package com.whispercppdemo.media

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun decodeWaveFile(file: File): FloatArray {
    Log.d("DecodeWAV", "=== Starting decode for ${file.name} ===")
    Log.d("DecodeWAV", "File size: ${file.length()} bytes")

    if (!file.exists() || file.length() < 1024) {
        Log.e("DecodeWAV", "File too small or missing")
        throw IllegalArgumentException("File too small: ${file.length()} bytes")
    }

    val data = file.readBytes()
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    // Read channels
    val channels = buffer.getShort(22).toInt()
    Log.d("DecodeWAV", "Channels from header: $channels")

    if (channels < 1 || channels > 2) {
        Log.e("DecodeWAV", "Invalid channels: $channels")
        throw IllegalArgumentException("Invalid channels: $channels")
    }

    // Find data chunk
    var dataOffset = 44
    val searchLimit = minOf(300, data.size - 8)
    for (i in 0 until searchLimit step 4) {
        if (i + 4 <= data.size &&
            data[i].toInt().toChar() == 'd' &&
            data[i+1].toInt().toChar() == 'a' &&
            data[i+2].toInt().toChar() == 't' &&
            data[i+3].toInt().toChar() == 'a') {
            dataOffset = i + 8
            Log.d("DecodeWAV", "Found 'data' chunk at offset $dataOffset")
            break
        }
    }

    buffer.position(dataOffset)
    val shortBuffer = buffer.asShortBuffer()
    val shorts = ShortArray(shortBuffer.remaining())
    shortBuffer.get(shorts)

    Log.d("DecodeWAV", "Extracted ${shorts.size} short samples")

    if (shorts.isEmpty()) {
        throw IllegalArgumentException("No audio data found")
    }

    val sampleCount = shorts.size / channels
    Log.d("DecodeWAV", "Final mono samples: $sampleCount")

    // Check if audio is mostly silence
    val maxAmplitude = shorts.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
    Log.d("DecodeWAV", "Max amplitude: $maxAmplitude / 32767")

    if (maxAmplitude < 500) {
        Log.w("DecodeWAV", "Audio appears to be very quiet or silent")
    }

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