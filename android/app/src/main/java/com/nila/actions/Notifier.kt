package com.nila.actions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nila.MainActivity
import com.nila.R
import com.nila.data.Severity

/**
 * Notifications, which are also how alerts reach a wrist.
 *
 * This is the whole watch integration for the common case and it is deliberately
 * boring: a standard high-importance notification is bridged to a paired Wear OS
 * watch automatically, and a cheap band's own companion app picks it up through
 * NotificationListenerService and buzzes. That covers every band a parent
 * already owns without a line of Bluetooth code.
 *
 * The vibration patterns are distinct per severity so the wrist is readable
 * without looking -- which matters, because the entire point of routing alerts
 * to a watch is that the phone stays silent next to a sleeping baby.
 */
class Notifier(private val context: Context) {

    companion object {
        const val CHANNEL_MONITOR = "nila.monitor"
        const val CHANNEL_ALERTS = "nila.alerts"
        const val CHANNEL_CRITICAL = "nila.critical"

        const val ID_MONITOR = 1
        const val ID_ALERT = 2

        /**
         * The camera lane's own foreground notification.
         *
         * Distinct from [ID_MONITOR] because both services can run at once --
         * the microphone lane and the camera lane -- and a shared id would mean
         * whichever started second replaced the other's notification and then
         * cancelled it on the way out, leaving a foreground service with no
         * notification and Android about to kill it.
         */
        const val ID_WATCH = 3

        /** Safety alerts, kept apart from cry alerts so neither replaces the other. */
        const val ID_SAFETY = 4

        /**
         * The ongoing notification for the receiving phone.
         *
         * A fifth id rather than reusing [ID_MONITOR], for the reason given
         * above it: one phone can in principle be both, and a shared id would
         * mean one foreground service cancelling the other's notification on the
         * way out and leaving the survivor about to be killed.
         */
        const val ID_PARENT_SERVICE = 5

        /** An alert relayed from the monitoring phone. */
        const val ID_PARENT = 6

        /**
         * The monitoring phone has gone quiet.
         *
         * Its own id so it cannot be replaced by -- or replace -- a relayed
         * alert. These two say opposite things and both need to be readable:
         * "your baby needs you" and "I can no longer tell you whether your baby
         * needs you" must not overwrite each other.
         */
        const val ID_LINK = 7

        /** Long-short-long reads as urgent; a single pulse reads as informational. */
        private val PATTERN_ATTENTION = longArrayOf(0, 180)
        private val PATTERN_URGENT = longArrayOf(0, 400, 180, 400)
        private val PATTERN_CRITICAL = longArrayOf(0, 700, 150, 700, 150, 700)
    }

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_monitor_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alerts_desc)
            enableVibration(true)
            vibrationPattern = PATTERN_URGENT
            setBypassDnd(false)
        }

        // Critical bypasses Do Not Disturb. A parent silences their phone at
        // night; a safety event is exactly the thing that should still get
        // through, and nothing else in this app uses this channel.
        val critical = NotificationChannel(
            CHANNEL_CRITICAL,
            "Safety alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Face not visible, or no movement. Bypasses Do Not Disturb."
            enableVibration(true)
            vibrationPattern = PATTERN_CRITICAL
            setBypassDnd(true)
        }

        manager.createNotificationChannels(listOf(monitor, alerts, critical))
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The persistent, silent notification the foreground service runs under. */
    fun monitoringNotification(status: String, detail: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_nila)
            .setContentTitle(status)
            .setContentText(detail)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /**
     * @param fullScreen when set, Android may show this alert as a full-screen
     * activity over the lock screen instead of a banner. Only supplied for
     * level 4 -- see [com.nila.phonelink.AlertActivity] for why the bar is that
     * high, and for the Android 14 permission that can silently downgrade it
     * back to a banner. Callers must not depend on the screen coming on.
     */
    fun alert(
        title: String,
        body: String,
        severity: Severity,
        id: Int = ID_ALERT,
        fullScreen: PendingIntent? = null,
    ) {
        val channel = if (severity.level >= Severity.CRITICAL.level)
            CHANNEL_CRITICAL else CHANNEL_ALERTS

        val pattern = when (severity) {
            Severity.NOTE, Severity.ATTENTION -> PATTERN_ATTENTION
            Severity.URGENT -> PATTERN_URGENT
            Severity.CRITICAL -> PATTERN_CRITICAL
        }

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_nila)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(pattern)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            // Keeps the alert on the wrist rather than forcing the phone screen
            // on, which would light up the room.
            .setLocalOnly(false)
            .apply { fullScreen?.let { setFullScreenIntent(it, true) } }
            .build()

        runCatching { manager.notify(id, notification) }
    }

    /** Replace a notification already on screen, keeping its id and position. */
    fun update(id: Int, notification: Notification) =
        runCatching { manager.notify(id, notification) }

    /**
     * Whether a level-4 alert can actually take the screen.
     *
     * False is the normal state on Android 14 and later: the permission is
     * granted at install only to calling and alarm-clock apps, and everything
     * else has to be sent to a Settings page to ask for it. Surfaced so the app
     * can say so plainly rather than letting the user discover the downgrade on
     * the night it matters.
     */
    val canUseFullScreenAlerts: Boolean
        get() = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) true
        else runCatching {
            (context.getSystemService(NotificationManager::class.java))
                ?.canUseFullScreenIntent() == true
        }.getOrDefault(false)

    fun clear(id: Int = ID_ALERT) = runCatching { manager.cancel(id) }

    val notificationsEnabled: Boolean get() = manager.areNotificationsEnabled()
}
