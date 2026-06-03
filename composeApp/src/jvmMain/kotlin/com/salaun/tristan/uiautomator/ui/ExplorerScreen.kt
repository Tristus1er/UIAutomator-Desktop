package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import com.salaun.tristan.uiautomator.i18n.LocalStrings

@Composable
fun ExplorerScreen(state: AppState) {
    val strings = LocalStrings.current
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = strings.explorerTitle,
            nav = {
                ToolbarNavButton(strings.home, onClick = { state.go(Screen.Main) })
                ToolbarNavButton(
                    strings.explorerViewGraph,
                    onClick = { state.openGraphForCurrentSession() },
                    enabled = state.explorerSession != null && !state.explorerRunning,
                )
            },
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

        ConfigRow(state)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !state.explorerRunning && state.adbPath.isNotBlank(),
                onClick = { state.startExploration() },
            ) { Text(strings.explorerStart) }
            OutlinedButton(
                enabled = state.explorerRunning,
                onClick = { state.stopExploration() },
            ) { Text(strings.explorerStop) }
            if (state.explorerRunning) {
                // size(18.dp) — using only width() leaves the default ~40dp
                // height, so the indicator spun inside a deformed rectangle.
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        state.explorerProgress?.let { p ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val frac = if (p.plannedActions > 0) p.processedActions.toFloat() / p.plannedActions else 0f
                LinearProgressIndicator(
                    progress = { frac.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val base = strings.explorerProgressFmt.format(p.discoveredStates, p.processedActions, p.plannedActions)
                val stateTail = p.currentStateId?.let { " • $it" } ?: ""
                val actionTail = p.currentActionLabel?.let { " → $it" } ?: ""
                Text(
                    base + stateTail + actionTail,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        Row(modifier = Modifier.weight(1f)) {
            LogPanel(state, modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 8.dp))
            VerticalDivider(modifier = Modifier.fillMaxHeight())
            SummaryPanel(state, modifier = Modifier.width(360.dp).fillMaxHeight().padding(start = 8.dp))
        }
        }
    }
}

@Composable
private fun ConfigRow(state: AppState) {
    val strings = LocalStrings.current
    val cfg = state.explorerConfig
    var pkg by remember(cfg.targetPackage) { mutableStateOf(cfg.targetPackage) }
    var maxStates by remember(cfg.maxStates) { mutableStateOf(cfg.maxStates.toString()) }
    var maxDepth by remember(cfg.maxDepth) { mutableStateOf(cfg.maxDepth.toString()) }
    var maxClicks by remember(cfg.maxClickablesPerState) { mutableStateOf(cfg.maxClickablesPerState.toString()) }
    var settle by remember(cfg.settleDelayMs) { mutableStateOf(cfg.settleDelayMs.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = pkg,
                onValueChange = {
                    pkg = it
                    state.updateExplorerConfig { copy(targetPackage = it.trim()) }
                },
                label = { Text(strings.explorerTargetPackage) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                val suggested = state.suggestTargetPackage()
                if (suggested.isNotBlank()) {
                    pkg = suggested
                    state.updateExplorerConfig { copy(targetPackage = suggested) }
                }
            }) { Text(strings.explorerFromCapture) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField(strings.explorerMaxStates, maxStates, { maxStates = it }) { v ->
                state.updateExplorerConfig { copy(maxStates = v.coerceAtLeast(1)) }
            }
            NumField(strings.explorerMaxDepth, maxDepth, { maxDepth = it }) { v ->
                state.updateExplorerConfig { copy(maxDepth = v.coerceAtLeast(0)) }
            }
            NumField(strings.explorerMaxActions, maxClicks, { maxClicks = it }) { v ->
                state.updateExplorerConfig { copy(maxClickablesPerState = v.coerceAtLeast(1)) }
            }
            NumField(strings.explorerDelay, settle, { settle = it }) { v ->
                state.updateExplorerConfig { copy(settleDelayMs = v.coerceAtLeast(0).toLong()) }
            }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, onType: (String) -> Unit, onCommit: (Int) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = {
            onType(it)
            it.toIntOrNull()?.let(onCommit)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.width(150.dp),
    )
}

@Composable
private fun LogPanel(state: AppState, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    // Snapshot the live SnapshotStateList into a plain list at every
    // recomposition: the LazyColumn's measure pass then reads this immutable
    // copy instead of racing the explorer coroutine that appends new lines.
    // This sidesteps the "Index 0, size 0" crash observed when the list was
    // mutated from the IO dispatcher while Compose was measuring.
    val logs: List<String> = state.explorerLog.toList()
    val listState = rememberLazyListState()
    // User-controlled toggle: when ON, every new log line pins the viewport to
    // the last entry; when OFF, scroll position is left entirely to the user.
    var autoScroll by remember { mutableStateOf(true) }

    LaunchedEffect(logs.size, autoScroll) {
        if (logs.isEmpty() || !autoScroll) return@LaunchedEffect
        listState.animateScrollToItem(logs.size - 1)
    }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.explorerLog, style = MaterialTheme.typography.titleSmall)
            Box(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoScroll, onCheckedChange = { autoScroll = it })
                Text(strings.explorerAutoScroll, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = { copyTextToClipboard(logs.joinToString("\n")) },
                enabled = logs.isNotEmpty(),
            ) { Text(strings.explorerCopyLog) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(6.dp),
        ) {
            // Note: no SelectionContainer around the LazyColumn — that combo
            // is flaky in Compose Desktop 1.10 when the backing list mutates
            // at measure time. Users can copy the full log via the button
            // above; it's the reliable path.
            if (logs.isEmpty()) {
                Text(
                    "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                ) {
                    itemsIndexed(logs, key = { i, _ -> i }) { _, line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(listState),
            )
        }
    }
}

@Composable
private fun SummaryPanel(state: AppState, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val session = state.explorerSession
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings.explorerSummary, style = MaterialTheme.typography.titleSmall)
        if (session == null) {
            Text(strings.explorerNoSession, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("${strings.explorerPackagePrefix}${session.targetPackage}", style = MaterialTheme.typography.bodySmall)
            Text("${strings.explorerStatesPrefix}${session.states.size}", style = MaterialTheme.typography.bodySmall)
            Text("${strings.explorerTransitionsPrefix}${session.transitions.size}", style = MaterialTheme.typography.bodySmall)
            state.explorerStore?.let {
                Text(strings.explorerDirectoryPrefix, style = MaterialTheme.typography.bodySmall)
                Text(
                    it.baseDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
