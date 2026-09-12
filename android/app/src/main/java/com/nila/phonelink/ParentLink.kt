package com.nila.phonelink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The receiving half, on the phone in the parent's room.
 *
 * Two jobs, and the second one is the one that matters. The first is to hold a
 * socket open to the guardian and hand frames to a listener. The second is to
 * notice when the frames stop -- because a baby monitor that has silently died
 * is more dangerous than no baby monitor at all, and on this side of the link
 * "everything is fine" and "the other phone is dead" look identical.
 *
 * So silence is an event here, not the absence of one. [LinkProtocol.LINK_TIMEOUT_MS]
 * of it raises an alarm, and a deliberate stop on the guardian sends a BYE so it
 * does not.
 */
class ParentLink(
    private val context: Context,
    private val store: LinkStore,
) : Closeable {

    companion object {
        private const val TAG = "ParentLink"

        /** Reconnect backoff, in milliseconds. Capped: this must keep trying. */
        private val BACKOFF = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val RESOLVE_TIMEOUT_MS = 6_000L
    }

    sealed interface Status {
        /** These two phones have never been paired. */
        data object Unpaired : Status

        /** Looking for the guardian, or waiting to retry. */
        data class Searching(val detail: String) : Status

        /** Frames are arriving. [since] is when this connection came up. */
        data class Connected(val host: String, val since: Long) : Status

        /**
         * The guardian said it was stopping. Quiet, deliberate, not an alarm --
         * somebody tapped stop and this phone should say so plainly.
         */
        data class Stopped(val reason: String) : Status

        /**
         * The guardian went silent without saying goodbye. This is the alarm
         * case: the phone by the cot may have crashed, been killed by battery
         * management, or lost Wi-Fi, and nobody is watching the baby.
         */
        data class Lost(val lastSeenAtMs: Long) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Unpaired)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var lastFrameAtMs = 0L
    @Volatile private var lastSeq = -1L
    @Volatile private var sawGoodbye = false

    private var loopThread: Thread? = null
    private var watchdogThread: Thread? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var onFrame: ((LinkProtocol.Frame) -> Unit)? = null
    private var onLinkLost: (() -> Unit)? = null

    /**
     * Begin, and keep going until [close].
     *
     * [onLinkLost] fires once per outage, not once per check -- an alarm that
     * re-fires every second is an alarm that gets the app uninstalled.
     */
    fun start(
        onFrame: (LinkProtocol.Frame) -> Unit,
        onLinkLost: () -> Unit,
    ) {
        if (running) return
        if (store.key == null) {
            _status.value = Status.Unpaired
            return
        }
        this.onFrame = onFrame
        this.onLinkLost = onLinkLost
        running = true
        acquireLocks()
        loopThread = thread(name = "nila-parent-link") { connectLoop() }
        watchdogThread = thread(name = "nila-parent-watch") { watchdogLoop() }
    }

    /**
     * Keep the Wi-Fi radio and multicast up while the screen is off.
     *
     * Without the Wi-Fi lock, Doze parks the radio and the guardian's frames
     * arrive in bursts minutes apart, which the watchdog correctly reads as the
     * link having died. The multicast lock is for discovery: several OEM builds,
     * vivo's among them, drop inbound multicast to a sleeping app, and mDNS is
     * multicast.
     */
    private fun acquireLocks() {
        runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nila:parent-link"
            ).apply { setReferenceCounted(false); acquire() }
            multicastLock = wifi.createMulticastLock("nila:discovery")
                .apply { setReferenceCounted(false); acquire() }
        }.onFailure { Log.w(TAG, "could not hold Wi-Fi awake", it) }
    }

    private fun connectLoop() {
        var attempt = 0
        while (running) {
            val key = store.key
            if (key == null) {
                _status.value = Status.Unpaired
                return
            }

            // The remembered address first. When it is right -- which is most
            // nights, on a home network handing out the same lease -- this
            // reconnects in milliseconds instead of waiting on discovery.
            val host = store.host?.takeIf { it.isNotBlank() } ?: discover()

            if (host == null) {
                _status.value = Status.Searching("Looking for the monitoring phone")
                sleep(BACKOFF[attempt.coerceAtMost(BACKOFF.lastIndex)])
                attempt++
                continue
            }

            if (hold(host, key)) {
                attempt = 0
            } else {
                // A remembered address that no longer answers is worse than no
                // address: it makes every retry fail the same way. Forget it and
                // let discovery have the next attempt.
                if (host == store.host) runCatching {
                    kotlinx.coroutines.runBlocking { store.rememberHost("") }
                }
                attempt++
                sleep(BACKOFF[attempt.coerceAtMost(BACKOFF.lastIndex)])
            }
        }
    }

    /** Connect, handshake, and read until the socket dies. True if it ever came up. */
    private fun hold(host: String, key: ByteArray): Boolean {
        var connected = false
        try {
            val sock = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, LinkProtocol.PORT), CONNECT_TIMEOUT_MS)
            }
            socket = sock

            val out = sock.getOutputStream().writer()
            out.write(LinkProtocol.encode(hello(), key))
            out.write("\n")
            out.flush()

            connected = true
            sawGoodbye = false
            lastSeq = -1L
            lastFrameAtMs = System.currentTimeMillis()
            _status.value = Status.Connected(host, lastFrameAtMs)
            runCatching { kotlinx.coroutines.runBlocking { store.rememberHost(host) } }
            Log.i(TAG, "connected to $host")

            val reader = sock.getInputStream().bufferedReader()
            while (running) {
                val line = reader.readLine() ?: break
                val frame = LinkProtocol.decode(line, key)
                if (frame == null) {
                    // Unauthenticated, malformed, or from a version this phone
                    // does not speak. Dropped without comment -- see decode.
                    continue
                }
                // Strictly increasing, so a frame replayed onto the socket
                // cannot re-raise an alert that was already handled.
                if (frame.seq <= lastSeq) continue
                lastSeq = frame.seq
                lastFrameAtMs = System.currentTimeMillis()

                if (frame.kind == LinkProtocol.Kind.BYE) {
                    sawGoodbye = true
                    _status.value = Status.Stopped(frame.body.ifBlank { frame.title })
                } else if (_status.value !is Status.Connected) {
                    _status.value = Status.Connected(host, lastFrameAtMs)
                }
                runCatching { onFrame?.invoke(frame) }
                    .onFailure { Log.w(TAG, "listener threw", it) }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "connection to $host ended: ${t.message}")
        } finally {
            runCatching { socket?.close() }
            socket = null
        }
        return connected
    }

    private fun hello() = LinkProtocol.Frame(
        kind = LinkProtocol.Kind.HELLO,
        seq = 0,
        severity = 0,
        seconds = 0,
        trend = "",
        monitoring = false,
        sentAtMs = System.currentTimeMillis(),
        title = android.os.Build.MODEL ?: "parent phone",
        body = "",
    )

    /**
     * Raise the alarm when the frames stop.
     *
     * Deliberately does not care *why* they stopped. Wi-Fi dropped, the guardian
     * crashed, battery management killed it, somebody carried the phone out of
     * range -- from here they are the same event and they need the same
     * response, which is to tell a person that nothing is watching the baby.
     *
     * A BYE suppresses it, because a monitor somebody chose to stop is not a
     * monitor that failed.
     */
    private fun watchdogLoop() {
        var alarmed = false
        while (running) {
            sleep(1_000)
            if (!running) break

            val last = lastFrameAtMs
            val stale = last > 0 &&
                System.currentTimeMillis() - last > LinkProtocol.LINK_TIMEOUT_MS

            if (stale && !sawGoodbye && !alarmed) {
                alarmed = true
                _status.value = Status.Lost(last)
                Log.w(TAG, "no frame for ${LinkProtocol.LINK_TIMEOUT_MS}ms")
                runCatching { onLinkLost?.invoke() }
            } else if (!stale && alarmed) {
                // Recovered. Re-arm, so the next outage alarms too.
                alarmed = false
            }
        }
    }

    /**
     * Find the guardian over mDNS.
     *
     * Blocking, with a timeout, because the caller is a dedicated reconnect
     * thread that has nothing else to do and the alternative is a callback maze
     * around a fundamentally sequential retry loop.
     */
    private fun discover(): String? {
        val manager = runCatching {
            context.getSystemService(Context.NSD_SERVICE) as NsdManager
        }.getOrNull() ?: return null

        val found = java.util.concurrent.atomic.AtomicReference<String>(null)
        val latch = CountDownLatch(1)

        @Suppress("DEPRECATION")
        val resolver = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                found.set(info.host?.hostAddress)
                latch.countDown()
            }
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "resolve failed ($code)")
                latch.countDown()
            }
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains("_nila") != true) return
                @Suppress("DEPRECATION")
                runCatching { manager.resolveService(info, resolver) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "discovery failed ($code); use the address by hand")
                latch.countDown()
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }

        return try {
            manager.discoverServices(
                LinkProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery
            )
            latch.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            found.get()
        } catch (t: Throwable) {
            Log.w(TAG, "discovery threw", t)
            null
        } finally {
            runCatching { manager.stopServiceDiscovery(discovery) }
        }
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    override fun close() {
        running = false
        runCatching { socket?.close() }
        socket = null
        loopThread?.interrupt()
        watchdogThread?.interrupt()
        loopThread?.join(1_000)
        watchdogThread?.join(1_000)
        loopThread = null
        watchdogThread = null
        runCatching { wifiLock?.release() }
        runCatching { multicastLock?.release() }
        wifiLock = null
        multicastLock = null
        Log.i(TAG, "parent link stopped")
    }
}
