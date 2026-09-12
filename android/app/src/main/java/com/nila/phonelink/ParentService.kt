package com.nila.phonelink

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nila.actions.Notifier
import com.nila.data.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The receiving phone, awake all night for the sake of one message.
 *
 * A foreground service for the same reason [com.nila.monitor.MonitorService] is:
 * it has to survive the screen going off for eight hours. The type is
 * `connectedDevice` rather than `dataSync`, and that is not a cosmetic choice --
 * since Android 15 a `dataSync` foreground service is capped at six hours in any
 * twenty-four, which is shorter than a night. A monitor that stops receiving at
 * 4am without saying so is precisely the failure this whole feature is supposed
 * to make impossible.
 *
 * All this class really does is turn a severity into behaviour. The mapping is
 * [LinkProtocol.tierFor] and it is tested; what happens here is the Android side
 * of each tier.
 */
class ParentService : Service() {

    companion object {
        private const val TAG = "ParentService"

        const val ACTION_START = "com.nila.parent.START"
        const val ACTION_STOP = "com.nila.parent.STOP"

        /** Sent by the notification action and by [AlertActivity] on the way out. */
        const val ACTION_SILENCE = "com.nila.parent.SILENCE"

        private val _status =
            MutableStateFlow<ParentLink.Status>(ParentLink.Status.Unpaired)

        /** Readable from Settings without binding. */
        val status: StateFlow<ParentLink.Status> = _status.asStateFlow()

        private val _lastAlert = MutableStateFlow<String?>(null)
        val lastAlert: StateFlow<String?> = _lastAlert.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, ParentService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ParentService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private lateinit var notifier: Notifier
    private lateinit var store: LinkStore
    @Volatile private var link: ParentLink? = null
    private var player: AlertPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        notifier.ensureChannels()
        store = LinkStore(this)
        player = AlertPlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopReceiving(); return START_NOT_STICKY }
            ACTION_SILENCE -> { player?.stop(); return START_STICKY }
        }
        startReceiving()
        // Restarted by the system if it is ever killed. The whole value of this
        // service is that it is running at 3am; a one-shot that dies quietly is
        // worse than not offering the feature.
        return START_STICKY
    }

    private fun startReceiving() {
        if (link != null) return

        goForeground("Listening for the monitoring phone", "Connecting…")

        // The link's own Wi-Fi lock keeps the radio up; this keeps the watchdog
        // thread running on time. In Doze a sleeping thread can be parked for
        // minutes, which would make the ten-second link check meaningless.
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nila:parent")
                .apply { setReferenceCounted(false); acquire() }
        }

        link = ParentLink(this, store).also { l ->
            l.start(onFrame = ::onFrame, onLinkLost = ::onLinkLost)
            kotlin.concurrent.thread(name = "nila-parent-status") {
                // Mirror the link's status out to the UI. A plain thread rather
                // than a scope: this Service is not a LifecycleService and the
                // loop ends when the link is closed.
                while (link != null) {
                    _status.value = l.status.value
                    updateForeground(l.status.value)
                    runCatching { Thread.sleep(1_000) }
                }
            }
        }
    }

    /**
     * One frame arrived. Decide what, if anything, the person should experience.
     *
     * Only ALERT frames can make noise. A STATE frame is the heartbeat carrying
     * a status line, and it updates the ongoing notification silently -- if the
     * live cry timer could raise an alert, the parent phone would sound every
     * three seconds for the length of an episode.
     */
    private fun onFrame(frame: LinkProtocol.Frame) {
        if (frame.kind != LinkProtocol.Kind.ALERT) return

        val severity = Severity.of(frame.severity)
        _lastAlert.value = frame.title

        when (frame.tier) {
            LinkProtocol.Tier.IGNORE -> {
                // Level 1 is a note in the guardian's log. It is not news, and
                // sending it to a second phone would train its owner to ignore
                // the two levels that are.
                Log.d(TAG, "ignored level ${frame.severity}: ${frame.title}")
            }

            LinkProtocol.Tier.NOTIFY -> {
                notifier.alert(frame.title, frame.body, severity, id = Notifier.ID_PARENT)
            }

            LinkProtocol.Tier.ALARM -> {
                notifier.alert(frame.title, frame.body, severity, id = Notifier.ID_PARENT)
                player?.sound(frame.tier)
            }

            LinkProtocol.Tier.FULL_SCREEN -> {
                // The sound starts first and does not depend on the screen. If
                // the full-screen permission was never granted, Android shows
                // this as a heads-up banner instead and says nothing about it --
                // so the alarm has to be the part that is guaranteed to happen.
                player?.sound(frame.tier)
                notifier.alert(
                    frame.title, frame.body, severity,
                    id = Notifier.ID_PARENT,
                    fullScreen = fullScreenIntent(frame),
                )
            }
        }
    }

    private fun fullScreenIntent(frame: LinkProtocol.Frame): PendingIntent =
        PendingIntent.getActivity(
            this,
            Notifier.ID_PARENT,
            AlertActivity.intentFor(this, frame.title, frame.body, frame.severity),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * The guardian stopped talking without saying goodbye.
     *
     * Treated as URGENT, which means a sounding alarm. That is a deliberate
     * choice and it is the most important line in this file: an unexplained
     * silence from the phone watching the baby carries exactly as much weight as
     * that phone reporting a problem, because from here they are
     * indistinguishable and only one of them is safe to ignore.
     */
    private fun onLinkLost() {
        notifier.alert(
            title = "Lost contact with the monitoring phone",
            body = "Nothing has been heard for " +
                "${LinkProtocol.LINK_TIMEOUT_MS / 1000} seconds. Check the baby, " +
                "and check the other phone is awake and on Wi-Fi.",
            severity = Severity.URGENT,
            id = Notifier.ID_LINK,
        )
        player?.sound(LinkProtocol.Tier.ALARM)
    }

    private fun updateForeground(status: ParentLink.Status) {
        val (title, detail) = when (status) {
            is ParentLink.Status.Connected ->
                "Receiving alerts" to (_lastAlert.value ?: "Connected to the monitoring phone")
            is ParentLink.Status.Searching -> "Looking for the monitoring phone" to status.detail
            is ParentLink.Status.Lost -> "Lost contact" to "The monitoring phone went quiet"
            is ParentLink.Status.Stopped -> "Monitoring stopped" to status.reason
            ParentLink.Status.Unpaired -> "Not paired" to "Pair with the monitoring phone first"
        }
        runCatching {
            notifier.update(Notifier.ID_PARENT_SERVICE,
                            notifier.monitoringNotification(title, detail))
        }
    }

    private fun goForeground(title: String, detail: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(
            this,
            Notifier.ID_PARENT_SERVICE,
            notifier.monitoringNotification(title, detail),
            type,
        )
    }

    private fun stopReceiving() {
        runCatching { link?.close() }
        link = null
        player?.stop()
        runCatching { wakeLock?.release() }
        wakeLock = null
        _status.value = ParentLink.Status.Unpaired
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { link?.close() }
        link = null
        runCatching { player?.close() }
        player = null
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }
}
