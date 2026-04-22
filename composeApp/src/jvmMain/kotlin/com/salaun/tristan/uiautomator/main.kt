package com.salaun.tristan.uiautomator

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize

fun main() = application {
    val windowState = rememberWindowState(width = 1400.dp, height = 900.dp)
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "UIAutomator Desktop",
    ) {
        App()
    }
}
