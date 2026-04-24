package com.whispercppdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.whispercppdemo.ui.main.MainScreen
import com.whispercppdemo.ui.main.MainScreenViewModel
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainScreenViewModel by viewModels { MainScreenViewModel.factory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Set status bar to transparent
        window.statusBarColor = Color.Transparent.toArgb()

        setContent {
            WhisperCppDemoTheme {
                MainScreen(viewModel)
            }
        }
    }
}