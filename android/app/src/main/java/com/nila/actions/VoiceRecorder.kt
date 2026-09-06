package com.nila.actions

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Records the caregiver's own voice, to be played back at 3am.
 *
 * This is the soother that actually works. White noise settles some babies some
 * of the time; the voice they have heard since before they were born settles
 * more of them, more often -- and [SootheMemory] measures which, per baby, so
 * the claim does not have to be taken on faith.
 *
 * The service that plays these already looked for them in `files/recordings`.
 * Until this existed there was no way to put one there, so every episode fell
 * through to the built-in sounds.
 *
 * Recordings never leave the phone: they are written to app-private storage,
 * backup is disabled at the manifest level, and nothing in the app uploads.
 */
class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"

        /** Long enough for a lullaby verse; short enough not to fill a phone. */
        const val MAX_SECONDS = 120

        fun directory(context: Context): File =
            File(context.filesDir, "recordings").apply { mkdirs() }

        fun existing(context: Context): List<File> =
            directory(context).listFiles()
                ?.filter { it.extension == "m4a" || it.extension == "wav" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
    }

    private var recorder: MediaRecorder? = null
    private var target: File? = null

    val isRecording: Boolean get() = recorder != null

    /**
     * @param label what the parent will see in the list, e.g. "Amma humming"
     */
    fun start(label: String): Boolean {
        if (isRecording) return false
        val file = File(directory(context), "${sanitise(label)}.m4a")

        return try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // 44.1 kHz mono. The detector runs at 16 kHz, but this file is
                // for a baby's ears rather than the model's, and a voice
                // downsampled to 16 kHz sounds noticeably thin through a phone
                // speaker.
                setAudioSamplingRate(44_100)
                setAudioChannels(1)
                setAudioEncodingBitRate(96_000)
                setMaxDuration(MAX_SECONDS * 1000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            target = file
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not start recording", t)
            runCatching { file.delete() }
            recorder = null
            target = null
            false
        }
    }

    /** @return the finished file, or null if it was too short to be usable. */
    fun stop(): File? {
        val rec = recorder ?: return null
        val file = target
        recorder = null
        target = null

        return try {
            rec.stop()
            rec.release()
            // MediaRecorder writes a valid but empty MPEG-4 container if it is
            // stopped almost immediately, and MediaPlayer then fails on it at
            // the worst possible moment. Discard it here instead.
            if (file != null && file.length() > 2_000) file else {
                file?.delete()
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recording failed", t)
            runCatching { rec.release() }
            file?.delete()
            null
        }
    }

    fun cancel() {
        val file = target
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        target = null
        file?.delete()
    }

    private fun sanitise(label: String): String =
        label.trim().ifBlank { "Recording" }
            .replace(Regex("[^A-Za-z0-9 ]"), "")
            .replace(' ', '-')
            .take(40)
}
