package com.nila.ui

import com.nila.data.CareKind
import com.nila.data.CareRecord
import com.nila.data.EventRecord
import com.nila.data.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * That the dashboard's arithmetic is arithmetic.
 *
 * Every figure on the Monitor screen's lower half is derived here, which means
 * a mistake in this file is a wrong number shown to a parent under a heading
 * that says how much their baby cried -- and, in the colic panel, a number they
 * may repeat to a doctor. None of it is judgement, so all of it is testable,
 * and it is tested against the two things that actually go wrong with this kind
 * of code: events that straddle a boundary, and logs with a piece missing.
 *
 * A fixed zone and a fixed "now", because a test whose result depends on the
 * machine's timezone is a test that fails in one office and passes in another.
 */
class InsightsTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2025, 6, 12)
    private val now = at(today, 21, 0)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    private fun cry(
        startMs: Long,
        seconds: Int,
        cause: String? = null,
    ) = EventRecord(
        kind = "CRY_ENDED",
        severityLevel = Severity.ATTENTION.level,
        startedAtMs = startMs,
        endedAtMs = startMs + seconds * 1000L,
        durationSeconds = seconds,
        hypothesisLabel = cause,
    )

    private fun care(kind: CareKind, atMs: Long) =
        CareRecord(kind = kind.name, atMs = atMs)

    private fun build(
        events: List<EventRecord> = emptyList(),
        care: List<CareRecord> = emptyList(),
    ) = Insights.build(now, events, care, zone)

    // ------------------------------------------------------------- the week

    @Test
    fun `the window is seven days ending today`() {
        val summary = build()
        assertEquals(7, summary.days.size)
        assertEquals(today, summary.days.last().date)
        assertEquals(today.minusDays(6), summary.days.first().date)
        assertTrue(summary.days.last().isToday)
        assertEquals(1, summary.days.count { it.isToday })
    }

    @Test
    fun `crying is counted against the day it happened in`() {
        val summary = build(listOf(cry(at(today, 9), 600)))
        assertEquals(10, summary.days.last().cryMinutes)
        assertEquals(1, summary.days.last().cryEpisodes)
        assertEquals(0, summary.days.first().cryMinutes)
    }

    /**
     * The failure this whole arrangement exists to prevent.
     *
     * The seeder used to write the *end* of a cry as its start time, so an hour
     * of crying that began at 23:30 was filed under the following morning. In a
     * timeline that is invisible. In a colic total it is a whole hour in the
     * wrong column of the count a parent hands to a doctor.
     */
    @Test
    fun `a cry across midnight is split between the two days`() {
        val yesterday = today.minusDays(1)
        // 23:40 to 00:20: twenty minutes each side.
        val summary = build(listOf(cry(at(yesterday, 23, 40), 40 * 60)))
        val days = summary.days.associateBy { it.date }
        assertEquals(20, days.getValue(yesterday).cryMinutes)
        assertEquals(20, days.getValue(today).cryMinutes)
        // The episode itself is counted once, on the day it began.
        assertEquals(1, days.getValue(yesterday).cryEpisodes)
        assertEquals(0, days.getValue(today).cryEpisodes)
    }

    @Test
    fun `the three-hour line is a count of minutes and nothing else`() {
        val summary = build(listOf(cry(at(today, 8), 179 * 60)))
        assertFalse(summary.days.last().overThreeHours)
        val heavier = build(listOf(cry(at(today, 8), 181 * 60)))
        assertTrue(heavier.days.last().overThreeHours)
    }

    // ------------------------------------------------------------- sleeping

    @Test
    fun `a start and an end make a block`() {
        val blocks = Insights.sleepBlocks(
            listOf(
                care(CareKind.SLEEP_START, at(today, 13)),
                care(CareKind.SLEEP_END, at(today, 14, 30)),
            ),
            now,
        )
        assertEquals(1, blocks.size)
        assertEquals(90, blocks.single().minutes)
    }

    /** Somebody logged a wake and forgot. One block must not swallow the day. */
    @Test
    fun `a second start closes the first block`() {
        val blocks = Insights.sleepBlocks(
            listOf(
                care(CareKind.SLEEP_START, at(today, 9)),
                care(CareKind.SLEEP_START, at(today, 13)),
                care(CareKind.SLEEP_END, at(today, 14)),
            ),
            now,
        )
        assertEquals(2, blocks.size)
        assertEquals(240, blocks.first().minutes)
        assertEquals(60, blocks.last().minutes)
    }

    @Test
    fun `a sleep nobody ended is capped rather than run on`() {
        // Opened eleven hours before now, never closed.
        val blocks = Insights.sleepBlocks(
            listOf(care(CareKind.SLEEP_START, at(today, 10))),
            now,
        )
        assertEquals(Insights.OPEN_SLEEP_CAP_MINUTES, blocks.single().minutes)
    }

    @Test
    fun `an end with nothing open is ignored`() {
        assertTrue(
            Insights.sleepBlocks(
                listOf(care(CareKind.SLEEP_END, at(today, 10))), now
            ).isEmpty()
        )
    }

    @Test
    fun `sleep across midnight lands on both days`() {
        val yesterday = today.minusDays(1)
        val summary = build(
            care = listOf(
                care(CareKind.SLEEP_START, at(yesterday, 23)),
                care(CareKind.SLEEP_END, at(today, 3)),
            )
        )
        val days = summary.days.associateBy { it.date }
        assertEquals(60, days.getValue(yesterday).sleepMinutes)
        assertEquals(180, days.getValue(today).sleepMinutes)
    }

    @Test
    fun `days with no sleep logged are not averaged in`() {
        val summary = build(
            care = listOf(
                care(CareKind.SLEEP_START, at(today, 13)),
                care(CareKind.SLEEP_END, at(today, 15)),
            )
        )
        assertEquals(1, summary.daysWithSleepLogged)
        // Two hours over the one day it was logged, not two hours over seven.
        assertEquals(120, summary.averageSleepMinutes)
    }

    // --------------------------------------------------------------- causes

    @Test
    fun `the episodes the model would not name are their own wedge`() {
        val summary = build(
            listOf(
                cry(at(today, 8), 120, "tired"),
                cry(at(today, 10), 120, "tired"),
                cry(at(today, 12), 120, "hungry"),
                cry(at(today, 14), 120, null),
            )
        )
        assertEquals(3, summary.causes.size)
        assertEquals("tired", summary.causes.first().key)
        assertEquals(2, summary.causes.first().episodes)
        val unnamed = summary.causes.single { it.key.isEmpty() }
        assertEquals(1, unnamed.episodes)
        assertEquals("Not guessed", unnamed.label)
    }

    @Test
    fun `causes are ranked by how often they were guessed`() {
        val summary = build(
            listOf(
                cry(at(today, 8), 60, "hungry"),
                cry(at(today, 9), 60, "belly_pain"),
                cry(at(today, 10), 60, "belly_pain"),
                cry(at(today, 11), 60, "belly_pain"),
            )
        )
        assertEquals("belly_pain", summary.causes.first().key)
        assertEquals("Belly pain", summary.causes.first().label)
    }

    // --------------------------------------------------------- hour profile

    @Test
    fun `a cry is spread across the hours it occupied`() {
        // 19:40 for an hour: twenty minutes of the 19:00 hour, forty of 20:00.
        val summary = build(listOf(cry(at(today, 19, 40), 60 * 60)))
        val perDay = Insights.DAYS.toFloat()
        assertEquals(20f / perDay, summary.hourlyCryMinutes[19], 0.01f)
        assertEquals(40f / perDay, summary.hourlyCryMinutes[20], 0.01f)
        assertEquals(0f, summary.hourlyCryMinutes[18], 0.01f)
    }

    @Test
    fun `no pattern is claimed from a flat week`() {
        // One cry an hour, right around the clock: nothing to name.
        val spread = (0 until 24).map { cry(at(today.minusDays(1), it), 300) }
        assertNull(build(spread).pattern)
    }

    @Test
    fun `an evening cluster is named with its share attached`() {
        val evening = (0 until 6).map { cry(at(today.minusDays(it.toLong()), 19), 30 * 60) }
        val pattern = build(evening).pattern
        assertNotNull(pattern)
        assertTrue(pattern!!, pattern.contains("19:00"))
    }

    @Test
    fun `too few episodes is not a pattern`() {
        assertNull(build(listOf(cry(at(today, 19), 1800))).pattern)
    }

    // -------------------------------------------------------- the stopwatch

    @Test
    fun `an unfinished sleep reports when it started`() {
        val started = at(today, 13)
        assertEquals(
            started,
            Insights.openSleepSince(listOf(care(CareKind.SLEEP_START, started)), now),
        )
    }

    @Test
    fun `a finished sleep reports nothing running`() {
        assertNull(
            Insights.openSleepSince(
                listOf(
                    care(CareKind.SLEEP_START, at(today, 13)),
                    care(CareKind.SLEEP_END, at(today, 14)),
                ),
                now,
            )
        )
    }

    /** The rows come back newest-first from the DAO; order must not decide this. */
    @Test
    fun `the latest mark wins whatever order the rows arrive in`() {
        val rows = listOf(
            care(CareKind.SLEEP_END, at(today, 14)),
            care(CareKind.SLEEP_START, at(today, 19)),
            care(CareKind.SLEEP_START, at(today, 13)),
        )
        assertEquals(at(today, 19), Insights.openSleepSince(rows, now))
        assertEquals(at(today, 19), Insights.openSleepSince(rows.reversed(), now))
    }

    @Test
    fun `feeds and nappies are not sleep marks`() {
        assertNull(
            Insights.openSleepSince(
                listOf(
                    care(CareKind.SLEEP_START, at(today, 13)),
                    care(CareKind.SLEEP_END, at(today, 14)),
                    care(CareKind.FEED, at(today, 15)),
                ),
                now,
            )
        )
    }

    @Test
    fun `a day counts the stretches that made up its sleep`() {
        val summary = build(
            care = listOf(
                care(CareKind.SLEEP_START, at(today, 9)),
                care(CareKind.SLEEP_END, at(today, 10)),
                care(CareKind.SLEEP_START, at(today, 13)),
                care(CareKind.SLEEP_END, at(today, 15)),
            )
        )
        assertEquals(2, summary.days.last().sleepSessions)
        assertEquals(180, summary.days.last().sleepMinutes)
    }

    // ------------------------------------------------------- the scorecard

    private fun episode(
        id: Long,
        atMs: Long,
        escalated: Boolean = false,
        settledBySound: Boolean = false,
        ended: Boolean = true,
    ): List<EventRecord> = buildList {
        add(cry(atMs, 60).copy(id = id, kind = "CRY_STARTED", episodeId = id))
        if (settledBySound) {
            add(cry(atMs, 60).copy(id = id * 100 + 1, kind = "SOOTHE_WORKED", episodeId = id))
        }
        if (escalated) {
            add(cry(atMs, 60).copy(id = id * 100 + 2, kind = "ESCALATED_TO_PARENT",
                                   episodeId = id))
        }
        if (ended) add(cry(atMs, 60).copy(id = id * 100 + 3, episodeId = id))
    }

    @Test
    fun `the ladder is scored by the highest rung each cry reached`() {
        val summary = build(
            episode(1, at(today, 8)) +
                episode(2, at(today, 10), settledBySound = true) +
                // Reached a sound *and* an escalation: the escalation wins,
                // because the ladder tried the sound and moved past it.
                episode(3, at(today, 12), settledBySound = true, escalated = true)
        )
        assertEquals(1, summary.outcomes.settledAlone)
        assertEquals(1, summary.outcomes.settledBySound)
        assertEquals(1, summary.outcomes.wokeYou)
        assertEquals(3, summary.outcomes.total)
        assertEquals(67, summary.outcomes.handledAlone)
    }

    @Test
    fun `a cry still running is not scored`() {
        val summary = build(episode(1, at(today, 8), ended = false))
        assertEquals(0, summary.outcomes.total)
    }

    @Test
    fun `rows with no episode are left out of the scorecard`() {
        val summary = build(listOf(cry(at(today, 8), 60)))
        assertEquals(0, summary.outcomes.total)
    }

    @Test
    fun `an empty log produces an empty week rather than nothing`() {
        val summary = build()
        assertTrue(summary.loaded)
        assertEquals(7, summary.days.size)
        assertTrue(summary.days.all { it.cryMinutes == 0 && it.sleepMinutes == 0 })
        assertFalse(summary.hasCauses)
        assertNull(summary.averageSleepMinutes)
    }
}
