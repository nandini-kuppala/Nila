package com.nila.bridge

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.nila.data.NilaDatabase
import com.nila.monitor.MonitorService
import java.io.Closeable
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A read-only night dashboard, served to a laptop on the same Wi-Fi.
 *
 * A note on what this is not: vivo's Office Kit is a closed application --
 * screen mirroring, clipboard and file transfer -- with no public API, so no
 * third-party app can integrate with it programmatically. What a third-party app
 * *can* do is be useful on the larger screen Office Kit is already showing, and
 * serve something a browser can open directly. This does the second thing, which
 * works with or without Office Kit running.
 *
 * The privacy posture is deliberate and narrow:
 *   - off by default, started only by an explicit tap
 *   - binds to the local network only, and refuses connections from anywhere
 *     that is not a private address
 *   - serves counts, durations and timings; never audio, never video, never a
 *     stored document
 *   - stops when monitoring stops
 */
class DeskBridge(
    private val context: Context,
    private val db: NilaDatabase,
) : Closeable {

    companion object {
        private const val TAG = "DeskBridge"
        const val PORT = 8787
    }

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(2)
    @Volatile private var running = false
    @Volatile var requestsServed = 0; private set

    val isRunning: Boolean get() = running

    /** The address to type into a laptop browser, or null if not on Wi-Fi. */
    fun localAddress(): String? {
        // NetworkInterface rather than WifiManager.connectionInfo: the latter is
        // deprecated, needs location permission on modern Android, and returns
        // nothing useful on a tethered or emulated connection.
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .firstOrNull { it.hostAddress?.contains('.') == true && it.isSiteLocalAddress }
                ?.hostAddress
                ?.let { "http://$it:$PORT" }
        } catch (t: Throwable) {
            Log.w(TAG, "could not determine local address", t)
            null
        }
    }

    fun start(): Boolean {
        if (running) return true
        return try {
            // Bound in two steps so SO_REUSEADDR can be set first. Without it a
            // stop-then-start inside the TIME_WAIT window fails to bind, which
            // from the user's side looks like the toggle simply not working.
            server = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(PORT))
            }
            running = true
            acceptThread = thread(name = "nila-bridge") { acceptLoop() }
            Log.i(TAG, "dashboard on ${localAddress() ?: "unknown address"}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not start", t)
            running = false
            false
        }
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
            pool.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        client.use { sock ->
            try {
                // Anything off the local network is refused outright. The
                // dashboard exists for the laptop on the same Wi-Fi and there is
                // no scenario where a public address should reach it.
                val remote = sock.inetAddress
                if (!isLocal(remote)) {
                    Log.w(TAG, "refused non-local ${remote.hostAddress}")
                    sock.getOutputStream().write(
                        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
                            .toByteArray()
                    )
                    return
                }

                val reader = sock.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val path = requestLine.split(" ").getOrNull(1) ?: "/"

                val (type, body) = when {
                    path.startsWith("/api") -> "application/json" to json()
                    else -> "text/html; charset=utf-8" to page()
                }
                val bytes = body.toByteArray()
                sock.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: $type\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Cache-Control: no-store\r\n" +
                            "Connection: close\r\n\r\n").toByteArray()
                    )
                    write(bytes)
                    flush()
                }
                requestsServed++
            } catch (t: Throwable) {
                Log.w(TAG, "request failed", t)
            }
        }
    }

    private fun isLocal(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isSiteLocalAddress ||
            address.isLinkLocalAddress

    private fun json(): String {
        val state = MonitorService.state.value
        val now = System.currentTimeMillis()
        val dayMs = TimeUnit.DAYS.toMillis(1)

        val (seconds, episodes) = runBlockingSafely {
            db.events().cryingSecondsBetween(now - dayMs, now) to
                db.events().cryEpisodesBetween(now - dayMs, now)
        } ?: (0 to 0)

        val events = runBlockingSafely {
            db.events().since(now - dayMs).take(40)
        } ?: emptyList()

        return buildString {
            append("{")
            append("\"monitoring\":${state.running},")
            append("\"status\":\"${escape(state.statusLine)}\",")
            append("\"accelerator\":\"${escape(state.accelerator)}\",")
            append("\"detectLatencyMs\":${"%.2f".format(state.detectorLatencyMs)},")
            append("\"cryingSecondsToday\":$seconds,")
            append("\"episodesToday\":$episodes,")
            append("\"events\":[")
            events.forEachIndexed { i, e ->
                if (i > 0) append(",")
                append("{\"at\":${e.startedAtMs},")
                append("\"kind\":\"${escape(e.kind)}\",")
                append("\"severity\":${e.severityLevel},")
                append("\"seconds\":${e.durationSeconds},")
                append("\"trend\":\"${escape(e.trend ?: "")}\"}")
            }
            append("]}")
        }
    }

    private fun <T> runBlockingSafely(block: suspend () -> T): T? = try {
        kotlinx.coroutines.runBlocking { block() }
    } catch (t: Throwable) {
        Log.w(TAG, "query failed", t)
        null
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun page(): String = DASHBOARD_HTML

    /**
     * Stop serving, and do not return until the port is actually free.
     *
     * Closing the socket makes the blocked accept() throw, but the thread has
     * not necessarily unwound by the time close() returns -- and until it has,
     * the OS still considers the port bound. Without the join, "stop serving"
     * followed by "start serving" fails with EADDRINUSE, which reads to a user
     * as the toggle being broken.
     */
    override fun close() {
        running = false
        runCatching { server?.close() }
        acceptThread?.join(1_000)
        acceptThread = null
        server = null
        pool.shutdownNow()
        runCatching { pool.awaitTermination(1, TimeUnit.SECONDS) }
        Log.i(TAG, "dashboard stopped after $requestsServed requests")
    }
}
