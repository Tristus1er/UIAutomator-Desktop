package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.i18n.LocalStrings
import com.salaun.tristan.uiautomator.model.UiBounds
import com.salaun.tristan.uiautomator.model.UiNode
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.min

@Composable
fun ScreenshotPanel(
    pngBytes: ByteArray?,
    rootNode: UiNode?,
    selectedNode: UiNode?,
    onNodeHovered: (UiNode?) -> Unit,
    onNodeClicked: (UiNode?) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Rectangles drawn permanently on top of the screenshot. Used by the
     * enlarged-capture window to highlight every clickable element.
     */
    clickableBounds: List<UiBounds> = emptyList(),
) {
    val bitmap: ImageBitmap? = remember(pngBytes) {
        pngBytes?.let {
            runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text(
                LocalStrings.current.screenshotHint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            val drawnWpx = imgWpx * scale
            val drawnHpx = imgHpx * scale
            val drawnWdp = with(density) { drawnWpx.toDp() }
            val drawnHdp = with(density) { drawnHpx.toDp() }

            var lastHovered by remember { mutableStateOf<UiNode?>(null) }
            // When the user clicks an element we "pin" the selection: further
            // mouse moves inside the screenshot are ignored, which gives them
            // a chance to look up the node in the XML tree on the side without
            // losing the highlight. Pointer exit releases the pin.
            var pinned by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(drawnWdp, drawnHdp)
                    .pointerInput(rootNode, scale) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pos = ev.changes.firstOrNull()?.position
                                when (ev.type) {
                                    PointerEventType.Move, PointerEventType.Enter -> {
                                        if (!pinned) {
                                            val node = pos?.let { hitTest(rootNode, it, scale) }
                                            if (node !== lastHovered) {
                                                lastHovered = node
                                                onNodeHovered(node)
                                            }
                                        }
                                    }
                                    PointerEventType.Exit -> {
                                        // Leaving the screenshot releases any pin. If nothing
                                        // was pinned we also clear the hover selection so the
                                        // caller knows the pointer has gone elsewhere.
                                        if (pinned) {
                                            pinned = false
                                        } else if (lastHovered != null) {
                                            lastHovered = null
                                            onNodeHovered(null)
                                        }
                                    }
                                    PointerEventType.Press -> {
                                        val node = pos?.let { hitTest(rootNode, it, scale) }
                                        pinned = node != null
                                        // Update lastHovered too so re-entry over the same
                                        // node does not re-fire onNodeHovered redundantly.
                                        lastHovered = node
                                        onNodeClicked(node)
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Screenshot",
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Permanent "clickable" overlays (drawn first so the red selection overlay sits on top).
                    for (b in clickableBounds) {
                        val x = b.left * scale
                        val y = b.top * scale
                        val w = b.width * scale
                        val h = b.height * scale
                        drawRect(
                            color = Color(0x332196F3),
                            topLeft = Offset(x, y),
                            size = Size(w, h),
                        )
                        drawRect(
                            color = Color(0xFF1976D2),
                            topLeft = Offset(x, y),
                            size = Size(w, h),
                            style = Stroke(width = 1.5f),
                        )
                    }
                    selectedNode?.bounds?.let { b ->
                        val x = b.left * scale
                        val y = b.top * scale
                        val w = b.width * scale
                        val h = b.height * scale
                        drawRect(
                            color = Color(0x33FF0000),
                            topLeft = Offset(x, y),
                            size = Size(w, h),
                        )
                        drawRect(
                            color = Color.Red,
                            topLeft = Offset(x, y),
                            size = Size(w, h),
                            style = Stroke(width = 2f),
                        )
                    }
                }
            }
        }
    }
}

private fun hitTest(root: UiNode?, pos: Offset, scale: Float): UiNode? {
    if (root == null || scale <= 0f) return null
    val devX = (pos.x / scale).toInt()
    val devY = (pos.y / scale).toInt()
    return root.findSmallestAt(devX, devY)
}
