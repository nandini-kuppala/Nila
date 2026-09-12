package com.nila.audio

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * Writes the cry that woke the app to a file the parent can play back.
 *
 * ### Why this exists at all, given the alternative
 *
 * Nila used to record nothing: monitored audio was analysed in memory and
 * dropped. That is the stronger privacy position and it was the wrong one for
 * the person using it. The summary said *probably tired, we played white noise,
 * it did not help, we woke you* -- and offered no way to check any of it. A
 * parent who cannot hear what the app heard has to take the whole chain on
 * trust, including a cause estimate the app itself labels unreliable.
 *
 * ### What is given up, and what is not
 *
 * The clip is the *episode*, not the night. Recording starts when the detector
 * declares a cry and stops [CAP_SECONDS] later, so silence, conversation and
 * everything else in the room are never written anywhere -- the same property
 * the old design had, for every second except the ones the parent asked about.
 *
 * Files live in [Context.getFilesDir], which is app-private: no other app can
 * read them, `adb pull` cannot reach them on a non-rooted phone, and
 * `allowBackup=false` in the manifest keeps them off any cloud transport. They
 * are deleted after [RETAIN_DAYS] days without anyone having to remember.
 *
 * Nothing about this adds a network path. There is still no code in this app
 * that uploads audio.
 */
class EpisodeRecorder(context: Context) {

    companion object {
        private const val TAG = "EpisodeRecorder"

        /**
         * How much of an episode is kept.
         *
         * Ninety seconds is not a storage decision, it is the span in which
         * the interesting things happen: the cause is read at twenty seconds,
         * two soothers are tried and judged, and the parent is woken at ninety.
         * A clip that covers exactly that is a clip where every rung of the
         * ladder can be heard. Past it the audio is a baby still crying with a
         * parent already in the room.
         */
        const val CAP_SECONDS = 90

        /** How long an ordinary cry's clip stays on the phone. */
        const val RETAIN_DAYS = 7

        /**
         * How long a *flagged* cry's clip stays.
         *
         * A cry that woke somebody, ran to the three-minute cap, or came back
         * as pain is the one a parent may want to play to a doctor, and the
         * gap between noticing a pattern and getting an appointment is not
         * seven days. Thirty covers a referral without turning the phone into
         * an archive of a nursery.
         */
        const val KEEP_DAYS = 30

        private const val DIR = "cries"

        /** Flagged clips, on their own retention window. */
        private const val KEPT_DIR = "cries/kept"
        private const val BYTES_PER_SAMPLE = 2
        private const val HEADER_BYTES = 44

        private val CAP_SAMPLES = CAP_SECONDS * LogMelFrontend.SAMPLE_RATE

        /** Clips for the timeline and the summary card, newest first. */
        fun existing(context: Context): List<File> =
            (File(context.filesDir, DIR).listFiles()?.toList().orEmpty() +
                File(context.filesDir, KEPT_DIR).listFiles()?.toList().orEmpty())
                .filter { it.isFile && it.extension == "wav" && it.length() > HEADER_BYTES }
                .sortedByDescending { it.lastModified() }

        /** Where flagged clips live. Exposed so the store can be emptied. */
        fun keptDir(context: Context): File =
            File(context.filesDir, KEPT_DIR).apply { mkdirs() }

        /** Total bytes of audio this app is holding, for the settings screen. */
        fun bytesHeld(context: Context): Long =
            existing(context).sumOf { it.length() }

        /** Delete every clip, kept ones included. Offered in Settings. */
        fun deleteAll(context: Context): Int =
            existing(context).count { runCatching { it.delete() }.getOrDefault(false) }
    }

    private val dir = File(context.filesDir, DIR).apply { mkdirs() }
    private val kept = File(context.filesDir, KEPT_DIR).apply { mkdirs() }

    private var stream: BufferedOutputStream? = null
    private var file: File? = null
    private var samplesWritten = 0
    private var firstWindow = true

    /** True while audio is still being taken. Goes false at the cap. */
    val recording: Boolean get() = stream != null

    /** Seconds of audio captured so far. */
    val seconds: Float
        get() = samplesWritten.toFloat() / LogMelFrontend.SAMPLE_RATE

    /**
     * Begin a clip for an episode that has just been declared.
     *
     * Purges expired clips on the way in rather than on a timer: this runs once
     * per episode, which is exactly as often as the directory changes.
     */
    fun start(): File? {
        finish()
        purge()
        return try {
            val target = File(dir, "cry-${System.currentTimeMillis()}.wav")
            val out = BufferedOutputStream(target.outputStream())
            // Placeholder header. The two length fields are unknown until the
            // episode ends, and are patched in by finish().
            out.write(header(0))
            stream = out
            file = target
            samplesWritten = 0
            firstWindow = true
            target
        } catch (t: Throwable) {
            Log.w(TAG, "could not open a clip for this episode", t)
            null
        }
    }

