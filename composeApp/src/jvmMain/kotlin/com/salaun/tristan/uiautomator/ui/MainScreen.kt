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
import com.salaun.tristan.uiautomator.i18n.LocalStrings

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
                // `it` is now nullable: the tree clears its hover-driven
                // selection when the pointer leaves the panel.
                onSelect = { state.selectNode(it) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun Toolbar(state: AppState) {
    val strings = LocalStrings.current
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
        ) { Text(strings.toolbarCapture) }

        OutlinedButton(onClick = { state.refreshDevices() }, enabled = !state.busy) {
            Text(strings.toolbarRefreshDevices)
        }

        DeviceSelector(state)

        CaptureActionsMenu(state)

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
            Text(strings.toolbarExplorer)
        }
        TextButton(onClick = { state.screen = Screen.ManualExplorer }) {
            Text(strings.manualToolbarLabel)
        }
        TextButton(onClick = { state.screen = Screen.Sessions }) {
            Text(strings.toolbarSessions)
        }
        if (state.explorerSession != null) {
            TextButton(onClick = { state.screen = Screen.Graph }) {
                Text(strings.toolbarGraph)
            }
        }
        TextButton(onClick = { state.screen = Screen.Settings }) {
            Text(strings.toolbarSettings)
        }
    }
}

@Composable
private fun CaptureActionsMenu(state: AppState) {
    val strings = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    val hasCapture = state.screenshotPng != null && state.xmlText != null

    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(strings.captureActionsMenu + " ▾", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(strings.captureActionImport) },
                onClick = {
                    open = false
                    pickLoadFile(strings.captureImportDialogTitle, suggested = "*.zip")?.let {
                        state.importCaptureFrom(it)
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(strings.captureActionExport) },
                enabled = hasCapture,
                onClick = {
                    open = false
                    pickSaveFile(strings.captureExportDialogTitle, suggestedName = "capture.zip")?.let {
                        state.exportCaptureTo(it)
                    }
                },
            )
            androidx.compose.material3.HorizontalDivider()
            DropdownMenuItem(
                text = { Text(strings.captureActionCopyImage) },
                enabled = hasCapture,
                onClick = { open = false; state.copyScreenshotToClipboard() },
            )
            DropdownMenuItem(
                text = { Text(strings.captureActionCopyXml) },
                enabled = hasCapture,
                onClick = { open = false; state.copyXmlToClipboard() },
            )
            androidx.compose.material3.HorizontalDivider()
            DropdownMenuItem(
                text = { Text(strings.captureActionSaveImage) },
                enabled = hasCapture,
                onClick = {
                    open = false
                    pickSaveFile(strings.captureSaveImageDialogTitle, suggestedName = "screenshot.png")?.let {
                        state.saveScreenshotTo(it)
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(strings.captureActionSaveXml) },
                enabled = hasCapture,
                onClick = {
                    open = false
                    pickSaveFile(strings.captureSaveXmlDialogTitle, suggestedName = "dump.xml")?.let {
                        state.saveXmlTo(it)
                    }
                },
            )
        }
    }
}

private fun pickSaveFile(title: String, suggestedName: String): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.SAVE)
    dialog.file = suggestedName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return java.io.File(dir, file)
}

private fun pickLoadFile(title: String, suggested: String): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.file = suggested
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return java.io.File(dir, file)
}

@Composable
private fun DeviceSelector(state: AppState) {
    val strings = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    val selected = state.devices.firstOrNull { it.serial == state.selectedSerial }
    val label = when {
        selected != null -> selected.displayName
        state.selectedSerial != null -> state.selectedSerial!!
        state.devices.isEmpty() -> strings.toolbarNoDevice
        else -> strings.toolbarChooseDevice
    }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = !state.busy) {
            Text(label, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (state.devices.isEmpty()) {
                DropdownMenuItem(text = { Text(strings.toolbarNoDevice) }, onClick = { open = false })
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
