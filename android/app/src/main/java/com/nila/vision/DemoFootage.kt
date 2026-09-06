package com.nila.vision

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.Closeable

/**
 * A recording of a real baby, played through the safety watch.
 *
 * The audio side already has this -- [com.nila.monitor.MonitorService.simulate]
 * replays a real cry through the real detector -- and the camera side needed it
 * for the same two reasons. An emulator has no nursery, so its camera shows a
 * rendered room with a cat in it, and a face detector pointed at that reports
 * exactly what it should: no face. And nobody demonstrating a baby monitor can
 * produce a baby on cue.
 *
 * Nothing about the pipeline is bypassed. Frames go through the same downscale,
 * the same BlazeFace detector, the same motion-energy comparison and the same
 * escalation rules the camera feeds. Only the source of the pixels differs, and
 * the screen says so for as long as it runs.
 */
class DemoFootage(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "DemoFootage"
        private const val ASSET = "demo_crawl.mp4"

        /**
         * Matches CameraWatch's analysis rate, so the demo exercises the
         * pipeline at the cadence the camera actually drives it at rather than
         * at whatever rate frames can be decoded.
         */
        const val FRAME_INTERVAL_MS = 200L

        /** Same width the camera path downscales to before analysis. */
        private const val ANALYSIS_WIDTH = 480
    }

    private var retriever: MediaMetadataRetriever? = null

    /** Clip length in milliseconds, or 0 if it could not be read. */
    var durationMs: Long = 0L
        private set

    /**
     * The rows of the source frame that are not letterbox.
     *
     * The clip is a landscape recording in a portrait container, so roughly
     * half of every frame is black bar. Cropping it matters twice over: the
     * face detector stops spending half its input on black, and the frame
     * shown on screen fills the space instead of floating in a letterbox
     * inside a letterbox.
     */
    private var contentTop = 0
    private var contentBottom = 0

    fun open(): Boolean {
        if (retriever != null) return true
        return try {
            val r = MediaMetadataRetriever()
            context.assets.openFd(ASSET).use {
                r.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            durationMs = r.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever = r
            measureLetterbox(r)
            Log.i(TAG, "opened $ASSET, ${durationMs}ms, " +
                "content rows $contentTop..$contentBottom")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not open $ASSET", t)
            null.also { retriever = null }
            false
        }
    }

    /**
     * Find the black bars once, from a frame in the middle of the clip.
     *
     * Sampled every eighth row and every sixteenth column: this runs once and
     * only needs to find an edge, not measure it precisely.
     */
    private fun measureLetterbox(r: MediaMetadataRetriever) {
        val frame = r.getFrameAtTime(
            durationMs * 500L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        ) ?: return
        try {
            contentTop = 0
            contentBottom = frame.height
            val row = IntArray(frame.width)

            fun rowIsBlack(y: Int): Boolean {
                frame.getPixels(row, 0, frame.width, 0, y, frame.width, 1)
                var bright = 0
                var x = 0
                while (x < frame.width) {
                    val p = row[x]
                    val luma = ((p shr 16 and 0xFF) * 299 +
                        (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    if (luma > 24) bright++
                    x += 16
                }
                return bright < frame.width / 16 / 8
            }

            var y = 0
            while (y < frame.height / 2 && rowIsBlack(y)) y += 8
            contentTop = (y - 8).coerceAtLeast(0)

            y = frame.height - 1
            while (y > frame.height / 2 && rowIsBlack(y)) y -= 8
            contentBottom = (y + 8).coerceAtMost(frame.height)

            // A clip with no bars, or one this heuristic misread, is used whole.
            if (contentBottom - contentTop < frame.height / 4) {
                contentTop = 0
                contentBottom = frame.height
            }
        } finally {
            frame.recycle()
        }
    }

    /**
     * The frame at [positionMs], letterbox removed and downscaled the way the
     * camera path does.
     *
     * OPTION_CLOSEST rather than OPTION_CLOSEST_SYNC: sync frames in this clip
     * are seconds apart, and stepping between them would show the watch four
     * still images instead of a baby crawling.
     */
    fun frameAt(positionMs: Long): Bitmap? {
        val r = retriever ?: return null
        return try {
            val raw = r.getFrameAtTime(
                positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: return null

            val height = (contentBottom - contentTop).coerceAtMost(raw.height - contentTop)
            val cropped =
                if (height in 1 until raw.height) {
                    Bitmap.createBitmap(raw, 0, contentTop, raw.width, height)
                        .also { if (it !== raw) raw.recycle() }
                } else raw

            if (cropped.width <= ANALYSIS_WIDTH) return cropped
            val ratio = ANALYSIS_WIDTH.toFloat() / cropped.width
            Bitmap.createScaledBitmap(
                cropped,
                ANALYSIS_WIDTH,
                (cropped.height * ratio).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== cropped) cropped.recycle() }
        } catch (t: Throwable) {
            Log.w(TAG, "frame at ${positionMs}ms failed", t)
            null
        }
    }

    override fun close() {
        runCatching { retriever?.release() }
        retriever = null
    }
}
