package com.whispercppdemo.recorder

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
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

    // Fixed: Proper override + correct syntax
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    suspend fun startRecording(outputFile: File, onError: (Exception) -> Unit) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whisper Recording")
            .setContentText("Recording audio... Tap to return to app")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            startForeground(1, notification, serviceType)
        } else {
            startForeground(1, notification)
        }

        recorder.startRecording(outputFile, onError)
    }

    suspend fun stopRecording(shouldStopService: Boolean = true) {
        recorder.stopRecording()

        if (shouldStopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Recording Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used for background audio recording"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}