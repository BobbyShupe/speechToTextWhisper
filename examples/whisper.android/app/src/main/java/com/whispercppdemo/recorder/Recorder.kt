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
    private var recorder: AudioRecordThread? = null

    suspend fun startRecording(outputFile: File, onError: (Exception) -> Unit) = withContext(scope.coroutineContext) {
        recorder = AudioRecordThread(outputFile, onError)
        recorder?.start()
    }

    suspend fun stopRecording() = withContext(scope.coroutineContext) {
        recorder?.stopRecording()
        recorder?.join(4000)   // Wait longer for finalization
        recorder = null
    }
}

private class AudioRecordThread(
    private val outputFile: File,
    private val onError: (Exception) -> Unit
) : Thread("AudioRecorder") {

    private var quit = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    override fun run() {
        var audioRecord: AudioRecord? = null
        var fos: FileOutputStream? = null

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ) * 4

            val buffer = ShortArray(bufferSize / 2)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                throw RuntimeException("AudioRecord initialization failed")
            }

            fos = FileOutputStream(outputFile)

            // Write correct header with placeholder size first
            fos.write(createWavHeader(0))

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

            // Update header with real size
            fos.close()
            updateWavHeader(outputFile, totalSamples)

            Log.d("AudioRecorder", "Finished recording: $totalSamples samples (${outputFile.length()} bytes)")

        } catch (e: Exception) {
            Log.e("AudioRecorder", "Recording error", e)
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
    bb.putShort(1)        // Audio format = PCM
    bb.putShort(1)        // Channels = 1 (mono)
    bb.putInt(16000)      // Sample rate
    bb.putInt(32000)      // Byte rate
    bb.putShort(2)        // Block align
    bb.putShort(16)       // Bits per sample
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