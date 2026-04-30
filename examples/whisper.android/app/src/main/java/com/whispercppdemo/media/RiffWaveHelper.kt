package com.whispercppdemo.media

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun decodeWaveFile(file: File): FloatArray {
    Log.d("DecodeWAV", "Decoding ${file.name} (${file.length()} bytes)")

    if (!file.exists() || file.length() < 1024) {
        throw IllegalArgumentException("File too small: ${file.length()} bytes")
    }

    val data = file.readBytes()
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    val channels = buffer.getShort(22).toInt()
    if (channels < 1 || channels > 2) {
        throw IllegalArgumentException("Invalid channels: $channels")
    }

    var dataOffset = 44
    for (i in 0 until minOf(300, data.size - 8) step 4) {
        if (i + 4 <= data.size &&
            data[i].toInt().toChar() == 'd' &&
            data[i+1].toInt().toChar() == 'a' &&
            data[i+2].toInt().toChar() == 't' &&
            data[i+3].toInt().toChar() == 'a') {
            dataOffset = i + 8
            break
        }
    }

    buffer.position(dataOffset)
    val shortBuffer = buffer.asShortBuffer()
    val totalShorts = shortBuffer.remaining()
    val sampleCount = totalShorts / channels

    Log.d("DecodeWAV", "Total samples: $sampleCount")

    val floatArray = FloatArray(sampleCount)
    val chunkSize = 16384 * channels
    val tempShorts = ShortArray(chunkSize)

    var offset = 0
    while (offset < sampleCount) {
        val remaining = totalShorts - offset * channels
        val toRead = minOf(chunkSize, remaining)
        shortBuffer.get(tempShorts, 0, toRead)

        for (i in 0 until toRead / channels) {
            val idx = offset + i
            if (idx >= sampleCount) break

            if (channels == 1) {
                floatArray[idx] = (tempShorts[i] / 32767.0f).coerceIn(-1f, 1f)
            } else {
                val sample = (tempShorts[i*2] + tempShorts[i*2 + 1]) / 2
                floatArray[idx] = (sample / 32767.0f).coerceIn(-1f, 1f)
            }
        }
        offset += toRead / channels
    }

    Log.d("DecodeWAV", "Successfully decoded $sampleCount mono samples")
    return floatArray
}