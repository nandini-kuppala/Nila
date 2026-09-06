package com.nila

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.actions.SoothePlayer
import com.nila.actions.Soother
import com.nila.actions.VoiceRecorder
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The caregiver's own voice, from recording to playback.
 *
 * This path existed only half-built: the monitor already looked in
 * `files/recordings` for a soother to play, and nothing in the app could put
 * one there -- so every episode silently fell through to white noise. The test
 * covers the join.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSootherTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        VoiceRecorder.existing(context)
            .filter { it.name.startsWith("Test") }
            .forEach { it.delete() }
    }

    @Test
    fun recordsAndThenPlaysBack() {
        val recorder = VoiceRecorder(context)
        assertTrue("recorder would not start", recorder.start("Test lullaby"))
        Thread.sleep(1_800)
        val file = recorder.stop()

        assertTrue("nothing was written", file != null && file.length() > 2_000)

        val player = SoothePlayer(context)
        try {
            assertTrue(
                "the recording would not play back",
                player.play(Soother.Recorded("test", "Test lullaby", file!!), loop = false),
            )
        } finally {
            player.stop()
        }
    }

    /**
     * A recording stopped almost immediately leaves a valid but empty container
     * that MediaPlayer chokes on later. It has to be discarded at the source.
     */
    @Test
    fun discardsARecordingTooShortToBeUsable() {
        val recorder = VoiceRecorder(context)
        assertTrue(recorder.start("Test blip"))
        val file = recorder.stop()
        assertTrue("an unusable stub was kept", file == null || file.length() > 2_000)
    }

    @Test
    fun builtInSoothersAllPlay() {
        val player = SoothePlayer(context)
        try {
            Soother.builtIns.forEach {
                assertTrue("${it.id} would not play", player.play(it, loop = false))
                player.stop()
            }
        } finally {
            player.stop()
        }
    }
}
