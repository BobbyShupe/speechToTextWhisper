package com.whispercppdemo.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    MainScreen(
        canTranscribe = viewModel.canTranscribe,
        isRecording = viewModel.isRecording,
        messageLog = viewModel.dataLog,
        onRecordTapped = viewModel::toggleRecord,
        onClearTapped = viewModel::clearLog
    )
}

@Composable
private fun MainScreen(
    canTranscribe: Boolean,
    isRecording: Boolean,
    messageLog: String,
    onRecordTapped: () -> Unit,
    onClearTapped: () -> Unit
) {
    val backgroundColor = Color(0xFF000000) // Dark background color
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        containerColor = backgroundColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    RecordButton(
                        enabled = canTranscribe,
                        isRecording = isRecording,
                        onClick = onRecordTapped
                    )
                }

                Button(
                    onClick = { clipboardManager.setText(AnnotatedString(messageLog)) },
                    enabled = messageLog.isNotEmpty()
                ) {
                    Text("Copy")
                }

                Button(
                    onClick = onClearTapped,
                    enabled = messageLog.isNotEmpty()
                ) {
                    Text("Clear")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            CumulativeTextDisplay(messageLog)
        }
    }
}

@Composable
private fun CumulativeTextDisplay(text: String) {
    val listState = rememberLazyListState()

    LaunchedEffect(text) {
        if (text.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White // Set to white for dark background
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