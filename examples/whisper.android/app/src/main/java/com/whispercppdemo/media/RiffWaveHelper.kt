package com.whispercppdemo.media

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun decodeWaveFile(file: File): FloatArray {
    Log.d("DecodeWAV", "=== Decoding ${file.name} (${file.length()} bytes) ===")

    if (!file.exists() || file.length() < 1024) {
        throw IllegalArgumentException("File too small: ${file.length()} bytes")
    }

    val data = file.readBytes()
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    val channels = buffer.getShort(22).toInt()
    Log.d("DecodeWAV", "Channels: $channels")

    if (channels < 1 || channels > 2) {
        throw IllegalArgumentException("Invalid channels: $channels")
    }

    // Find 'data' chunk
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

    Log.d("DecodeWAV", "Total short samples: $totalShorts")

    val sampleCount = totalShorts / channels
    val floatArray = FloatArray(sampleCount)

    // Process in smaller chunks to reduce peak memory usage
    val chunkSize = 8192 * channels // Process 8K samples at a time
    val tempShorts = ShortArray(chunkSize)

    var offset = 0
    while (offset < sampleCount) {
        val shortsToRead = minOf(chunkSize, totalShorts - offset * channels)
        shortBuffer.get(tempShorts, 0, shortsToRead)

        for (i in 0 until shortsToRead / channels) {
            val idx = offset + i
            if (idx >= sampleCount) break

            when (channels) {
                1 -> floatArray[idx] = (tempShorts[i] / 32767.0f).coerceIn(-1f, 1f)
                else -> {
                    val sample = (tempShorts[i*2] + tempShorts[i*2 + 1]) / 2
                    floatArray[idx] = (sample / 32767.0f).coerceIn(-1f, 1f)
                }
            }
        }
        offset += shortsToRead / channels
    }

    Log.d("DecodeWAV", "Successfully decoded $sampleCount mono samples")
    return floatArray
}