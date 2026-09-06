package com.nila.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.concurrent.thread

/**
 * Continuous 16 kHz mono capture with a sliding analysis window.
 *
 * Emits one [Window] every HOP_SAMPLES (480 ms) covering the last 0.96 s, so
 * consecutive windows overlap by half. The overlap matters: a cry that starts
 * mid-window would otherwise be split across two patches and score weakly in
 * both, which is exactly the onset the detector most needs to catch.
 *
 * Capture runs on its own thread at [MediaRecorder.AudioSource.UNPROCESSED]
 * where available. The usual VOICE_RECOGNITION source applies noise
 * suppression and AGC tuned for speech, and both actively damage what we are
 * trying to measure -- AGC in particular flattens the loudness envelope that
 * the escalation logic reads.
 */
class AudioCapture(
    private val onOverrun: (Int) -> Unit = {},
    private val onFault: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = LogMelFrontend.SAMPLE_RATE
        const val WINDOW_SAMPLES = LogMelFrontend.PATCH_SAMPLES     // 15_600 ~ 0.96 s
        const val HOP_SAMPLES = SAMPLE_RATE * 48 / 100              // 7_680 ~ 0.48 s

        /**
         * A healthy AudioRecord.read blocks until it has samples. If it starts
         * returning zero immediately -- the microphone was taken by another
         * app, revoked, or never existed on this device -- an unguarded loop
         * spins a core flat out and looks, from the outside, exactly like
         * normal operation. Back off, then give up and say so.
         */
        private const val STARVED_BACKOFF_MS = 20L
        private const val STARVED_READS_BEFORE_FAULT = 150      // ~3 s of nothing
    }

    /** One analysis window. [samples] is freshly allocated and safe to retain. */
    class Window(
        val samples: FloatArray,
        val timestampMs: Long,
        val dbfs: Float,
    )

    private val _windows = MutableSharedFlow<Window>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val windows: SharedFlow<Window> = _windows

    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile var overruns = 0; private set

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuf > 0) { "AudioRecord.getMinBufferSize failed ($minBuf)" }

        // Four windows of headroom. Enough that a GC pause or a busy moment in
        // the inference thread does not cost us audio.
        val bufBytes = maxOf(minBuf, WINDOW_SAMPLES * 2 * 4)

        val rec = createRecorder(bufBytes)
        check(rec.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialise"
        }

        recorder = rec
        running = true
        rec.startRecording()

        worker = thread(name = "nila-audio", priority = Thread.MAX_PRIORITY) {
            captureLoop(rec)
        }
        Log.i(TAG, "capture started (buffer ${bufBytes}B, source ${rec.audioSource})")
    }

    private fun createRecorder(bufBytes: Int): AudioRecord {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            runCatching {
                val rec = AudioRecord(
                    source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufBytes,
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
                rec.release()
            }
        }
        error("no usable audio source")
    }

    private fun captureLoop(rec: AudioRecord) {
        val ring = FloatArray(WINDOW_SAMPLES)
        val pcm = ShortArray(HOP_SAMPLES)
        var filled = 0

        var starvedReads = 0

        while (running) {
            var got = 0
            var readFailed = false
            while (got < pcm.size && running) {
                val n = rec.read(pcm, got, pcm.size - got)
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read -> $n")
                    readFailed = true
                    break
                }
                if (n == 0) break
                got += n
            }

            if (!running) break

            if (got <= 0 || readFailed) {
                starvedReads++
                if (starvedReads >= STARVED_READS_BEFORE_FAULT) {
                    running = false
                    onFault(
                        "The microphone stopped producing audio. Another app may " +
                            "have taken it."
                    )
                    break
                }
                // Yield rather than spin. Without this the thread burns a core
                // and nothing downstream ever notices.
                try {
                    Thread.sleep(STARVED_BACKOFF_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }
            starvedReads = 0

            // Slide the ring left by `got` and append the new samples, converting
            // PCM16 to the [-1, 1] float range the frontend expects.
            val keep = WINDOW_SAMPLES - got
            if (keep > 0) System.arraycopy(ring, got, ring, 0, keep)
            for (i in 0 until got) {
                ring[keep + i] = pcm[i] / 32768.0f
            }
            filled = minOf(WINDOW_SAMPLES, filled + got)
            if (filled < WINDOW_SAMPLES) continue

            // A fresh copy per window rather than a reused buffer. Consumers run
            // asynchronously, so handing them a buffer we are about to overwrite
            // is a data race that would show up as occasional nonsense features.
            // ~125 KB/s of short-lived allocation is a cheap price for that.
            val emit = ring.copyOf()
            val window = Window(
                samples = emit,
                timestampMs = System.currentTimeMillis(),
                dbfs = LogMelFrontend.dbfs(emit, 0, WINDOW_SAMPLES),
            )
            if (!_windows.tryEmit(window)) {
                overruns++
                onOverrun(overruns)
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(1_000)
        worker = null
        recorder?.let { rec ->
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                rec.release()
            }
        }
        recorder = null
        Log.i(TAG, "capture stopped ($overruns overruns)")
    }

    val isRunning: Boolean get() = running
}
