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
    }

    private var player: MediaPlayer? = null
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
