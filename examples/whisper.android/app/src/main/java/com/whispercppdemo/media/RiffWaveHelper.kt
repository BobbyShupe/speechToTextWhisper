package com.whispercppdemo.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun decodeWaveFile(file: File): FloatArray {
    if (!file.exists() || file.length() < 1024) {
        throw IllegalArgumentException("WAV file too small: ${file.length()} bytes")
    }

    val data = file.readBytes()
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    // Read channels from standard position (byte 22)
    val channels = buffer.getShort(22).toInt()

    if (channels != 1 && channels != 2) {
        throw IllegalArgumentException("Invalid number of channels: $channels")
    }

    // Most WAV files have the data starting at byte 44.
    // If not, we do a simple search for "data" chunk
    var dataOffset = 44
    if (data.size > 44) {
        val headerEnd = minOf(100, data.size - 8)
        for (i in 0 until headerEnd step 4) {
            if (i + 4 <= data.size &&
                data[i].toInt().toChar() == 'd' &&
                data[i+1].toInt().toChar() == 'a' &&
                data[i+2].toInt().toChar() == 't' &&
                data[i+3].toInt().toChar() == 'a') {
                dataOffset = i + 8
                break
            }
        }
    }

    if (dataOffset + 100 > data.size) {
        throw IllegalArgumentException("Could not find audio data in WAV file")
    }

    buffer.position(dataOffset)

    val shortBuffer = buffer.asShortBuffer()
    val shorts = ShortArray(shortBuffer.remaining())
    shortBuffer.get(shorts)

    if (shorts.isEmpty()) {
        throw IllegalArgumentException("No audio samples found")
    }

    val sampleCount = shorts.size / channels

    return FloatArray(sampleCount) { i ->
        when (channels) {
            1 -> (shorts[i] / 32767.0f).coerceIn(-1f, 1f)
            else -> {
                val sample = (shorts[i*2] + shorts[i*2 + 1]) / 2
                (sample / 32767.0f).coerceIn(-1f, 1f)
            }
        }
    }
}