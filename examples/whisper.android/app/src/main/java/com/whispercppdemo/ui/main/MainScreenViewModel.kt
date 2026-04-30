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

    var availableModels by mutableStateOf<List<String>>(emptyList())
        private set
    var selectedModel by mutableStateOf("Select Model")
        private set

    private var recordingService: RecordingService? = null
    private var currentRecordedFile: File? = null

    private val prefs: SharedPreferences by lazy {
        application.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            recordingService = (service as RecordingService.RecordingBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
        }
    }

    init {
        Intent(application, RecordingService::class.java).also {
            application.bindService(it, connection, Context.BIND_AUTO_CREATE)
        }

        viewModelScope.launch {
            loadSavedState()
            copyAssets()
            loadAvailableModels()
            loadBaseModel()
            canTranscribe = true
            resumePendingTranscriptions()
        }
    }

    private fun loadSavedState() {
        dataLog = prefs.getString("data_log", "") ?: ""
        selectedModel = prefs.getString("selected_model", "Select Model") ?: "Select Model"
    }

    private suspend fun copyAssets() = withContext(Dispatchers.IO) {
        val modelsPath = File(application.filesDir, "models").apply { mkdirs() }
        val assetModels = application.assets.list("models") ?: emptyArray()
        Log.d("WhisperVM", "Models in assets: ${assetModels.joinToString()}")
        assetModels.forEach { name ->
            val dest = File(modelsPath, name)
            if (!dest.exists()) {
                try {
                    application.assets.open("models/$name").use { it.copyTo(dest.outputStream()) }
                } catch (e: Exception) {
                    Log.e("WhisperVM", "Failed to copy $name", e)
                }
            }
        }
    }

    private fun loadAvailableModels() {
        val modelsDir = File(application.filesDir, "models")
        availableModels = modelsDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }

    private suspend fun loadBaseModel() = withContext(Dispatchers.IO) {
        val modelToLoad = selectedModel.takeIf { it != "Select Model" } ?: availableModels.firstOrNull()
        if (modelToLoad == null) return@withContext

        val modelFile = File(application.filesDir, "models/$modelToLoad")
        if (modelFile.exists()) {
            try {
                val context = com.whispercpp.whisper.WhisperContext.createContextFromFile(modelFile.absolutePath)
                RecordingService.whisperContext = context
                withContext(Dispatchers.Main) {
                    selectedModel = modelToLoad
                }
            } catch (e: Exception) {
                Log.e("WhisperVM", "Failed to load model", e)
            }
        }
    }

    private fun resumePendingTranscriptions() {
        viewModelScope.launch(Dispatchers.IO) {
            val cacheDir = application.cacheDir
            val pendingFiles = cacheDir.listFiles { file ->
                file.name.endsWith(".wav", ignoreCase = true) && file.length() > 2000
            } ?: emptyArray<File>()

            if (pendingFiles.isEmpty()) return@launch

            Log.d("WhisperVM", "Resuming ${pendingFiles.size} pending files")

            withContext(Dispatchers.Main) {
                //dataLog += "\n[Resuming ${pendingFiles.size} pending recordings...]\n"
            }

            for (file in pendingFiles) {
                queueSize++
                recordingService?.queueForTranscription(file)
                delay(600)
            }
        }
    }

    fun selectModel(modelName: String) = viewModelScope.launch {
        if (modelName == selectedModel || modelName.isEmpty()) return@launch

        try {
            RecordingService.whisperContext?.release()

            val modelFile = File(application.filesDir, "models/$modelName")
            if (!modelFile.exists()) return@launch

            val newContext = com.whispercpp.whisper.WhisperContext.createContextFromFile(modelFile.absolutePath)

            RecordingService.whisperContext = newContext
            selectedModel = modelName
            prefs.edit().putString("selected_model", modelName).apply()
        } catch (e: Exception) {
            Log.e("WhisperVM", "Model switch failed", e)
        }
    }

    fun transcribeCurrentChunk() = viewModelScope.launch {
        if (!canTranscribe || currentRecordedFile == null) return@launch

        val fileToQueue = currentRecordedFile!!

        recordingService?.stopRecording(shouldStopService = false)
        delay(1000)

        if (fileToQueue.exists() && fileToQueue.length() > 2000) {
            queueSize++
            recordingService?.queueForTranscription(fileToQueue)
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
                recordingService?.queueForTranscription(fileToQueue)
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