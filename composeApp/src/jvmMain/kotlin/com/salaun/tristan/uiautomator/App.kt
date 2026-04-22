package com.salaun.tristan.uiautomator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.salaun.tristan.uiautomator.settings.AppSettings
import com.salaun.tristan.uiautomator.ui.MainScreen
import com.salaun.tristan.uiautomator.ui.SettingsScreen

@Composable
@Preview
fun App() {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        val settings = remember { AppSettings.load() }
        val state = remember { AppState(settings, scope) }

        LaunchedEffect(Unit) { state.initialize() }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (state.screen) {
                    Screen.Main -> MainScreen(state)
                    Screen.Settings -> SettingsScreen(state)
                }
            }
        }
    }
}
