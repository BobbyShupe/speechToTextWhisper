package com.whispercppdemo.recorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Recorder {
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )
    private var recorderThread: AudioRecordThread? = null

    suspend fun startRecording(outputFile: File, onError: (Exception) -> Unit) = withContext(scope.coroutineContext) {
        recorderThread = AudioRecordThread(outputFile, onError)
        recorderThread?.start()
    }

    suspend fun stopRecording() = withContext(scope.coroutineContext) {
        recorderThread?.stopRecording()
        recorderThread?.join(4000) // Wait up to 4 seconds for clean shutdown
        recorderThread = null
    }
}

private class AudioRecordThread(
    private val outputFile: File,
    private val onError: (Exception) -> Unit
) : Thread("AudioRecorder") {

    private val quit = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    override fun run() {
        var audioRecord: AudioRecord? = null
        var fos: FileOutputStream? = null

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 4

            val buffer = ShortArray(bufferSize / 2)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                throw RuntimeException("Failed to initialize AudioRecord")
            }

            fos = FileOutputStream(outputFile)
            fos.write(createWavHeader(0)) // Write initial header

            audioRecord.startRecording()

            var totalSamples = 0

            while (!quit.get()) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    fos.write(shortToByteArray(buffer, read))
                    totalSamples += read
                } else if (read < 0) {
                    break
                }
            }

            audioRecord.stop()
            fos.flush()
            fos.close()

            // Update header with correct size
            updateWavHeader(outputFile, totalSamples)

            Log.d("AudioRecorder", "Finished recording: $totalSamples samples (${outputFile.length()} bytes)")

        } catch (e: Exception) {
            Log.e("AudioRecorder", "Recording failed", e)
            onError(e)
        } finally {
            audioRecord?.release()
            fos?.close()
        }
    }

    fun stopRecording() {
        quit.set(true)
    }

    private fun shortToByteArray(shorts: ShortArray, length: Int): ByteArray {
        val bb = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(shorts, 0, length)
        return bb.array()
    }
}

private fun createWavHeader(dataSize: Int): ByteArray {
    val totalSize = 36 + dataSize
    val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

    bb.put("RIFF".toByteArray())
    bb.putInt(totalSize)
    bb.put("WAVE".toByteArray())
    bb.put("fmt ".toByteArray())
    bb.putInt(16)
    bb.putShort(1)           // PCM
    bb.putShort(1)           // Mono
    bb.putInt(16000)
    bb.putInt(32000)
    bb.putShort(2)
    bb.putShort(16)
    bb.put("data".toByteArray())
    bb.putInt(dataSize)

    return bb.array()
}

private fun updateWavHeader(file: File, totalSamples: Int) {
    val dataSize = totalSamples * 2
    val raf = java.io.RandomAccessFile(file, "rw")
    try {
        raf.seek(4)
        raf.writeInt(Integer.reverseBytes(36 + dataSize))
        raf.seek(40)
        raf.writeInt(Integer.reverseBytes(dataSize))
    } finally {
        raf.close()
    }
}