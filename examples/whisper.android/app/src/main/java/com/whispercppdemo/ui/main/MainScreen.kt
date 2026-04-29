package com.whispercppdemo.ui.main

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    val backgroundColor = Color(0xFF000000)
    val clipboardManager = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }

    Scaffold(containerColor = backgroundColor) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            // Debug line
            Text(
                text = "Current Model: ${viewModel.selectedModel}",
                color = Color.Yellow,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Simple Dropdown
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = viewModel.selectedModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Select Whisper Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    viewModel.availableModels.forEach { modelName ->
                        DropdownMenuItem(
                            text = { Text(modelName) },
                            onClick = {
                                Log.d("MainScreen", "Dropdown clicked: $modelName")   // Debug log
                                viewModel.selectModel(modelName)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Record Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    RecordButton(
                        enabled = viewModel.canTranscribe,
                        isRecording = viewModel.isRecording,
                        onClick = viewModel::toggleRecord
                    )
                }

                Button(
                    onClick = viewModel::transcribeCurrentChunk,
                    enabled = viewModel.canTranscribe && viewModel.isRecording,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (viewModel.queueSize > 0) "Queue: ${viewModel.queueSize}" else "Transcribe Chunk")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { clipboardManager.setText(AnnotatedString(viewModel.dataLog)) },
                    enabled = viewModel.dataLog.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Copy")
                }

                Button(
                    onClick = viewModel::clearLog,
                    enabled = viewModel.dataLog.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            CumulativeTextDisplay(viewModel.dataLog)
        }
    }
}

// Keep these two functions as they were
@Composable
private fun CumulativeTextDisplay(text: String) {
    val listState = rememberLazyListState()

    LaunchedEffect(text) {
        if (text.isNotEmpty()) {
            listState.animateScrollToItem(0, Int.MAX_VALUE)
        }
    }

    SelectionContainer {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun RecordButton(enabled: Boolean, isRecording: Boolean, onClick: () -> Unit) {
    val micPermissionState = rememberPermissionState(
        permission = android.Manifest.permission.RECORD_AUDIO,
        onPermissionResult = { if (it) onClick() }
    )

    Button(
        onClick = {
            if (micPermissionState.status.isGranted) onClick()
            else micPermissionState.launchPermissionRequest()
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (isRecording) "Stop recording" else "Start recording")
    }
}