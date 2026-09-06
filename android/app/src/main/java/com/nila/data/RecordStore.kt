package com.nila.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Local file storage for health documents.
 *
 * Everything lands in the app's private files directory, which is excluded from
 * cloud backup in the manifest. A photograph of a prescription is among the more
 * sensitive things on a phone; it should not silently end up in a backup
 * transport because the default was convenient.
 */
class RecordStore(private val context: Context) {

    companion object {
        private const val TAG = "RecordStore"
        private const val DIR = "records"
        /** Big enough for OCR, small enough that a shelf of documents is not a
         *  gigabyte. Prescription text is legible well below full sensor size. */
        private const val MAX_EDGE = 2048
        private const val JPEG_QUALITY = 88
    }

    private fun dir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun saveImage(bitmap: Bitmap, prefix: String = "rec"): File {
        val scaled = downscale(bitmap)
        val file = File(dir(), "${prefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }
        if (scaled !== bitmap) scaled.recycle()
        return file
    }

    /** Copy a picked document in, so it survives the source app revoking access. */
    fun importUri(uri: Uri, prefix: String = "rec"): Pair<File, String?>? {
        return try {
            val type = context.contentResolver.getType(uri)
            val extension = when {
                type == null -> "bin"
                type.contains("pdf") -> "pdf"
                type.contains("png") -> "png"
                type.contains("image") -> "jpg"
                else -> "bin"
            }
            val file = File(dir(), "${prefix}_${System.currentTimeMillis()}.$extension")
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
                true
            } ?: false
            if (copied) file to type else null
        } catch (t: Throwable) {
            Log.e(TAG, "could not import $uri", t)
            null
        }
    }

    fun loadBitmap(path: String?): Bitmap? {
        if (path == null) return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    /** Decode a picked image, downscaled, without loading the full thing first. */
    fun decodeUri(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val sample = maxOf(
            1,
            maxOf(bounds.outWidth, bounds.outHeight) / MAX_EDGE,
        )
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "could not decode $uri", t)
        null
    }

    fun delete(path: String?) {
        path ?: return
        runCatching { File(path).delete() }
    }

    fun totalBytes(): Long =
        dir().listFiles()?.sumOf { it.length() } ?: 0L

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val scale = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true,
        )
    }
}
