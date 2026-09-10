package com.nila.monitor

import com.nila.data.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder as the parent sees it.
 *
 * These pin the two properties that make the chart worth drawing: rungs that
 * have not happened yet are shown as *coming* rather than as missing, and the
 * one currently running is distinguishable from the ones that are done. Get
 * either wrong and the chart reads as a broken app at the exact moment somebody
 * is deciding whether to trust it.
 */
class EpisodePipelineTest {

    private fun fired(vararg pairs: Pair<EpisodePipeline.Stage, Int>) =
        pairs.associate { (stage, at) ->
            stage to EpisodePipeline.Fired("did the thing", at)
        }

    @Test
    fun `every rung is present from the first second`() {
        val steps = EpisodePipeline.steps(seconds = 1, fired = emptyMap())
        assertEquals(EpisodePipeline.Stage.entries.size, steps.size)
    }

    @Test
    fun `rungs stay in ladder order`() {
        val steps = EpisodePipeline.steps(seconds = 95, fired = emptyMap())
        assertEquals(EpisodePipeline.Stage.entries.toList(), steps.map { it.stage })
    }

    @Test
    fun `a rung that has not fired and is not due yet is pending`() {
        val steps = EpisodePipeline.steps(seconds = 5, fired = emptyMap())
        val verdict = steps.first { it.stage == EpisodePipeline.Stage.VERDICT }
        assertEquals(EpisodePipeline.Status.PENDING, verdict.status)
        // And it says what it *will* do, so the row is not blank.
        assertTrue(verdict.body.isNotBlank())
        assertNull(verdict.detail)
    }

    @Test
    fun `the newest fired rung is the active one`() {
        val steps = EpisodePipeline.steps(
            seconds = 22,
            fired = fired(
                EpisodePipeline.Stage.HEARD to 0,
                EpisodePipeline.Stage.LOGGED to 12,
                EpisodePipeline.Stage.CLASSIFIED to 20,
            ),
        )
        assertEquals(
            EpisodePipeline.Status.ACTIVE,
            steps.first { it.stage == EpisodePipeline.Stage.CLASSIFIED }.status,
        )
        assertEquals(
            EpisodePipeline.Status.DONE,
            steps.first { it.stage == EpisodePipeline.Stage.LOGGED }.status,
        )
    }

    /**
     * A cry that settles after one sound never reaches the verdict rung. That
     * rung has to stop looking imminent, or the summary of a cry that resolved
     * on its own shows a pending alert.
     */
    @Test
    fun `a rung overtaken by the episode is skipped, not left pending`() {
        val steps = EpisodePipeline.steps(
            seconds = 120,
            fired = fired(EpisodePipeline.Stage.HEARD to 0),
        )
        val soothed = steps.first { it.stage == EpisodePipeline.Stage.SOOTHED }
        assertEquals(EpisodePipeline.Status.SKIPPED, soothed.status)
    }

    @Test
    fun `closing marks nothing as still active`() {
        val steps = EpisodePipeline.steps(
            seconds = 180,
            fired = fired(
                EpisodePipeline.Stage.HEARD to 0,
                EpisodePipeline.Stage.CLOSED to 180,
            ),
            closed = true,
        )
        assertTrue(
            "a closed episode has no running rung",
            steps.none { it.status == EpisodePipeline.Status.ACTIVE },
        )
    }

    @Test
    fun `a fired rung reports the second it actually fired`() {
        val steps = EpisodePipeline.steps(
            seconds = 30,
            fired = mapOf(
                EpisodePipeline.Stage.SOOTHED to
                    EpisodePipeline.Fired("Attempt 1: White noise", 21, Severity.NOTE)
            ),
        )
        val soothed = steps.first { it.stage == EpisodePipeline.Stage.SOOTHED }
        assertEquals(21, soothed.firedAtSeconds)
        assertNotNull(soothed.detail)
        assertEquals("Attempt 1: White noise", soothed.body)
    }

    @Test
    fun `severity rides along so the chart can colour a rung`() {
        val steps = EpisodePipeline.steps(
            seconds = 95,
            fired = mapOf(
                EpisodePipeline.Stage.VERDICT to
                    EpisodePipeline.Fired("woke you", 90, Severity.URGENT)
            ),
        )
        assertEquals(
            Severity.URGENT,
            steps.first { it.stage == EpisodePipeline.Stage.VERDICT }.severity,
        )
    }

    /**
     * The rung times are read off the escalation config rather than repeated,
     * so retuning the ladder cannot leave the chart describing the old one.
     */
    @Test
    fun `rung times match the escalation ladder`() {
        val config = Escalation.Config()
        assertEquals(config.noteSeconds, EpisodePipeline.Stage.LOGGED.atSeconds)
        assertEquals(config.reasonSeconds, EpisodePipeline.Stage.CLASSIFIED.atSeconds)
        assertEquals(config.sootheAfterSeconds, EpisodePipeline.Stage.SOOTHED.atSeconds)
        assertEquals(config.verdictSeconds, EpisodePipeline.Stage.VERDICT.atSeconds)
        assertEquals(config.closeSeconds, EpisodePipeline.Stage.CLOSED.atSeconds)
    }

    @Test
    fun `progress runs zero to one across the episode`() {
        assertEquals(0f, EpisodePipeline.progress(0), 1e-4f)
        assertEquals(0.5f, EpisodePipeline.progress(90), 1e-4f)
        assertEquals(1f, EpisodePipeline.progress(180), 1e-4f)
        assertEquals("clamped past the close", 1f, EpisodePipeline.progress(400), 1e-4f)
    }
}
