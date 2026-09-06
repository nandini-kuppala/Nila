package com.nila.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nila.data.CareKind
import com.nila.data.NilaDatabase
import com.nila.data.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private val Context.reminderStore by preferencesDataStore("reminders")

/**
 * Feeding and nappy-change reminders.
 *
 * Deliberately *elapsed-time* reminders rather than a fixed clock schedule. A
 * newborn does not feed at nine, twelve and three; it feeds roughly every so
 * often from the last one, and a reminder that ignores the feed you just gave is
 * a reminder that gets turned off within a day.
 *
 * So each reminder is armed relative to the most recent log of its own kind, and
 * logging that kind again re-arms it. Tapping "Fed" at 02:40 silently moves the
 * next nudge to 05:40 -- which is the behaviour that makes this survive contact
 * with an actual night.
 *
 * Nothing here is a medical instruction. The intervals are the parent's own, the
 * default is a common starting point rather than a recommendation, and the text
 * says how long it has been rather than what to do.
 */
class Reminders(private val context: Context) {

    companion object {
        private const val TAG = "Reminders"

        const val ACTION_FIRE = "com.nila.REMIND"
        const val EXTRA_KIND = "kind"

        /**
         * Two to three hours is the range most newborn feeding guidance quotes,
         * so the default sits inside it -- but it is a starting value the parent
         * changes, not advice.
         */
        const val DEFAULT_FEED_MINUTES = 180
        const val DEFAULT_DIAPER_MINUTES = 150

        /** Distinct request codes so the two alarms never overwrite each other. */
        private fun requestCode(kind: CareKind) = 4000 + kind.ordinal

        private fun enabledKey(kind: CareKind) =
            booleanPreferencesKey("enabled_${kind.name}")

        private fun intervalKey(kind: CareKind) =
            intPreferencesKey("interval_${kind.name}")

        fun defaultMinutes(kind: CareKind) = when (kind) {
            CareKind.FEED -> DEFAULT_FEED_MINUTES
            else -> DEFAULT_DIAPER_MINUTES
        }

        /** The two kinds this feature covers. Sleep is not a thing to nag about. */
        val KINDS = listOf(CareKind.FEED, CareKind.DIAPER)
    }

    data class Setting(
        val kind: CareKind,
        val enabled: Boolean,
        val intervalMinutes: Int,
        val lastLoggedAtMs: Long?,
    ) {
        val dueAtMs: Long?
            get() = lastLoggedAtMs?.plus(TimeUnit.MINUTES.toMillis(intervalMinutes.toLong()))
    }

    private val alarms =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    suspend fun settings(): List<Setting> {
        val prefs = context.reminderStore.data.first()
        val dao = NilaDatabase.get(context).care()
        return KINDS.map { kind ->
            Setting(
                kind = kind,
                enabled = prefs[enabledKey(kind)] ?: false,
                intervalMinutes = prefs[intervalKey(kind)] ?: defaultMinutes(kind),
                lastLoggedAtMs = dao.latestOf(kind.name)?.atMs,
            )
        }
    }

    suspend fun setEnabled(kind: CareKind, enabled: Boolean) {
        context.reminderStore.edit { it[enabledKey(kind)] = enabled }
        if (enabled) reschedule(kind) else cancel(kind)
    }

    suspend fun setInterval(kind: CareKind, minutes: Int) {
        context.reminderStore.edit {
            it[intervalKey(kind)] = minutes.coerceIn(15, 720)
        }
        reschedule(kind)
    }

    /**
     * Re-arm from the most recent log of this kind.
     *
     * Called after every care entry, which is what keeps the reminder honest:
     * it always counts from the last real event rather than from whenever the
     * app happened to start.
     */
    suspend fun reschedule(kind: CareKind) {
        val setting = settings().firstOrNull { it.kind == kind } ?: return
        if (!setting.enabled) return cancel(kind)

        val due = setting.dueAtMs ?: (System.currentTimeMillis() +
            TimeUnit.MINUTES.toMillis(setting.intervalMinutes.toLong()))

        // Already overdue when re-armed -- for instance the phone was off.
        // Nudging a minute from now is better than firing instantly, which
        // would look like a bug.
        val at = maxOf(due, System.currentTimeMillis() + 60_000L)
        schedule(kind, at)
    }

    suspend fun rescheduleAll() = KINDS.forEach { reschedule(it) }

    private fun schedule(kind: CareKind, atMs: Long) {
        val pending = pendingIntent(kind)
        try {
            // Exact where the OS allows it, inexact where it does not. A feed
            // reminder that arrives twenty minutes late is still useful, so this
            // never asks for the special "alarms and reminders" permission --
            // the prompt costs more goodwill than the precision is worth.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            } else {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
            Log.i(TAG, "${kind.name} reminder armed for $atMs")
        } catch (t: Throwable) {
            Log.w(TAG, "could not arm ${kind.name} reminder", t)
            runCatching {
                alarms.set(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
        }
    }

    /**
     * Cancelling the alarm is not enough on its own.
     *
     * AlarmManager.cancel drops the scheduled wake-up but leaves the
     * PendingIntent registered with the system, where it stays live and
     * re-triggerable. Retiring both is what actually turns the reminder off.
     */
    fun cancel(kind: CareKind) {
        val pending = pendingIntent(kind)
        alarms.cancel(pending)
        pending.cancel()
    }

    private fun pendingIntent(kind: CareKind): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(kind),
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_KIND, kind.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Posts the nudge, then arms the next one.
 *
 * Rearming here rather than using a repeating alarm is what makes the chain
 * self-correcting: if the parent logged a feed since this alarm was set, the
 * reschedule counts from that instead and the reminder quietly moves.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminders.ACTION_FIRE) return
        val kind = runCatching {
            CareKind.valueOf(intent.getStringExtra(Reminders.EXTRA_KIND) ?: return)
        }.getOrNull() ?: return

        // goAsync keeps the receiver alive past onReceive; without it the
        // process can be killed mid-query and the chain of reminders stops dead.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reminders = Reminders(context)
                val setting = reminders.settings().firstOrNull { it.kind == kind }
                if (setting?.enabled == true) {
                    val elapsed = setting.lastLoggedAtMs?.let {
                        (System.currentTimeMillis() - it) / 60_000L
                    }
                    val notifier = Notifier(context)
                    notifier.ensureChannels()
                    notifier.alert(
                        title = when (kind) {
                            CareKind.FEED -> "Feeding reminder"
                            else -> "Nappy change reminder"
                        },
                        body = buildString {
                            append(
                                when (kind) {
                                    CareKind.FEED -> "It has been "
                                    else -> "The last change was "
                                }
                            )
                            append(
                                when {
                                    elapsed == null -> "a while"
                                    elapsed >= 120 -> "${elapsed / 60}h ${elapsed % 60}m"
                                    else -> "${elapsed}m"
                                }
                            )
                            append(
                                when (kind) {
                                    CareKind.FEED -> " since the last feed you logged."
                                    else -> " ago."
                                }
                            )
                        },
                        severity = Severity.ATTENTION,
                        id = NOTIFICATION_BASE + kind.ordinal,
                    )
                }
                reminders.reschedule(kind)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val NOTIFICATION_BASE = 40
    }
}
