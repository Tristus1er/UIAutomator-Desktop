package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import com.salaun.tristan.uiautomator.explorer.ExplorationSession
import com.salaun.tristan.uiautomator.explorer.SessionStore
import com.salaun.tristan.uiautomator.explorer.StateEntry
import com.salaun.tristan.uiautomator.explorer.TransitionEntry
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.max

private const val CARD_W = 220
private const val CARD_H = 170
private const val COL_STEP = 280
private const val ROW_STEP = 200
private const val MARGIN = 32

@Composable
fun GraphScreen(state: AppState) {
    val session = state.explorerSession
    val store = state.explorerStore

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Graphe d'exploration", style = MaterialTheme.typography.titleLarge)
            Box(Modifier.weight(1f))
            session?.let {
                Text(
                    "${it.states.size} états · ${it.transitions.size} transitions",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = { state.screen = Screen.Explorer }) { Text("← Explorer") }
            OutlinedButton(onClick = { state.screen = Screen.Main }) { Text("Accueil") }
        }
        androidx.compose.material3.HorizontalDivider()

        if (session == null || store == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune session chargée. Lancez une exploration.")
            }
        } else {
            var selectedId by remember(session.id) {
                mutableStateOf<String?>(session.states.firstOrNull()?.id)
            }
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    GraphCanvas(session, store, selectedId) { id -> selectedId = id }
                }
                VerticalDivider(modifier = Modifier.fillMaxHeight())
                DetailsPanel(
                    session = session,
                    store = store,
                    selectedId = selectedId,
                    onSelectState = { selectedId = it },
                    modifier = Modifier.width(420.dp).fillMaxHeight(),
                )
            }
        }
    }
}

private data class Layout(
    val positions: Map<String, Offset>,
    val totalW: Int,
    val totalH: Int,
)

private fun computeLayout(session: ExplorationSession): Layout {
    val byDepth = session.states.groupBy { it.depth }.toSortedMap()
    val positions = HashMap<String, Offset>()
    var maxCol = 0
    var maxRow = 0
    for ((depth, states) in byDepth) {
        states.forEachIndexed { index, s ->
            val x = MARGIN + depth * COL_STEP
            val y = MARGIN + index * ROW_STEP
            positions[s.id] = Offset(x.toFloat(), y.toFloat())
            if (depth > maxCol) maxCol = depth
            if (index > maxRow) maxRow = index
        }
    }
    val w = MARGIN + (maxCol + 1) * COL_STEP + CARD_W
    val h = MARGIN + (maxRow + 1) * ROW_STEP + CARD_H
    return Layout(positions, w, h)
}

@Composable
private fun GraphCanvas(
    session: ExplorationSession,
    store: SessionStore,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val layout = remember(session) { computeLayout(session) }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(hScroll)
            .verticalScroll(vScroll),
    ) {
        Box(modifier = Modifier.size(layout.totalW.dp, layout.totalH.dp)) {
            EdgeCanvas(session, layout, selectedId)
            for (stateEntry in session.states) {
                val pos = layout.positions[stateEntry.id] ?: continue
                StateCard(
                    stateEntry = stateEntry,
                    store = store,
                    isSelected = stateEntry.id == selectedId,
                    modifier = Modifier
                        .offset(pos.x.dp, pos.y.dp)
                        .size(CARD_W.dp, CARD_H.dp),
                    onClick = { onSelect(stateEntry.id) },
                )
            }
        }
    }
}

@Composable
private fun EdgeCanvas(session: ExplorationSession, layout: Layout, selectedId: String?) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    Canvas(modifier = Modifier.fillMaxSize()) {
        for (t in session.transitions) {
            val from = layout.positions[t.from] ?: continue
            val to = t.to?.let { layout.positions[it] }
            val start = Offset(from.x + CARD_W, from.y + CARD_H / 2f)
            val end = when {
                to != null -> Offset(to.x, to.y + CARD_H / 2f)
                else -> Offset(from.x + CARD_W + 40f, from.y + CARD_H / 2f + 40f)
            }
            val highlight = selectedId != null && (t.from == selectedId || t.to == selectedId)
            val color = when {
                t.leftApp -> errorColor
                t.loop -> onSurface
                highlight -> primary
                else -> onSurface.copy(alpha = 0.6f)
            }
            val path = Path().apply {
                moveTo(start.x, start.y)
                val dx = (end.x - start.x) / 2f
                cubicTo(start.x + dx, start.y, end.x - dx, end.y, end.x, end.y)
            }
            drawPath(path, color = color, style = Stroke(width = if (highlight) 2.5f else 1.5f))
            if (t.to != null) {
                val arrow = Path().apply {
                    moveTo(end.x, end.y)
                    lineTo(end.x - 8f, end.y - 5f)
                    lineTo(end.x - 8f, end.y + 5f)
                    close()
                }
                drawPath(arrow, color = color)
            }
        }
    }
}

@Composable
private fun StateCard(
    stateEntry: StateEntry,
    store: SessionStore,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bitmap = remember(stateEntry.id, stateEntry.screenshotPath) {
        loadThumbnail(store, stateEntry.screenshotPath)
    }
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stateEntry.id, style = MaterialTheme.typography.titleSmall)
            Text("d=${stateEntry.depth}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("· ${stateEntry.clickables.size} actions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(110.dp).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text("—", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            stateEntry.packageName.ifBlank { "?" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun DetailsPanel(
    session: ExplorationSession,
    store: SessionStore,
    selectedId: String?,
    onSelectState: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = session.states.firstOrNull { it.id == selectedId }
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected == null) {
            Text("Sélectionnez un état.")
            return@Column
        }

        Text(selected.id, style = MaterialTheme.typography.titleMedium)
        Text("Package : ${selected.packageName}", style = MaterialTheme.typography.bodySmall)
        Text("Empreinte : ${selected.fingerprint.take(12)}…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text("Profondeur : ${selected.depth}", style = MaterialTheme.typography.bodySmall)

        val bmp = remember(selected.id) { loadFullImage(store, selected.screenshotPath) }
        Box(
            modifier = Modifier.fillMaxWidth().height(260.dp).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text("Capture indisponible")
            }
        }

        Text("Transitions sortantes :", style = MaterialTheme.typography.titleSmall)
        val outgoing = session.transitions.filter { it.from == selected.id }
        if (outgoing.isEmpty()) {
            Text("—", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(outgoing) { t -> TransitionRow(t) { if (it != null) onSelectState(it) } }
            }
        }
    }
}

@Composable
private fun TransitionRow(t: TransitionEntry, onSelectTarget: (String?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelectTarget(t.to) }.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val target = when {
            t.leftApp -> "(hors app)"
            t.loop -> "↺ ${t.to}"
            t.to != null -> "→ ${t.to}"
            else -> "?"
        }
        Text(target, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(80.dp))
        Text(
            t.action.label,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun loadThumbnail(store: SessionStore, path: String): ImageBitmap? {
    return runCatching {
        val bytes = store.readScreenshot(path) ?: return null
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

private fun loadFullImage(store: SessionStore, path: String): ImageBitmap? = loadThumbnail(store, path)
