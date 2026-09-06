package com.nila

import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.actions.ReminderReceiver
import com.nila.actions.Reminders
import com.nila.data.CareKind
import com.nila.data.CareRecord
import com.nila.data.NilaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Feeding and nappy reminders, checked against the alarm manager rather than
 * against the switch that sets them.
 *
 * The behaviour that matters is not "a toggle turns green" -- it is that the
 * next nudge moves when the parent logs a feed. A reminder that keeps counting
 * from when it was first set would fire twenty minutes after a 3am feed, and
 * would be switched off the next morning.
 */
@RunWith(AndroidJUnit4::class)
class RemindersTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reminders = Reminders(context)

    @After
    fun tearDown() = runBlocking {
        Reminders.KINDS.forEach { reminders.setEnabled(it, false) }
    }

    /** Does an alarm actually exist? FLAG_NO_CREATE returns null when it does not. */
    private fun armedAlarm(kind: CareKind): PendingIntent? = PendingIntent.getBroadcast(
        context,
        4000 + kind.ordinal,
        Intent(context, ReminderReceiver::class.java)
            .setAction(Reminders.ACTION_FIRE)
            .putExtra(Reminders.EXTRA_KIND, kind.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
    )

    @Test
    fun enablingArmsAnAlarmAndDisablingCancelsIt() = runBlocking {
        reminders.setEnabled(CareKind.FEED, false)
        assertNull("stale alarm before the test", armedAlarm(CareKind.FEED))

        reminders.setEnabled(CareKind.FEED, true)
        assertNotNull("no alarm armed after enabling", armedAlarm(CareKind.FEED))

        reminders.setEnabled(CareKind.FEED, false)
        assertNull("alarm survived being disabled", armedAlarm(CareKind.FEED))
    }

    @Test
    fun defaultsAreTheDocumentedIntervals() = runBlocking {
        val settings = reminders.settings()
        assertEquals(2, settings.size)
        assertEquals(
            Reminders.DEFAULT_FEED_MINUTES,
            settings.first { it.kind == CareKind.FEED }.intervalMinutes,
        )
    }

    /**
     * The point of the whole feature: logging a feed moves the next reminder.
     *
     * Written against the *latest* feed rather than a fixture, because the real
     * care log is shared with every other suite on this device and inserting an
     * older row would not change what "the last feed" means.
     */
    @Test
    fun loggingAFeedPushesTheNextReminderOut() = runBlocking {
        val dao = NilaDatabase.get(context).care()
        reminders.setInterval(CareKind.FEED, 180)
        reminders.setEnabled(CareKind.FEED, true)

        val before = reminders.settings().first { it.kind == CareKind.FEED }.dueAtMs

        val fedAt = System.currentTimeMillis()
        dao.insert(CareRecord(kind = CareKind.FEED.name, atMs = fedAt))
        val setting = reminders.settings().first { it.kind == CareKind.FEED }

        assertEquals("the reminder is not anchored to the last feed",
                     fedAt, setting.lastLoggedAtMs)
        assertEquals(
            "the reminder is not one interval after the last feed",
            fedAt + TimeUnit.MINUTES.toMillis(180),
            setting.dueAtMs,
        )
        assertTrue(
            "the reminder did not move forward ($before -> ${setting.dueAtMs})",
            before == null || setting.dueAtMs!! > before,
        )
    }

    /** A reminder for a kind that has never been logged has nothing to count from. */
    @Test
    fun aKindNeverLoggedHasNoDueTime() = runBlocking {
        val settings = reminders.settings()
        settings.forEach { setting ->
            if (setting.lastLoggedAtMs == null) assertNull(setting.dueAtMs)
        }
    }

    @Test
    fun intervalIsClampedToSomethingSurvivable() = runBlocking {
        reminders.setInterval(CareKind.DIAPER, 2)
        assertTrue(
            reminders.settings().first { it.kind == CareKind.DIAPER }.intervalMinutes >= 15
        )
        reminders.setInterval(CareKind.DIAPER, 100_000)
        assertTrue(
            reminders.settings().first { it.kind == CareKind.DIAPER }.intervalMinutes <= 720
        )
    }
}
