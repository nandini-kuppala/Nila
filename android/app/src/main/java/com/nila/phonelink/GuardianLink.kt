package com.nila.phonelink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.nila.data.Severity
import com.nila.monitor.MonitorState
import java.io.BufferedReader
import java.io.Closeable
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The listening half of the phone-to-phone link, on the phone by the cot.
 *
 * Structured like [com.nila.bridge.DeskBridge] and for the same reasons -- the
 * two-step bind so `SO_REUSEADDR` is set before binding, the refusal of anything
 * that is not a private address, the join on close so the port is actually free
 * before the next start. Those were not free the first time and are not worth
 * rediscovering.
 *
 * What is different is that these connections are long-lived. The dashboard
 * answers a request and hangs up; this holds a socket open all night and pushes
 * down it. So there is a heartbeat, and a client that stops reading is dropped
 * rather than allowed to block the writer.
 *
 * Every send is best-effort and a failure is not escalated. The guardian's own
 * notification and its own speaker are the failsafe -- a safety alert must never
 * depend on a radio link, which is the one rule the build spec sets for this
 * layer and the one that makes the feature safe to add at all.
 */
class GuardianLink(
    private val context: Context,
    private val store: LinkStore,
) : Closeable {

    companion object {
        private const val TAG = "GuardianLink"

        /**
         * One link per process, shared by both lanes.
         *
         * The microphone service and the camera service run at the same time
         * and both have alerts to send. Two instances would mean two attempts
         * to bind the same port, the second failing -- and the lane that failed
         * would go silently unrelayed, which is the class of bug this whole
         * feature exists to prevent.
         *
         * Reference counted rather than owned by one service, because either
         * lane can be stopped while the other keeps running.
         */
        @Volatile private var shared: GuardianLink? = null
        private var holders = 0

        /** The shared link, started if it was not already. Null if unpaired. */
        @Synchronized
        fun acquire(context: Context): GuardianLink? {
            val store = LinkStore(context.applicationContext)
            if (store.currentRole != LinkStore.Role.GUARDIAN) return null
            if (store.key == null) return null

            val existing = shared
            if (existing != null && existing.isRunning) {
                holders++
                return existing
            }
            val link = GuardianLink(context.applicationContext, store)
            if (!link.start()) return null
            shared = link
            holders = 1
            return link
        }

        /**
         * Give up one hold. The socket closes only when the last one goes.
         *
         * [goodbye] is sent just before closing, so the receiving phone shows
         * "stopped" rather than sounding its lost-contact alarm -- and is
         * deliberately not sent when another lane is still running, because
         * nothing has stopped from the other phone's point of view.
         *
         * [link] is the instance the caller was handed. Releases from a holder
         * of an *older* link are ignored: were they counted, a lane that had
         * been given a replacement could be closed by the last holder of the
         * link it replaced, and the other phone would stop being told anything
         * without either side noticing.
         */
        @Synchronized
        fun release(link: GuardianLink?, goodbye: String? = null) {
            if (link == null || link !== shared) return
            holders = (holders - 1).coerceAtLeast(0)
            if (holders > 0) return
            shared?.let { link ->
                goodbye?.let { runCatching { link.sendGoodbye(it) } }
                // The frame is queued on a pool thread; give it a moment to
                // reach the wire before the socket is torn out from under it.
                runCatching { Thread.sleep(150) }
                runCatching { link.close() }
            }
            shared = null
        }

        /** For the settings screen, which neither holds nor starts the link. */
        fun peek(): GuardianLink? = shared

        /** A client that has not authenticated by now is not a parent phone. */
        private const val HELLO_TIMEOUT_MS = 5_000

        /**
         * A write that blocks this long has a reader that is gone or asleep.
         * Dropped rather than waited on: one unresponsive socket must not delay
         * the alert going to the other phone.
         */
        private const val WRITE_TIMEOUT_MS = 4_000
    }

    private class Client(val socket: Socket, val out: OutputStreamWriter) {
        val label: String = socket.inetAddress?.hostAddress ?: "?"
    }

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(2)
    private val clients = CopyOnWriteArrayList<Client>()
    private val seq = AtomicLong(0)

    @Volatile private var running = false
    @Volatile private var nsdManager: NsdManager? = null
    @Volatile private var registration: NsdManager.RegistrationListener? = null

    /** Last state pushed, so the heartbeat has something to repeat. */
    @Volatile private var lastState: LinkProtocol.Frame? = null

    val isRunning: Boolean get() = running
    val connectedParents: Int get() = clients.size

    /** The address to type on the other phone if discovery does not find us. */
    fun localAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { it.hostAddress?.contains('.') == true && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (t: Throwable) {
        Log.w(TAG, "could not determine local address", t)
        null
    }

    fun start(): Boolean {
        if (running) return true
        if (store.key == null) {
            Log.i(TAG, "not paired; link stays down")
            return false
        }
        return try {
            server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(LinkProtocol.PORT))
            }
            running = true
            acceptThread = thread(name = "nila-link-accept") { acceptLoop() }
            heartbeatThread = thread(name = "nila-link-beat") { heartbeatLoop() }
            advertise()
            Log.i(TAG, "listening on ${localAddress()}:${LinkProtocol.PORT}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not start", t)
            running = false
            false
        }
    }

    /**
     * Announce the service over mDNS so the parent phone needs no IP address.
     *
     * Best-effort in the strongest sense: some OEM builds and plenty of consumer
     * routers filter multicast, and on those this registration succeeds and is
     * simply never seen. That is survivable because the parent can be pointed at
     * an address by hand, which is why [localAddress] is shown in Settings
     * rather than hidden behind discovery working.
     */
    private fun advertise() {
        runCatching {
            val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = LinkProtocol.SERVICE_NAME
                serviceType = LinkProtocol.SERVICE_TYPE
                port = LinkProtocol.PORT
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "advertised as ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "advertise failed ($code); manual address still works")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdManager = manager
            registration = listener
        }.onFailure { Log.w(TAG, "no mDNS", it) }
    }

    private fun acceptLoop() {
        val socket = server ?: return
        while (running) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "accept failed", t)
                break
            }
            pool.execute { admit(client) }
        }
    }

    /**
     * Let a connection in, but only once it has proved it holds the key.
     *
     * The check is not decoration. Without it, anything that can open a TCP
     * connection on the home network receives a running commentary on whether
     * there is a baby in the house and whether anyone is awake.
     */
    private fun admit(socket: Socket) {
        val key = store.key ?: return runCatching { socket.close() }.let {}
        val remote: InetAddress = socket.inetAddress
        if (!isLocal(remote)) {
            Log.w(TAG, "refused non-local ${remote.hostAddress}")
            runCatching { socket.close() }
            return
        }

        try {
            socket.soTimeout = HELLO_TIMEOUT_MS
            socket.tcpNoDelay = true
            val reader = socket.getInputStream().bufferedReader()
            val hello = reader.readLine()
            val frame = hello?.let { LinkProtocol.decode(it, key) }
            if (frame == null || frame.kind != LinkProtocol.Kind.HELLO) {
                Log.w(TAG, "rejected ${remote.hostAddress}: no valid hello")
                runCatching { socket.close() }
                return
            }

            // Back to blocking reads. From here the parent says nothing; the
            // read side exists only so a closed connection is noticed.
            socket.soTimeout = 0
            val client = Client(socket, OutputStreamWriter(socket.getOutputStream()))
            clients += client
            Log.i(TAG, "parent phone connected from ${client.label}")

            // Whatever is happening right now, immediately -- a phone that has
            // just reconnected should not show a blank screen until the next
            // heartbeat.
            lastState?.let { send(client, it.copy(seq = seq.incrementAndGet())) }

            watch(client, reader)
        } catch (t: Throwable) {
            Log.w(TAG, "handshake failed", t)
            runCatching { socket.close() }
        }
    }

    /** Block until the client goes away, then forget it. */
    private fun watch(client: Client, reader: BufferedReader) {
        try {
            while (running && reader.readLine() != null) {
                // The parent has nothing to say. Reaching here at all means the
                // socket is alive, which is the only information wanted.
            }
        } catch (t: Throwable) {
            Log.d(TAG, "parent ${client.label} dropped: ${t.message}")
        } finally {
            drop(client)
        }
    }

    private fun drop(client: Client) {
        clients.remove(client)
        runCatching { client.socket.close() }
        Log.i(TAG, "parent ${client.label} gone; ${clients.size} left")
    }

    private fun isLocal(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isSiteLocalAddress ||
            address.isLinkLocalAddress

    /**
     * Say something every few seconds even when nothing has changed.
     *
     * This is what makes the parent phone's silence meaningful. Without a
     * heartbeat, a guardian whose process was killed at midnight looks exactly
     * like a guardian watching a baby who is sleeping soundly, and the parent
     * phone has no way to tell the difference until morning.
     */
    private fun heartbeatLoop() {
        while (running) {
            runCatching { Thread.sleep(LinkProtocol.HEARTBEAT_MS) }
            if (!running) break
            val state = lastState ?: idleState()
            broadcast(state.copy(seq = seq.incrementAndGet(),
                                 sentAtMs = System.currentTimeMillis()))
        }
    }

    private fun idleState() = LinkProtocol.Frame(
        kind = LinkProtocol.Kind.STATE,
        seq = 0,
        severity = 0,
        seconds = 0,
        trend = "",
        monitoring = false,
        sentAtMs = System.currentTimeMillis(),
        title = "Nila is not monitoring",
        body = "",
    )

    /** Mirror the monitor's live state. Cheap, and coalesced by the heartbeat. */
    fun sendState(state: MonitorState) {
        val evidence = state.currentEvidence
        lastState = LinkProtocol.Frame(
            kind = LinkProtocol.Kind.STATE,
            seq = seq.incrementAndGet(),
            severity = when (val p = state.phase) {
                is MonitorState.Phase.Escalated -> p.severity.level
                is MonitorState.Phase.Safety -> Severity.URGENT.level
                is MonitorState.Phase.CryDetected -> Severity.ATTENTION.level
                else -> 0
            },
            seconds = evidence?.durationSeconds ?: 0,
            trend = evidence?.trend?.name.orEmpty(),
            monitoring = state.running,
            sentAtMs = System.currentTimeMillis(),
            title = state.statusLine,
            body = "",
        )
    }

    /**
     * Wake the other phone.
     *
     * Sent immediately rather than folded into the heartbeat: three seconds is
     * nothing while watching a status line tick over, and far too long between
     * a baby needing somebody and a phone in another room making a sound.
     */
    fun sendAlert(title: String, body: String, severity: Severity, seconds: Int) {
        broadcast(
            LinkProtocol.Frame(
                kind = LinkProtocol.Kind.ALERT,
                seq = seq.incrementAndGet(),
                severity = severity.level,
                seconds = seconds,
                trend = "",
                monitoring = true,
                sentAtMs = System.currentTimeMillis(),
                title = title,
                body = body,
            )
        )
    }

    /**
     * Tell the parent the monitor is stopping on purpose.
     *
     * Without this, every deliberate stop reads on the other phone as the link
     * having failed, and raises the alarm it is supposed to raise when the
     * guardian dies unexpectedly. Distinguishing the two is what stops the
     * link-loss alarm from being the thing everybody learns to ignore.
     */
    fun sendGoodbye(reason: String) {
        broadcast(
            LinkProtocol.Frame(
                kind = LinkProtocol.Kind.BYE,
                seq = seq.incrementAndGet(),
                severity = 0,
                seconds = 0,
                trend = "",
                monitoring = false,
                sentAtMs = System.currentTimeMillis(),
                title = "Monitoring stopped",
                body = reason,
            )
        )
    }

    private fun broadcast(frame: LinkProtocol.Frame) {
        val key = store.key ?: return
        if (clients.isEmpty()) return
        val encoded = LinkProtocol.encode(frame, key)
        clients.forEach { client -> pool.execute { send(client, encoded) } }
    }

    private fun send(client: Client, frame: LinkProtocol.Frame) {
        val key = store.key ?: return
        send(client, LinkProtocol.encode(frame, key))
    }

    private fun send(client: Client, encoded: String) {
        try {
            client.socket.soTimeout = WRITE_TIMEOUT_MS
            client.out.write(encoded)
            client.out.write("\n")
            client.out.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "send to ${client.label} failed: ${t.message}")
            drop(client)
        }
    }

    fun describe(): String = when {
        !running -> "Not sharing alerts with another phone"
        clients.isEmpty() ->
            "Waiting for the other phone" +
                (localAddress()?.let { " — it can also be pointed at $it" } ?: "")
        clients.size == 1 -> "One phone connected and receiving alerts"
        else -> "${clients.size} phones connected"
    }

    override fun close() {
        if (!running) return
        running = false
        runCatching { registration?.let { nsdManager?.unregisterService(it) } }
        registration = null
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear()
        runCatching { server?.close() }
        acceptThread?.join(1_000)
        heartbeatThread?.interrupt()
        heartbeatThread?.join(1_000)
        acceptThread = null
        heartbeatThread = null
        server = null
        pool.shutdownNow()
        runCatching { pool.awaitTermination(1, TimeUnit.SECONDS) }
        Log.i(TAG, "link stopped")
    }
}
