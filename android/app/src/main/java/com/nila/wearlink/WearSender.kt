package com.nila.wearlink

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.nila.data.Severity
import com.nila.monitor.MonitorState

/**
 * Pushes state and alerts to a paired watch.
 *
 * This is the *second* tier of watch support, and it is worth being clear that
 * it is optional. Tier one is a plain high-importance notification, which Wear
 * OS bridges automatically and which a cheap band's companion app mirrors over
 * Bluetooth -- that already works, on hardware people own, with no code. What
 * this adds is a live cry timer, a per-severity vibration pattern the wearer can
 * identify without looking, and a watch screen that updates continuously rather
 * than showing one frozen notification.
 *
 * Every send is best-effort and failure is silent by design: the phone's own
 * alarm is the failsafe, and a safety alert must never depend on a radio link.
 */
class WearSender(private val context: Context) {

    companion object {
        private const val TAG = "WearSender"
        /** Below this, resending identical state is just radio for nothing. */
        private const val MIN_INTERVAL_MS = 1_500L
    }

    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }

    private var lastSentAt = 0L
    private var lastPayload: String? = null

    @Volatile var connectedNodes: Int = 0; private set

    fun sendState(state: MonitorState) {
        val evidence = state.currentEvidence
        val payload = WearProtocol.State(
            monitoring = state.running,
            headline = state.statusLine,
            cryingSeconds = evidence?.durationSeconds ?: 0,
            trend = evidence?.trend?.name.orEmpty(),
            severity = when (val p = state.phase) {
                is MonitorState.Phase.Escalated -> p.severity.level
                is MonitorState.Phase.Safety -> Severity.URGENT.level
                is MonitorState.Phase.CryDetected -> Severity.ATTENTION.level
                else -> 0
            },
        )
        send(WearProtocol.PATH_STATE, payload, force = false)
    }

    /** An alert always goes out, even if the state text has not changed. */
    fun sendAlert(headline: String, severity: Severity, seconds: Int) {
        send(
            WearProtocol.PATH_ALERT,
            WearProtocol.State(true, headline, seconds, "", severity.level),
            force = true,
        )
    }

    private fun send(path: String, state: WearProtocol.State, force: Boolean) {
        val encoded = state.encode()
        val fingerprint = "$path:${String(encoded)}"
        val now = System.currentTimeMillis()

        if (!force) {
            if (fingerprint == lastPayload) return
            if (now - lastSentAt < MIN_INTERVAL_MS) return
        }
        lastPayload = fingerprint
        lastSentAt = now

        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                connectedNodes = nodes.size
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, path, encoded)
                        .addOnFailureListener {
                            Log.w(TAG, "send to ${node.displayName} failed: ${it.message}")
                        }
                }
            }
            .addOnFailureListener {
                connectedNodes = 0
                Log.d(TAG, "no paired nodes: ${it.message}")
            }
    }

    fun describe(): String = when (connectedNodes) {
        0 -> "No Wear OS watch paired. Notifications still reach any watch or " +
            "band that mirrors them."
        1 -> "One watch paired and receiving live updates"
        else -> "$connectedNodes watches paired"
    }
}
