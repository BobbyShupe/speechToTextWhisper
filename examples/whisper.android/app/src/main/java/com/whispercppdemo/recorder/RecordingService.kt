package com.whispercppdemo.recorder

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File

class RecordingService : Service() {

    private val binder = RecordingBinder()
    private val recorder = Recorder()
    private val CHANNEL_ID = "recording_channel"

    private var wakeLock: PowerManager.WakeLock? = null

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // Improved: Use START_REDELIVER_INTENT so the system tries harder to restart us
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_REDELIVER_INTENT
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    suspend fun startRecording(outputFile: File, onError: (Exception) -> Unit) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whisper Recording")
            .setContentText("Recording in progress... (Do not swipe away)")
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

        // Stronger wake lock
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WhisperCppDemo::RecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // 6 hours
        }

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

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Recording Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used for continuous audio recording"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }
}