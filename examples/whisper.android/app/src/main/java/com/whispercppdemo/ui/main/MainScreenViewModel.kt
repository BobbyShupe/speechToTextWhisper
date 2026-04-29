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
import com.whispercppdemo.recorder.RecordingService
import kotlinx.coroutines.*
import java.io.File

class MainScreenViewModel(private val application: Application) : ViewModel() {

    var canTranscribe by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set
    var isRecording by mutableStateOf(false)
        private set
    var queueSize by mutableIntStateOf(0)
        private set

    private var recordingService: RecordingService? = null
    private var currentRecordedFile: File? = null

    private val prefs: SharedPreferences by lazy {
        application.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            recordingService = (service as RecordingService.RecordingBinder).getService()
            Log.d("WhisperVM", "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            Log.d("WhisperVM", "Service disconnected")
        }
    }

    init {
        Intent(application, RecordingService::class.java).also {
            application.bindService(it, connection, Context.BIND_AUTO_CREATE)
        }

        viewModelScope.launch {
            loadSavedState()
            copyAssets()
            loadBaseModel()
            canTranscribe = true
        }
    }

    private suspend fun loadSavedState() {
        dataLog = prefs.getString("data_log", "") ?: ""
    }

    fun transcribeCurrentChunk() = viewModelScope.launch {
        if (!canTranscribe || currentRecordedFile == null) return@launch

        val fileToQueue = currentRecordedFile!!

        Log.d("WhisperVM", "Transcribe Chunk pressed - stopping current recording...")

        recordingService?.stopRecording(shouldStopService = false)
        delay(1000) // Give recorder time to finalize WAV file

        if (fileToQueue.exists() && fileToQueue.length() > 2000) {
            queueSize++
            recordingService?.queueForTranscription(fileToQueue) { result ->
                dataLog = if (dataLog.isEmpty()) result else "$dataLog\n\n$result"
                saveTranscript()
                queueSize = (queueSize - 1).coerceAtLeast(0)
            }
            Log.d("WhisperVM", "Queued chunk for background transcription: ${fileToQueue.name}")
        } else {
            Log.w("WhisperVM", "Chunk file too small: ${fileToQueue.length()} bytes")
        }

        currentRecordedFile = null
        saveCurrentRecordingFile(null)
        startNewRecording()
    }

    private suspend fun startNewRecording() {
        val newFile = withContext(Dispatchers.IO) {
            File.createTempFile("rec_${System.currentTimeMillis()}", ".wav", application.cacheDir)
        }
        currentRecordedFile = newFile
        saveCurrentRecordingFile(newFile)

        recordingService?.startRecording(newFile) { e ->
            viewModelScope.launch(Dispatchers.Main) {
                dataLog += "\n[Recording error: ${e.message}]\n"
                isRecording = false
            }
        }
        isRecording = true
    }

    fun toggleRecord() = viewModelScope.launch {
        if (isRecording) {
            val fileToQueue = currentRecordedFile
            recordingService?.stopRecording(shouldStopService = true)
            delay(1000)

            isRecording = false
            currentRecordedFile = null
            saveCurrentRecordingFile(null)

            if (fileToQueue != null && fileToQueue.length() > 2000) {
                queueSize++
                recordingService?.queueForTranscription(fileToQueue) { result ->
                    dataLog = if (dataLog.isEmpty()) result else "$dataLog\n\n$result"
                    saveTranscript()
                    queueSize = (queueSize - 1).coerceAtLeast(0)
                }
            }
        } else {
            startNewRecording()
        }
    }

    private fun saveTranscript() {
        prefs.edit().putString("data_log", dataLog).apply()
    }

    private fun saveCurrentRecordingFile(file: File?) {
        prefs.edit().putString("current_recording_file", file?.absolutePath).apply()
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
            RecordingService.whisperContext = com.whispercpp.whisper.WhisperContext.createContextFromAsset(
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
        saveTranscript()
        saveCurrentRecordingFile(currentRecordedFile)
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