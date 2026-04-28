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
import kotlinx.coroutines.delay
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
    var queueSize by mutableIntStateOf(0)
        private set

    private var recordingService: RecordingService? = null
    private var currentRecordedFile: File? = null
    private var whisperContext: com.whispercpp.whisper.WhisperContext? = null

    private val transcriptionQueue = Channel<File>(Channel.UNLIMITED)

    private val prefs: SharedPreferences by lazy {
        application.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.RecordingBinder
            recordingService = binder.getService()
            Log.d("WhisperVM", "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            Log.d("WhisperVM", "Service disconnected")
        }
    }

    init {
        // Bind to service
        Intent(application, RecordingService::class.java).also { intent ->
            application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        viewModelScope.launch {
            loadSavedState()
            copyAssets()
            loadBaseModel()
            canTranscribe = true
            processQueue()           // Start background transcription consumer
        }
    }

    private suspend fun loadSavedState() {
        dataLog = prefs.getString("data_log", "") ?: ""

        // Try to recover current recording file if app was killed
        val lastFilePath = prefs.getString("current_recording_file", null)
        if (lastFilePath != null) {
            val file = File(lastFilePath)
            if (file.exists() && file.length() > 0) {
                currentRecordedFile = file
                isRecording = true
                Log.d("WhisperVM", "Recovered in-progress recording: ${file.name}")
            }
        }
    }

    // Background worker that processes transcription queue
    private suspend fun processQueue() {
        for (fileToTranscribe in transcriptionQueue) {
            isTranscribing = true
            try {
                val data = try {
                    withContext(Dispatchers.IO) {
                        decodeWaveFile(fileToTranscribe)
                    }
                } catch (e: Exception) {
                    Log.e("WhisperVM", "Failed to decode ${fileToTranscribe.name} (${fileToTranscribe.length()} bytes)", e)
                    withContext(Dispatchers.Main) {
                        dataLog += "\n[Decode Error: ${fileToTranscribe.name} - ${e.message}]\n"
                    }
                    fileToTranscribe.delete()
                    return
                }

                val rawText = whisperContext?.transcribeData(data) ?: ""
                val regex = Regex("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s-->\\s\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]:")
                val cleanText = rawText.replace(regex, "").trim()

                if (cleanText.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        dataLog = if (dataLog.isEmpty()) cleanText else "$dataLog\n\n$cleanText"
                        saveTranscript()           // Save immediately after each chunk
                    }
                }

                // Delete temp file after successful processing
                fileToTranscribe.delete()
            } catch (e: Exception) {
                Log.e("WhisperVM", "Transcription error", e)
            } finally {
                queueSize--
                isTranscribing = queueSize > 0
            }
        }
    }

    fun transcribeCurrentChunk() = viewModelScope.launch {
        if (!canTranscribe || currentRecordedFile == null) return@launch

        val fileToQueue = currentRecordedFile!!

        Log.d("WhisperVM", "Transcribe Chunk pressed - stopping current recording...")

        // Stop recording and wait for it to finish writing
        recordingService?.stopRecording(shouldStopService = false)

        // Wait longer for file to be fully written and header finalized
        delay(800)

        if (fileToQueue.exists() && fileToQueue.length() > 2000) {   // Increased threshold
            queueSize++
            transcriptionQueue.send(fileToQueue)
            Log.d("WhisperVM", "Queued chunk: ${fileToQueue.name} (${fileToQueue.length()} bytes)")

            withContext(Dispatchers.Main) {
                //dataLog += "\n[Chunk queued for transcription - ${fileToQueue.length()} bytes]\n"
            }
        } else {
            Log.e("WhisperVM", "Chunk file still empty or too small: ${fileToQueue.length()} bytes")
            withContext(Dispatchers.Main) {
                dataLog += "\n[Error: Chunk file was empty or too small (${fileToQueue.length()} bytes)]\n"
            }
        }

        currentRecordedFile = null
        saveCurrentRecordingFile(null)

        // Start next chunk
        startNewRecording()
    }

    private suspend fun startNewRecording() {
        val newFile = withContext(Dispatchers.IO) {
            File.createTempFile("rec_${System.currentTimeMillis()}", ".wav", application.cacheDir)
        }
        currentRecordedFile = newFile

        // Save the new file path in case we get killed
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

            kotlinx.coroutines.delay(300)   // Let the file finish writing

            isRecording = false
            currentRecordedFile = null
            saveCurrentRecordingFile(null)

            if (fileToQueue != null) {
                queueSize++
                transcriptionQueue.send(fileToQueue)
            }
        } else {
            startNewRecording()
        }
    }

    private fun saveTranscript() {
        prefs.edit().putString("data_log", dataLog).apply()
    }

    private fun saveCurrentRecordingFile(file: File?) {
        prefs.edit()
            .putString("current_recording_file", file?.absolutePath)
            .apply()
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