package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import com.salaun.tristan.uiautomator.explorer.SessionStore
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
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = strings.sessionsTitle,
            middle = {
                OutlinedButton(onClick = { refreshTick++ }) { Text(strings.sessionsRefresh) }
                OutlinedButton(onClick = {
                    val picked = pickImportZip(strings.sessionsImportDialogTitle)
                    if (picked != null) {
                        state.importSession(picked, openAfter = true)
                        refreshTick++
                    }
                }) { Text(strings.importLabel) }
            },
            nav = { ToolbarNavButton(strings.home, onClick = { state.go(Screen.Main) }) },
        )
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

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
    // A single fixed-height row: the texts and action buttons live in the left
    // column, so the thumbnails can span the FULL height of the card.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(THUMB_ROW_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    summary.session.targetPackage.ifBlank { strings.sessionsUnknownPackage },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(summary.dir.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text(strings.open) }
                OutlinedButton(onClick = onExport) { Text(strings.exportLabel) }
                OutlinedButton(onClick = onDelete) {
                    Text(strings.delete, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        SessionThumbnails(summary, modifier = Modifier.weight(1f))
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
}

/** Height of a session card — the thumbnails stretch to fill all of it. */
private val THUMB_ROW_HEIGHT = 180.dp

/**
 * Pixel height thumbnails are decoded at. ~2× the displayed dp height, so the
 * image stays crisp on HiDPI screens while a full 1080×2400 capture (~10 MB of
 * ARGB once decoded) shrinks to a few hundred KB in memory.
 */
private const val THUMB_DECODE_HEIGHT_PX = 384

/** Portrait-phone aspect ratio used for the placeholder while a thumb decodes. */
private const val THUMB_PLACEHOLDER_RATIO = 0.46f

/**
 * Shows the first 1-3 captured screens of a session as thumbnails between the
 * session's metadata columns. Each thumbnail fills the row height (keeping its
 * aspect ratio) and is decoded lazily: off the UI thread, downscaled to
 * display size, and only while its row is composed — scrolled-away rows are
 * disposed by the LazyColumn, releasing their bitmaps. The count adapts to the
 * width actually available (via [BoxWithConstraints]) so narrow windows simply
 * show fewer.
 */
@Composable
private fun SessionThumbnails(summary: SessionSummary, modifier: Modifier = Modifier) {
    val store = remember(summary.dir.absolutePath) { SessionStore(summary.dir) }
    BoxWithConstraints(modifier = modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        // A portrait thumb at full card height is ~ratio*height wide (+ gap);
        // adapt the count to the width actually available, never more than 3.
        val cellWidth = THUMB_ROW_HEIGHT * THUMB_PLACEHOLDER_RATIO + 6.dp
        val maxCount = (maxWidth / cellWidth).toInt().coerceIn(0, 3)
        if (maxCount == 0) return@BoxWithConstraints
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxHeight()) {
            summary.session.states.take(maxCount).forEach { st ->
                LazyThumbnail(
                    store = store,
                    dirKey = summary.dir.absolutePath,
                    path = st.screenshotPath,
                    contentDescription = st.id,
                )
            }
        }
    }
}

/**
 * One asynchronously-decoded thumbnail: a neutral placeholder is shown while
 * the PNG is read and downscaled on [Dispatchers.IO], then the image fades in
 * at full row height with its real aspect ratio.
 */
@Composable
private fun LazyThumbnail(
    store: SessionStore,
    dirKey: String,
    path: String,
    contentDescription: String,
) {
    var bmp by remember(dirKey, path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(dirKey, path) {
        bmp = withContext(Dispatchers.IO) { loadScaledThumbnail(store, path, THUMB_DECODE_HEIGHT_PX) }
    }
    val image = bmp
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(image.width.toFloat() / image.height.toFloat())
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(THUMB_PLACEHOLDER_RATIO)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
    }
}

/**
 * Decodes the screenshot at [path] and downscales it to [targetHeightPx]
 * (bilinear). Returns the full-size bitmap only when it is already smaller.
 * Keeping the decode here — instead of reusing the full-size
 * [loadThumbnail] — is what bounds the session list's memory to a few
 * hundred KB per visible row instead of ~30 MB.
 */
private fun loadScaledThumbnail(store: SessionStore, path: String, targetHeightPx: Int): ImageBitmap? =
    runCatching {
        val bytes = store.readScreenshot(path) ?: return null
        val full = javax.imageio.ImageIO.read(bytes.inputStream()) ?: return null
        if (full.height <= targetHeightPx) return full.toComposeImageBitmap()
        val w = (full.width.toLong() * targetHeightPx / full.height).toInt().coerceAtLeast(1)
        val scaled = java.awt.image.BufferedImage(w, targetHeightPx, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(full, 0, 0, w, targetHeightPx, null)
        } finally {
            g.dispose()
        }
        scaled.toComposeImageBitmap()
    }.getOrNull()

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
