package com.nila.audio

import android.content.Context
import java.io.DataInputStream
import java.io.InputStream

/**
 * Minimal RIFF/WAVE reader for the bundled assets.
 *
 * Deliberately not MediaExtractor: the self-test needs the raw samples in the
 * same shape the microphone produces them, and going through the platform
 * decoder to get PCM back out is more code and more failure modes than parsing
 * a header we generate ourselves.
 */
object WavReader {

    data class Wave(val samples: FloatArray, val sampleRate: Int)

    fun fromAsset(context: Context, name: String): Wave =
        context.assets.open(name).use { read(it) }

    fun read(stream: InputStream): Wave {
        val input = DataInputStream(stream.buffered())

        require(readTag(input) == "RIFF") { "not a RIFF file" }
        input.skipBytes(4)                       // overall size
        require(readTag(input) == "WAVE") { "not a WAVE file" }

        var sampleRate = 16_000
        var channels = 1
        var bits = 16

        while (true) {
            val tag = readTag(input)
            val size = readIntLE(input)
            when (tag) {
                "fmt " -> {
                    readShortLE(input)                       // audio format
                    channels = readShortLE(input)
                    sampleRate = readIntLE(input)
                    readIntLE(input)                         // byte rate
                    readShortLE(input)                       // block align
                    bits = readShortLE(input)
                    if (size > 16) input.skipBytes(size - 16)
                }
                "data" -> {
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    return Wave(toFloat(bytes, channels, bits), sampleRate)
                }
                else -> input.skipBytes(size + (size and 1))  // chunks are word-aligned
            }
        }
    }

    /** Interleaved PCM to mono float in [-1, 1]. */
    private fun toFloat(bytes: ByteArray, channels: Int, bits: Int): FloatArray {
        require(bits == 16) { "only 16-bit PCM is supported, got $bits" }
        val frames = bytes.size / (2 * channels)
        val out = FloatArray(frames)
        var b = 0
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                val lo = bytes[b].toInt() and 0xFF
                val hi = bytes[b + 1].toInt()
                acc += ((hi shl 8) or lo).toShort() / 32768.0f
                b += 2
            }
            out[f] = acc / channels
        }
        return out
    }

    /** Naive linear resample. Fine for a fixed asset; not for live audio. */
    fun resample(wave: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return wave
        val ratio = from.toDouble() / to
        val out = FloatArray((wave.size / ratio).toInt())
        for (i in out.indices) {
            val src = i * ratio
            val idx = src.toInt()
            val frac = (src - idx).toFloat()
            val a = wave[idx.coerceIn(0, wave.size - 1)]
            val b = wave[(idx + 1).coerceIn(0, wave.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    private fun readTag(input: DataInputStream): String {
        val b = ByteArray(4)
        input.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun readIntLE(input: DataInputStream): Int =
        (input.readUnsignedByte()) or (input.readUnsignedByte() shl 8) or
            (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 24)

    private fun readShortLE(input: DataInputStream): Int =
        (input.readUnsignedByte()) or (input.readUnsignedByte() shl 8)
}
