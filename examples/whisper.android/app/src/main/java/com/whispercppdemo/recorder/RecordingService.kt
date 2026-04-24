package com.whispercppdemo.recorder

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

class RecordingService : Service() {
    private val binder = RecordingBinder()
    private val recorder = Recorder()
    private val CHANNEL_ID = "recording_channel"

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    suspend fun startRecording(outputFile: File, onError: (Exception) -> Unit) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whisper Recording")
            .setContentText("Recording audio in background...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        recorder.startRecording(outputFile, onError)
    }

    suspend fun stopRecording() {
        recorder.stopRecording()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Recording Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}