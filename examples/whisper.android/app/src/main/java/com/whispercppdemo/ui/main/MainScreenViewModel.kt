package com.whispercppdemo.ui.main

import android.app.Application
import android.content.*
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercppdemo.media.decodeWaveFile
import com.whispercppdemo.recorder.RecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var canTranscribe by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set
    var isRecording by mutableStateOf(false)
        private set
    var isTranscribing by mutableStateOf(false)
        private set

    // New: Tracks how many chunks are waiting in the queue
    var queueSize by mutableIntStateOf(0)
        private set

    private var recordingService: RecordingService? = null
    private var currentRecordedFile: File? = null
    private var whisperContext: com.whispercpp.whisper.WhisperContext? = null

    // Thread-safe queue for audio files
    private val transcriptionQueue = Channel<File>(Channel.UNLIMITED)

    private val prefs: SharedPreferences by lazy {
        application.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.RecordingBinder
            recordingService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
        }
    }

    init {
        Intent(application, RecordingService::class.java).also { intent ->
            application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        viewModelScope.launch {
            loadData()
            processQueue() // Start the background consumer
        }
    }

    private suspend fun loadData() {
        try {
            loadSavedTranscript()
            copyAssets()
            loadBaseModel()
            canTranscribe = true
        } catch (e: Exception) {
            Log.e("Whisper", "Load failed", e)
        }
    }

    // Background worker that processes the queue one by one
    private suspend fun processQueue() {
        for (fileToTranscribe in transcriptionQueue) {
            isTranscribing = true
            try {
                val data = withContext(Dispatchers.IO) { decodeWaveFile(fileToTranscribe) }

                val rawText = whisperContext?.transcribeData(data) ?: ""
                val regex = Regex("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s-->\\s\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]:")
                val cleanText = rawText.replace(regex, "").trim()

                if (cleanText.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        dataLog = if (dataLog.isEmpty()) cleanText else "$dataLog\n\n$cleanText"
                        saveTranscript()
                    }
                }
                // Delete temp file after processing
                fileToTranscribe.delete()
            } catch (e: Exception) {
                Log.e("Whisper", "Transcription error", e)
            } finally {
                queueSize--
                isTranscribing = queueSize > 0
            }
        }
    }

    fun transcribeCurrentChunk() = viewModelScope.launch {
        if (!canTranscribe || currentRecordedFile == null) return@launch

        val fileToQueue = currentRecordedFile!!

        // Stop recording but DO NOT stop the service yet
        recordingService?.stopRecording(shouldStopService = false)

        // Queue the finished chunk
        queueSize++
        transcriptionQueue.send(fileToQueue)

        // Immediately start the next recording chunk
        startNewRecording()
    }

    private suspend fun startNewRecording() {
        val newFile = withContext(Dispatchers.IO) {
            File.createTempFile("rec_${System.currentTimeMillis()}", ".wav", application.cacheDir)
        }
        currentRecordedFile = newFile

        recordingService?.startRecording(newFile) { e ->
            viewModelScope.launch(Dispatchers.Main) {
                dataLog += "[Recording error]\n"
                isRecording = false
            }
        }
        isRecording = true
    }

    fun toggleRecord() = viewModelScope.launch {
        if (isRecording) {
            val fileToQueue = currentRecordedFile
            // Really stop the service now
            recordingService?.stopRecording(shouldStopService = true)

            isRecording = false
            currentRecordedFile = null

            // Queue the final chunk
            if (fileToQueue != null) {
                queueSize++
                transcriptionQueue.send(fileToQueue)
            }
        } else {
            startNewRecording()
        }
    }

    private fun loadSavedTranscript() {
        dataLog = prefs.getString("data_log", "") ?: ""
    }

    private fun saveTranscript() {
        prefs.edit().putString("data_log", dataLog).apply()
    }

    private suspend fun copyAssets() = withContext(Dispatchers.IO) {
        val modelsPath = File(application.filesDir, "models").apply { mkdirs() }
        application.assets.list("models")?.forEach { name ->
            val dest = File(modelsPath, name)
            if (!dest.exists()) {
                application.assets.open("models/$name").use { it.copyTo(dest.outputStream()) }
            }
        }
    }

    private suspend fun loadBaseModel() = withContext(Dispatchers.IO) {
        application.assets.list("models/")?.firstOrNull()?.let {
            whisperContext = com.whispercpp.whisper.WhisperContext.createContextFromAsset(
                application.assets, "models/$it"
            )
        }
    }

    fun clearLog() {
        dataLog = ""
        saveTranscript()
    }

    override fun onCleared() {
        application.unbindService(connection)
        transcriptionQueue.close()
        super.onCleared()
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                MainScreenViewModel(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                )
            }
        }
    }
}