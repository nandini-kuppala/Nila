package com.nila.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * The few seconds before an alert.
 *
 * An alert that arrives with a single frame answers "what does it look like
 * now". A parent's actual question is "what happened" -- did she roll, did the
 * blanket move, was she already like that. Keeping a short rolling buffer and
 * dumping it when a rule fires answers that instead, and it costs a fixed
 * amount of memory rather than a recording.
 *
 * Nothing is written to disk until an alert fires, so the ordinary case -- an
 * uneventful night -- leaves nothing behind at all. That is a deliberate privacy
 * property, not an optimisation.
 */
class PreRoll(
    private val context: Context,
    private val capacity: Int = 15,          // ~3 s at 5 fps
    private val thumbWidth: Int = 160,
) {
    companion object {
        private const val TAG = "PreRoll"
        private const val DIR = "prerolls"
        private const val STRIP_COLUMNS = 5
    }

    private data class Frame(val bitmap: Bitmap, val atMs: Long)

    private val buffer = ArrayDeque<Frame>()

    @Synchronized
    fun offer(source: Bitmap, atMs: Long = System.currentTimeMillis()) {
        val scale = thumbWidth.toFloat() / source.width.coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(
            source,
            thumbWidth,
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        buffer.addLast(Frame(thumb, atMs))
        while (buffer.size > capacity) {
            buffer.removeFirst().bitmap.recycle()
        }
    }

    @Synchronized
    fun clear() {
        buffer.forEach { it.bitmap.recycle() }
        buffer.clear()
    }

    val size: Int @Synchronized get() = buffer.size

    /** Seconds of history currently held. */
    val spanSeconds: Float
        @Synchronized get() {
            if (buffer.size < 2) return 0f
            return (buffer.last().atMs - buffer.first().atMs) / 1000f
        }

    /**
     * Write the buffer as one contact-sheet image.
     *
     * A strip rather than a video: it needs no encoder, opens in any viewer,
     * survives being attached to a notification, and -- most usefully -- shows
     * the whole sequence at once instead of asking a half-asleep parent to
     * scrub a three-second clip.
     */
    @Synchronized
    fun dump(label: String): File? {
        if (buffer.isEmpty()) return null
        val frames = buffer.toList()

        val cols = minOf(STRIP_COLUMNS, frames.size)
        val rows = (frames.size + cols - 1) / cols
        val fw = frames.first().bitmap.width
        val fh = frames.first().bitmap.height
        val labelH = 18

        val sheet = Bitmap.createBitmap(
            cols * fw, rows * (fh + labelH), Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(16, 18, 20))

        val text = Paint().apply {
            color = Color.rgb(200, 204, 208); textSize = 11f; isAntiAlias = true
        }
        val startMs = frames.first().atMs

        frames.forEachIndexed { i, frame ->
            val x = (i % cols) * fw
            val y = (i / cols) * (fh + labelH)
            canvas.drawBitmap(frame.bitmap, null,
                              Rect(x, y, x + fw, y + fh), null)
            val offset = (frame.atMs - startMs) / 1000f
            canvas.drawText(
                if (i == frames.size - 1) "alert"
                else "-%.1fs".format((frames.last().atMs - frame.atMs) / 1000f),
                x + 4f, (y + fh + 13).toFloat(), text,
            )
        }

        return try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            prune(dir)
            val out = File(dir, "${label}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { sheet.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            Log.i(TAG, "wrote ${frames.size}-frame pre-roll to ${out.name}")
            out
        } catch (t: Throwable) {
            Log.e(TAG, "could not write pre-roll", t)
            null
        } finally {
            sheet.recycle()
        }
    }

    /** Keep only the most recent few. A night of alerts should not fill the disk. */
    private fun prune(dir: File, keep: Int = 20) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep).forEach { runCatching { it.delete() } }
    }
}