    /**
     * Append one analysis window.
     *
     * Consecutive windows overlap by half -- deliberately, so the detector
     * never splits a cry onset across two patches -- so only the newest hop is
     * written or the clip would stutter with every sample duplicated. The first
     * window is the exception: it is written whole, because its older half is
     * the moment the cry started and that is the part worth hearing.
     */
    fun append(window: AudioCapture.Window) {
        val out = stream ?: return
        val samples = window.samples
        val from = if (firstWindow) 0 else
            (samples.size - AudioCapture.HOP_SAMPLES).coerceAtLeast(0)
        firstWindow = false

        val room = CAP_SAMPLES - samplesWritten
        if (room <= 0) { closeStream(); return }
        val count = minOf(samples.size - from, room)

        try {
            val bytes = ByteArray(count * BYTES_PER_SAMPLE)
            for (i in 0 until count) {
                // Float [-1,1] to signed 16-bit little-endian, clamped rather
                // than wrapped: a clipped sample should sound loud, not invert.
                val v = (samples[from + i].coerceIn(-1f, 1f) * 32767f)
                    .roundToInt().coerceIn(-32768, 32767)
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
            samplesWritten += count
            if (samplesWritten >= CAP_SAMPLES) closeStream()
        } catch (t: Throwable) {
            Log.w(TAG, "clip write failed; dropping the recording", t)
            abandon()
        }
    }

    /**
     * Close the clip and patch its header.
     *
     * @return the finished file, or null if nothing usable was captured. A clip
     * shorter than a second is discarded: it is a false start, and an entry in
     * the summary offering to play a third of a second of nothing is worse than
     * no entry.
     */
    fun finish(): File? {
        closeStream()
        val target = file ?: return null
        file = null
        if (samplesWritten < LogMelFrontend.SAMPLE_RATE) {
            target.delete()
            return null
        }
        return try {
            RandomAccessFile(target, "rw").use { raf ->
                raf.write(header(samplesWritten * BYTES_PER_SAMPLE))
            }
            target
        } catch (t: Throwable) {
            Log.w(TAG, "could not finalise ${target.name}", t)
            target.delete()
            null
        }
    }

    /**
     * Promote a finished clip to the flagged store.
     *
     * A move rather than a copy, and a rename inside the same directory tree so
     * it cannot fail for space. If the rename fails the clip stays where it is
     * -- still playable, just on the shorter window -- because losing the audio
     * would be a worse outcome than keeping it for seven days instead of thirty.
     *
     * @return where the clip ended up.
     */
    fun keep(clip: File): File {
        val target = File(kept, clip.name)
        return if (runCatching { clip.renameTo(target) }.getOrDefault(false)) target else clip
    }

    /** Drop the clip in progress without keeping it. */
    fun abandon() {
        closeStream()
        file?.delete()
        file = null
        samplesWritten = 0
    }

    private fun closeStream() {
        runCatching { stream?.flush(); stream?.close() }
        stream = null
    }

    /**
     * Delete clips past their retention window -- two windows, two directories.
     *
     * The flagged store is walked with the longer cutoff rather than being
     * skipped: "kept" means kept for a reason and for a while, not kept
     * forever. An app that quietly accumulates every difficult night a baby
     * ever had is not the promise this feature makes.
     */
    fun purge(nowMs: Long = System.currentTimeMillis()) {
        val day = 24L * 60L * 60L * 1000L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < nowMs - RETAIN_DAYS * day) {
                runCatching { f.delete() }
            }
        }
        kept.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < nowMs - KEEP_DAYS * day) {
                runCatching { f.delete() }
            }
        }
    }

    /** Canonical 44-byte PCM WAV header for [dataBytes] of 16 kHz mono PCM16. */
    private fun header(dataBytes: Int): ByteArray {
        val rate = LogMelFrontend.SAMPLE_RATE
        val byteRate = rate * BYTES_PER_SAMPLE
        val out = ByteArray(HEADER_BYTES)
        var p = 0
        fun ascii(s: String) { s.forEach { out[p++] = it.code.toByte() } }
        fun int32(v: Int) {
            out[p++] = (v and 0xFF).toByte()
            out[p++] = ((v shr 8) and 0xFF).toByte()
            out[p++] = ((v shr 16) and 0xFF).toByte()
            out[p++] = ((v shr 24) and 0xFF).toByte()
        }
        fun int16(v: Int) {
            out[p++] = (v and 0xFF).toByte()
            out[p++] = ((v shr 8) and 0xFF).toByte()
        }
        ascii("RIFF"); int32(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); int32(16)
        int16(1)                        // PCM, uncompressed
        int16(1)                        // mono
        int32(rate)
        int32(byteRate)
        int16(BYTES_PER_SAMPLE)         // block align
        int16(16)                       // bits per sample
        ascii("data"); int32(dataBytes)
        return out
    }
}
