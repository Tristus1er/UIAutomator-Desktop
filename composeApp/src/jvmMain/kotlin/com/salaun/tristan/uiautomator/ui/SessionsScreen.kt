package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import com.salaun.tristan.uiautomator.explorer.SessionSummary
import com.salaun.tristan.uiautomator.i18n.LocalStrings
import com.salaun.tristan.uiautomator.i18n.Strings
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(state: AppState) {
    val strings = LocalStrings.current
    var refreshTick by remember { mutableStateOf(0) }
    val sessions = remember(refreshTick, state.sessionsRoot) { state.listSessions() }
    var toDelete by remember { mutableStateOf<SessionSummary?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val toastScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.sessionsTitle, style = MaterialTheme.typography.headlineSmall)
            Box(Modifier.weight(1f))
            OutlinedButton(onClick = { refreshTick++ }) { Text(strings.sessionsRefresh) }
            OutlinedButton(onClick = {
                val picked = pickImportZip(strings.sessionsImportDialogTitle)
                if (picked != null) {
                    state.importSession(picked, openAfter = true)
                    refreshTick++
                }
            }) { Text(strings.importLabel) }
            OutlinedButton(onClick = { state.screen = Screen.Main }) { Text(strings.back) }
        }

        // Folder line: selectable text + right-click to copy. The text wraps a
        // SelectionContainer so the user can drag-select chars manually; the
        // right-click handler is attached via pointerInput (Compose Desktop
        // does not yet have a high-level "context click" modifier). On copy,
        // a snackbar pops up briefly to confirm.
        val folderPath = state.sessionsRoot.absolutePath
        val folderCopiedMessage = strings.sessionsFolderCopiedToast
        SelectionContainer {
            Text(
                "${strings.sessionsFolderPrefix}$folderPath",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.pointerInput(folderPath) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.type == PointerEventType.Press &&
                                ev.buttons.isSecondaryPressed
                            ) {
                                copyTextToClipboard(folderPath)
                                toastScope.launch {
                                    // Dismiss any prior toast so repeated
                                    // right-clicks always show a fresh one.
                                    snackbarHost.currentSnackbarData?.dismiss()
                                    snackbarHost.showSnackbar(folderCopiedMessage)
                                }
                            }
                        }
                    }
                },
            )
        }
        Text(
            strings.sessionsFolderCopyHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.statusMessage.isNotBlank()) {
            Text(state.statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider()

        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(strings.sessionsNone)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(sessions, key = { it.dir.absolutePath }) { summary ->
                    SessionRow(
                        strings = strings,
                        summary = summary,
                        onOpen = { state.loadSessionFromDir(summary.dir) },
                        onExport = {
                            val out = pickExportZip(
                                title = strings.sessionsExportDialogTitle,
                                suggestedName = summary.dir.name,
                            )
                            if (out != null) state.exportSession(summary.dir, out)
                        },
                        onDelete = { toDelete = summary },
                    )
                }
            }
        }
    }

    toDelete?.let { summary ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(strings.sessionsDeleteConfirmTitle) },
            text = { Text(strings.sessionsDeleteConfirmBodyFmt.format(summary.dir.absolutePath)) },
            confirmButton = {
                TextButton(onClick = {
                    state.deleteSession(summary.dir)
                    toDelete = null
                    refreshTick++
                }) { Text(strings.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(strings.cancel) }
            },
        )
    }

    // Refresh the list when we get here so newly-created sessions show up.
    LaunchedEffect(state.explorerSession) { refreshTick++ }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

@Composable
private fun SessionRow(
    strings: Strings,
    summary: SessionSummary,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    summary.session.targetPackage.ifBlank { strings.sessionsUnknownPackage },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(summary.dir.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatTimestamp(summary.session.startedAt), style = MaterialTheme.typography.bodySmall)
                Text(
                    strings.sessionsStatesTransitionsFmt.format(
                        summary.session.states.size,
                        summary.session.transitions.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpen) { Text(strings.open) }
            OutlinedButton(onClick = onExport) { Text(strings.exportLabel) }
            OutlinedButton(onClick = onDelete) {
                Text(strings.delete, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(epochMs))

private fun pickImportZip(title: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.file = "*.zip"
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir, file)
}

private fun pickExportZip(title: String, suggestedName: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
    dialog.file = "$suggestedName.zip"
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    val name = if (file.endsWith(".zip", ignoreCase = true)) file else "$file.zip"
    return File(dir, name)
}
