package com.whispercppdemo.recorder

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.whispercppdemo.media.decodeWaveFile
import kotlinx.coroutines.*
import java.io.File

class RecordingService : Service() {

    private val binder = RecordingBinder()
    private val recorder = Recorder()
    private val CHANNEL_ID = "recording_channel"

    private var wakeLock: PowerManager.WakeLock? = null
    private val transcriptionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        transcriptionScope.cancel()
        super.onDestroy()
    }

    suspend fun startRecording(outputFile: File, onError: (Exception) -> Unit) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whisper Recording")
            .setContentText("Recording... Transcription in background")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
            startForeground(1, notification, serviceType)
        } else {
            startForeground(1, notification)
        }

        acquireWakeLock()
        recorder.startRecording(outputFile, onError)
    }

    suspend fun stopRecording(shouldStopService: Boolean = true) {
        recorder.stopRecording()
        releaseWakeLock()

        if (shouldStopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // Transcribe and automatically delete the WAV file afterwards
    fun queueForTranscription(file: File, onResult: (String) -> Unit) {
        transcriptionScope.launch {
            try {
                Log.d("Transcription", "Starting transcription for ${file.name}")

                val data = decodeWaveFile(file)
                Log.d("Transcription", "Audio decoded successfully, ${data.size} samples")

                // Use printTimestamp = false to get clean text
                val rawText = RecordingService.whisperContext?.transcribeData(data, printTimestamp = false) ?: ""

                Log.d("Transcription", "Raw whisper output length: ${rawText.length}")
                Log.d("Transcription", "Raw output preview: ${rawText.take(200)}")

                val cleanText = rawText.trim()

                val resultText = if (cleanText.isNotEmpty() && cleanText.length > 3) {
                    cleanText
                } else {
                    "[No speech detected - try speaking louder and closer to the microphone]"
                }

                withContext(Dispatchers.Main) {
                    onResult(resultText)
                }

            } catch (e: Exception) {
                Log.e("Transcription", "Transcription failed", e)
                withContext(Dispatchers.Main) {
                    onResult("[Transcription Error: ${e.message}]")
                }
            } finally {
                if (file.exists()) {
                    file.delete()
                    Log.d("Transcription", "Deleted ${file.name}")
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WhisperCppDemo::RecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording & Transcription",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Handles recording and background transcription"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        var whisperContext: com.whispercpp.whisper.WhisperContext? = null
    }
}