package com.nila.actions

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * The sounds the phone can offer before it wakes anybody.
 *
 * BUILT_IN sounds ship as assets. RECORDED is the caregiver's own voice, taped
 * once in the app -- which is the one that actually works, and the reason this
 * feature exists at all.
 */
sealed class Soother(val id: String, val displayName: String) {
    class BuiltIn(id: String, displayName: String, val asset: String) :
        Soother(id, displayName)

    class Recorded(id: String, displayName: String, val file: File) :
        Soother(id, displayName)

    companion object {
        val WHITE_NOISE = BuiltIn("white_noise", "White noise", "soothe_white_noise.wav")
        val SHUSH = BuiltIn("shush", "Shushing", "soothe_shush.wav")
        val HEARTBEAT = BuiltIn("heartbeat", "Heartbeat", "soothe_heartbeat.wav")
        val builtIns = listOf(WHITE_NOISE, SHUSH, HEARTBEAT)
    }
}

/**
 * Plays a soothing sound on the device's own speaker.
 *
 * Volume is capped. This plays a metre from an infant's ear, and the whole
 * point is to settle a baby rather than startle one -- so the player never
 * takes the stream above [MAX_VOLUME_FRACTION] of system volume regardless of
 * where the user left the slider.
 */
class SoothePlayer(private val context: Context) {

    companion object {
        private const val TAG = "SoothePlayer"
        private const val MAX_VOLUME_FRACTION = 0.55f
        const val DEFAULT_DURATION_SECONDS = 45

        /** The demo cry, loud enough to be the thing you notice in the room. */
        private const val CRY_VOLUME = 0.85f

        /** And ducked under a soother, so the soother is what you hear next. */
        private const val CRY_DUCKED_VOLUME = 0.22f
    }

    private var player: MediaPlayer? = null
    private var cryPlayer: MediaPlayer? = null
    @Volatile var nowPlaying: Soother? = null; private set

    fun play(soother: Soother, loop: Boolean = true): Boolean {
        stop()
        return try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                when (soother) {
                    is Soother.BuiltIn ->
                        context.assets.openFd(soother.asset).use {
                            setDataSource(it.fileDescriptor, it.startOffset, it.length)
                        }
                    is Soother.Recorded -> setDataSource(soother.file.absolutePath)
                }
                isLooping = loop
                setVolume(MAX_VOLUME_FRACTION, MAX_VOLUME_FRACTION)
                prepare()
                start()
            }
            player = mp
            nowPlaying = soother
            Log.i(TAG, "playing ${soother.id}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not play ${soother.id}", t)
            nowPlaying = null
            false
        }
    }

    /**
     * Play the demo cry itself, on loop, until [stopCry].
     *
     * The simulation feeds the recording to the detector but never to the
     * speaker, so a demo of a cry was silent -- which left the soothing sounds
     * with nothing to be soothing, and the verification rung with nothing
     * audible to have judged. This is the demo path only; the live monitor
     * never plays what it hears.
     */
    fun playCryLoop(asset: String): Boolean {
        stopCry()
        return try {
            cryPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                context.assets.openFd(asset).use {
                    setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                isLooping = true
                setVolume(CRY_VOLUME, CRY_VOLUME)
                prepare()
                start()
            }
            Log.i(TAG, "playing the demo cry")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not play the demo cry", t)
            cryPlayer = null
            false
        }
    }

    /** Drop the cry under a soothing sound, or bring it back up. */
    fun duckCry(ducked: Boolean) {
        val v = if (ducked) CRY_DUCKED_VOLUME else CRY_VOLUME
        cryPlayer?.runCatching { setVolume(v, v) }
    }

    fun stopCry() {
        cryPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        cryPlayer = null
    }

    fun stop() {
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
        nowPlaying = null
    }

    val isPlaying: Boolean get() = player?.isPlaying == true

    /** True when something else already owns audio focus -- a call, or music. */
    fun audioBusy(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.isMusicActive || am.mode != AudioManager.MODE_NORMAL
    }
}
