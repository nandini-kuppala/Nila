package com.nila.ui

import com.nila.data.EventRecord
import com.nila.data.Severity
import com.nila.ui.screens.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That one cry is one entry, and that no cry is quietly merged into another.
 *
 * The folding is the only thing standing between a parent and a timeline where
 * a single difficult evening pushes two days off the bottom of the screen. Both
 * of its failure modes are invisible in a screenshot: merging two cries looks
 * like one long cry, and splitting one looks like a restless baby. Neither can
 * be caught by eye, so both are caught here.
 */
class TimelineTest {

    private var nextId = 1L

    private fun row(
        kind: String,
        episodeId: Long?,
        atMs: Long = 1_000L,
        seconds: Int = 0,
        severity: Severity = Severity.NOTE,
        note: String? = null,
        cause: String? = null,
        clip: String? = null,
    ) = EventRecord(
        id = nextId++,
        kind = kind,
        severityLevel = severity.level,
        startedAtMs = atMs,
        durationSeconds = seconds,
        hypothesisLabel = cause,
        note = note,
        episodeId = episodeId,
        clipPath = clip,
    )

    /** A whole cry as the service writes it: six rows, one episode. */
    private fun episode(
        id: Long,
        atMs: Long,
        seconds: Int = 120,
        escalated: Boolean = false,
        settled: Boolean = true,
        cause: String? = "tired",
        clip: String? = null,
    ): List<EventRecord> = buildList {
        add(row("CRY_STARTED", id, atMs, 0, clip = clip))
        add(row("SOOTHE_PLAYED", id, atMs, 20, note = "White noise"))
        add(
            row(
                if (settled) "SOOTHE_WORKED" else "SOOTHE_FAILED",
                id, atMs, 55, note = "White noise",
            )
        )
        if (escalated) {
            add(row("ESCALATED_TO_PARENT", id, atMs, 90, Severity.URGENT, cause = cause))
        }
        add(row("CRY_ENDED", id, atMs, seconds, Severity.ATTENTION, cause = cause))
    }

    @Test
    fun `a cry is one entry, whatever it wrote`() {
        val quiet = Timeline.collapse(episode(1, 10_000))
        assertEquals(1, quiet.size)
        assertTrue(quiet.single().isCry)
        // Heard, played, judged, ended -- kept underneath, not listed beside.
        assertEquals(4, quiet.single().steps.size)

        val hard = Timeline.collapse(episode(2, 10_000, escalated = true))
        assertEquals(1, hard.size)
        assertEquals(5, hard.single().steps.size)
    }

    @Test
    fun `two cries stay two entries`() {
        val entries = Timeline.collapse(episode(1, 10_000) + episode(2, 20_000))
        assertEquals(2, entries.filter { it.isCry }.size)
    }

    /**
     * The reason the episode id exists.
     *
     * Every row of an episode recomputes its own start from a duration rounded
     * to whole seconds, so rows of one cry carry timestamps a second apart --
     * and on a bad evening two cries can start five minutes apart. Grouping on
     * time would be a guess that fails in exactly those two cases.
     */
    @Test
    fun `episodes are grouped by id, not by timestamp`() {
        val a = episode(1, 10_000)
        val b = episode(2, 10_000) // same instant, different episode
        val entries = Timeline.collapse(a + b).filter { it.isCry }
        assertEquals(2, entries.size)
    }

    @Test
    fun `newest first`() {
        val entries = Timeline.collapse(episode(1, 10_000) + episode(2, 90_000))
        assertEquals(90_000L, entries.first().atMs)
    }

    @Test
    fun `an escalation is named in the title and raises the severity`() {
        val entry = Timeline.collapse(episode(1, 10_000, escalated = true)).single()
        assertTrue(entry.title.contains("woken"))
        assertEquals(Severity.URGENT, entry.severity)
    }

    @Test
    fun `a cry that settled says what settled it`() {
        val entry = Timeline.collapse(episode(1, 10_000, settled = true)).single()
        assertTrue(entry.summary!!, entry.summary!!.contains("White noise settled her"))
    }

    @Test
    fun `the cause is shown with its confidence and its hedge`() {
        val rows = episode(1, 10_000, cause = "belly_pain")
        val entry = Timeline.collapse(rows).single()
        assertTrue(entry.summary!!, entry.summary!!.contains("belly pain"))
        assertTrue(entry.summary!!, entry.summary!!.contains("a guess from the sound"))
    }

    @Test
    fun `a kept recording surfaces on the entry`() {
        val entry = Timeline.collapse(
            episode(1, 10_000, escalated = true, clip = "/data/cries/kept/cry-1.wav")
        ).single()
        assertEquals("/data/cries/kept/cry-1.wav", entry.clipPath)
    }

    @Test
    fun `an ordinary cry carries no recording`() {
        assertNull(Timeline.collapse(episode(1, 10_000)).single().clipPath)
    }

    /** Vision events are already one thing each and must not be folded. */
    @Test
    fun `safety events stay their own entries`() {
        val entries = Timeline.collapse(
            episode(1, 10_000) + listOf(
                row("ROLLED_TO_FRONT", null, 30_000, severity = Severity.URGENT),
                row("FACE_RETURNED", null, 40_000),
            )
        )
        assertEquals(3, entries.size)
        assertEquals(2, entries.count { !it.isCry })
    }

    /**
     * Rows written before the column existed, or by a service that died
     * mid-episode, are real events. Showing them alone is honest; attaching
     * them to the nearest cry is a fabricated association.
     */
    @Test
    fun `a cry row with no episode id stands alone`() {
        val entries = Timeline.collapse(listOf(row("CRY_ENDED", null, 10_000, 60)))
        assertEquals(1, entries.size)
        assertFalse(entries.single().isCry)
        assertEquals("The crying stopped", entries.single().title)
    }

    @Test
    fun `an empty log folds to nothing`() {
        assertTrue(Timeline.collapse(emptyList()).isEmpty())
    }
}
