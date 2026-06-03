package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import com.salaun.tristan.uiautomator.i18n.LocalStrings
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.min

@Composable
fun ManualExplorerScreen(state: AppState) {
    val strings = LocalStrings.current
    val sessionStarted = state.manualSession != null && state.manualScreenshotPng != null
    val scrollMode = state.manualScrollCapture != null

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = strings.manualTitle,
            middle = {
                if (!sessionStarted) {
                    Button(
                        onClick = { state.startManualExploration() },
                        enabled = !state.manualBusy && state.adbPath.isNotBlank(),
                    ) { Text(strings.manualStart) }
                } else {
                    OutlinedButton(onClick = { state.manualPressBack() }, enabled = !state.manualBusy) { Text("◀ " + strings.manualBack) }
                    OutlinedButton(onClick = { state.manualRelaunch() }, enabled = !state.manualBusy) { Text("⌂ " + strings.manualHome) }
                    OutlinedButton(onClick = { state.manualRecapture() }, enabled = !state.manualBusy) { Text("⟳ " + strings.manualRefresh) }
                    if (scrollMode) {
                        OutlinedButton(onClick = { state.manualExitScrollMode() }, enabled = !state.manualBusy) { Text(strings.manualScrollExit) }
                    } else {
                        OutlinedButton(onClick = { state.manualCaptureScrollable() }, enabled = !state.manualBusy) { Text("↕ " + strings.manualScrollCapture) }
                    }
                    Button(onClick = { state.endManualExploration() }, enabled = !state.manualBusy) { Text(strings.manualEnd) }
                }
                if (state.manualBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                val sess = state.manualSession
                val current = state.manualCurrentStateId
                if (sess != null && current != null) {
                    Text(
                        strings.manualCurrentStatePrefix.format(current) +
                            "  ·  ${sess.states.size} états · ${sess.transitions.size} transitions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            nav = {
                ToolbarNavButton(strings.home, onClick = { state.go(Screen.Main) })
                ToolbarNavButton(strings.toolbarSessions, onClick = { state.go(Screen.Sessions) })
            },
        )

        // Body --------------------------------------------------------------
        Row(modifier = Modifier.fillMaxSize()) {
            val stitched = state.manualScrollCapture
            if (stitched != null) {
                ManualScrollCapturePanel(
                    stitched = stitched,
                    onTap = { vx, vy -> state.manualTapVirtual(vx, vy) },
                    enabled = sessionStarted && !state.manualBusy,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            } else {
                ManualScreenshotPanel(
                    pngBytes = state.manualScreenshotPng,
                    onTap = { x, y -> state.manualTap(x, y) },
                    enabled = sessionStarted && !state.manualBusy,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            VerticalDivider()
            ManualSidePanel(
                state = state,
                // Fixed width: without it, the inner LazyColumn / log Box use
                // `fillMaxWidth()` which makes the unweighted side panel grow
                // to fill all the Row width during the first measure pass —
                // leaving zero pixels for the weighted screenshot panel and
                // making the screenshot appear "missing".
                modifier = Modifier.width(360.dp).fillMaxHeight().padding(8.dp),
            )
        }
    }
}

/**
 * Screenshot panel with a single role: forward clicks to the device. The
 * image is fitted into the available area; click coordinates are translated
 * back to device-pixel space using the same scale factor.
 */
@Composable
private fun ManualScreenshotPanel(
    pngBytes: ByteArray?,
    onTap: (x: Int, y: Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val bitmap: ImageBitmap? = remember(pngBytes) {
        pngBytes?.let { runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text(
                strings.manualEmptyHint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@Box
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val boxWpx = with(density) { maxWidth.toPx() }
            val boxHpx = with(density) { maxHeight.toPx() }
            val imgWpx = bitmap.width.toFloat()
            val imgHpx = bitmap.height.toFloat()
            val scale = if (imgWpx <= 0 || imgHpx <= 0) 1f else min(boxWpx / imgWpx, boxHpx / imgHpx)
            val drawnWdp = with(density) { (imgWpx * scale).toDp() }
            val drawnHdp = with(density) { (imgHpx * scale).toDp() }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(drawnWdp, drawnHdp)
                    .pointerInput(enabled, scale) {
                        if (!enabled) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.type == PointerEventType.Release) {
                                    val pos = ev.changes.firstOrNull()?.position ?: continue
                                    if (scale > 0f) {
                                        val devX = (pos.x / scale).toInt()
                                        val devY = (pos.y / scale).toInt()
                                        onTap(devX, devY)
                                    }
                                }
                            }
                        }
                    },
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Device screenshot",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Stitched-scroll panel: scales the (potentially very tall) virtual image to
 * fit the panel width, then makes it vertically scrollable. Taps are reported
 * in virtual device pixels, which the manual explorer routes back into
 * (frame, real coords) before sending to the device.
 */
@Composable
private fun ManualScrollCapturePanel(
    stitched: com.salaun.tristan.uiautomator.explorer.ScrollCapture.Stitched,
    onTap: (vx: Int, vy: Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val bitmap: ImageBitmap? = remember(stitched) {
        runCatching { SkiaImage.makeFromEncoded(stitched.pngBytes).toComposeImageBitmap() }.getOrNull()
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        // Top strip: hint + badge so the user understands the mode and can
        // see how many frames were stitched at a glance.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.manualScrollHint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(
                strings.manualScrollBadgeFmt.format(stitched.frames.size, stitched.virtualHeight),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        if (bitmap == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(strings.manualEmptyHint, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
            val density = LocalDensity.current
            val boxWpx = with(density) { maxWidth.toPx() }
            val imgWpx = bitmap.width.toFloat()
            val imgHpx = bitmap.height.toFloat()
            // Fit-to-width: vertical scroll handles the overflowing height.
            val scale = if (imgWpx <= 0) 1f else boxWpx / imgWpx
            val drawnWdp = with(density) { (imgWpx * scale).toDp() }
            val drawnHdp = with(density) { (imgHpx * scale).toDp() }

            val vScroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize().verticalScroll(vScroll)) {
                Box(
                    modifier = Modifier
                        .size(drawnWdp, drawnHdp)
                        .pointerInput(enabled, scale) {
                            if (!enabled) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (ev.type == PointerEventType.Release) {
                                        val pos = ev.changes.firstOrNull()?.position ?: continue
                                        if (scale > 0f) {
                                            val vx = (pos.x / scale).toInt()
                                            val vy = (pos.y / scale).toInt()
                                            onTap(vx, vy)
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Stitched scroll capture",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualSidePanel(state: AppState, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(strings.manualClickHint, style = MaterialTheme.typography.bodySmall)
        state.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // States list -------------------------------------------------------
        val sess = state.manualSession
        if (sess != null && sess.states.isNotEmpty()) {
            Text(
                "${sess.states.size} état(s) · ${sess.transitions.size} transition(s)",
                style = MaterialTheme.typography.titleSmall,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(0.4f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(sess.states) { st ->
                    val isCurrent = st.id == state.manualCurrentStateId
                    Text(
                        text = "${if (isCurrent) "▶ " else "  "}${st.id} · fp=${st.fingerprint.take(8)} · ${st.clickables.size} clickable(s)",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            HorizontalDivider()
        }

        // Log ---------------------------------------------------------------
        Text(strings.manualLogTitle, style = MaterialTheme.typography.titleSmall)
        val logScroll = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(6.dp),
        ) {
            SelectionContainer {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(logScroll)) {
                    val snapshot = state.manualLog.toList()
                    for (line in snapshot) {
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

