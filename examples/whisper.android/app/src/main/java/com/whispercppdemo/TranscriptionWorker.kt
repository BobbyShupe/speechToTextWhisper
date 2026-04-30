package com.whispercppdemo.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.whispercppdemo.media.decodeWaveFile
import com.whispercppdemo.recorder.RecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return@withContext Result.failure()

        val file = File(filePath)
        if (!file.exists()) {
            Log.e("TranscriptionWorker", "File not found: $filePath")
            return@withContext Result.failure()
        }

        try {
            Log.d("TranscriptionWorker", "Starting transcription for ${file.name}")

            val data = decodeWaveFile(file)
            val rawText = RecordingService.whisperContext?.transcribeData(data, printTimestamp = false) ?: ""

            val cleanText = rawText.replace(
                Regex("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s-->\\s\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]:?"),
                ""
            ).trim()

            val resultText = if (cleanText.isNotEmpty()) cleanText else "[No speech detected]"

            // Send result back via broadcast or SharedPreferences
            sendResult(resultText)

            file.delete()
            Log.d("TranscriptionWorker", "Transcription completed and file deleted")

            Result.success()
        } catch (e: Exception) {
            Log.e("TranscriptionWorker", "Transcription failed", e)
            file.delete() // Clean up even on failure
            Result.retry() // or failure()
        }
    }

    private fun sendResult(text: String) {
        // For simplicity, we'll use SharedPreferences for now
        val prefs = applicationContext.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
        val currentLog = prefs.getString("data_log", "") ?: ""
        val newLog = if (currentLog.isEmpty()) text else "$currentLog\n\n$text"

        prefs.edit().putString("data_log", newLog).apply()

        Log.d("TranscriptionWorker", "Result saved to prefs")
    }

    companion object {
        const val KEY_FILE_PATH = "file_path"
    }
}