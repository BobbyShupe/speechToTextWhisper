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

    private var recordingService: RecordingService? = null
    private var currentRecordedFile: File? = null
    private var whisperContext: com.whispercpp.whisper.WhisperContext? = null

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
        viewModelScope.launch { loadData() }
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

    // Clean version: Stop → Transcribe in background → Immediately start new recording
    fun transcribeCurrentChunk() = viewModelScope.launch {
        if (!canTranscribe || isTranscribing || currentRecordedFile == null) return@launch

        val fileToTranscribe = currentRecordedFile!!

        // 1. Stop current recording
        recordingService?.stopRecording()
        isRecording = false

        // 2. Immediately start new recording (recording continues)
        startNewRecording()

        // 3. Transcribe previous chunk silently in background
        isTranscribing = true
        canTranscribe = false

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
        } catch (e: Exception) {
            Log.e("Whisper", "Transcription error", e)
            withContext(Dispatchers.Main) {
                dataLog += "[Transcription error]\n"
            }
        } finally {
            isTranscribing = false
            canTranscribe = true
        }
    }

    private suspend fun startNewRecording() {
        val newFile = withContext(Dispatchers.IO) {
            File.createTempFile("rec", ".wav", application.cacheDir)
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
            recordingService?.stopRecording()
            isRecording = false
            currentRecordedFile = null
        } else {
            startNewRecording()
        }
    }

    fun clearLog() {
        dataLog = ""
        saveTranscript()
    }

    override fun onCleared() {
        application.unbindService(connection)
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