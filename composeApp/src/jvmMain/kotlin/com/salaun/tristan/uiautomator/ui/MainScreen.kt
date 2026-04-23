package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen

@Composable
fun MainScreen(state: AppState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Toolbar(state)
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxSize()) {
            ScreenshotPanel(
                pngBytes = state.screenshotPng,
                rootNode = state.rootNode,
                selectedNode = state.selectedNode,
                onNodeHovered = { state.selectNode(it) },
                onNodeClicked = { state.selectNode(it) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            VerticalDivider(modifier = Modifier.fillMaxHeight())
            XmlTreePanel(
                root = state.rootNode,
                expanded = state.expanded,
                selectedNode = state.selectedNode,
                onToggle = { state.toggle(it) },
                onSelect = { state.selectNode(it) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun Toolbar(state: AppState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { state.capture() },
            enabled = !state.busy && state.adbPath.isNotBlank(),
        ) { Text("Capturer") }

        OutlinedButton(onClick = { state.refreshDevices() }, enabled = !state.busy) {
            Text("Actualiser devices")
        }

        DeviceSelector(state)

        if (state.busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }

        Box(modifier = Modifier.weight(1f)) {
            Column {
                state.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.statusMessage.isNotBlank()) {
                    Text(state.statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        TextButton(onClick = { state.screen = Screen.Explorer }) {
            Text("Explorer")
        }
        if (state.explorerSession != null) {
            TextButton(onClick = { state.screen = Screen.Graph }) {
                Text("Graphe")
            }
        }
        TextButton(onClick = { state.screen = Screen.Settings }) {
            Text("Paramètres")
        }
    }
}

@Composable
private fun DeviceSelector(state: AppState) {
    var open by remember { mutableStateOf(false) }
    val selected = state.devices.firstOrNull { it.serial == state.selectedSerial }
    val label = when {
        selected != null -> selected.displayName
        state.selectedSerial != null -> state.selectedSerial!!
        state.devices.isEmpty() -> "Aucun device"
        else -> "Choisir un device"
    }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = !state.busy) {
            Text(label, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (state.devices.isEmpty()) {
                DropdownMenuItem(text = { Text("Aucun device") }, onClick = { open = false })
            } else {
                state.devices.forEach { d ->
                    DropdownMenuItem(
                        text = { Text(d.displayName) },
                        onClick = {
                            state.selectDevice(d.serial)
                            open = false
                        }
                    )
                }
            }
        }
    }
}
