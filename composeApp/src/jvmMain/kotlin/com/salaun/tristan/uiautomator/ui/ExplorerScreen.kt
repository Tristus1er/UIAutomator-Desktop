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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
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

@Composable
fun ExplorerScreen(state: AppState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Exploration automatique", style = MaterialTheme.typography.headlineSmall)
            Box(Modifier.weight(1f))
            OutlinedButton(onClick = { state.screen = Screen.Main }) { Text("← Retour") }
            OutlinedButton(
                enabled = state.explorerSession != null && !state.explorerRunning,
                onClick = { state.openGraphForCurrentSession() },
            ) { Text("Voir le graphe") }
        }

        ConfigRow(state)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !state.explorerRunning && state.adbPath.isNotBlank(),
                onClick = { state.startExploration() },
            ) { Text("Démarrer l'exploration") }
            OutlinedButton(
                enabled = state.explorerRunning,
                onClick = { state.stopExploration() },
            ) { Text("Arrêter") }
            if (state.explorerRunning) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
            }
        }

        state.explorerProgress?.let { p ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val frac = if (p.plannedActions > 0) p.processedActions.toFloat() / p.plannedActions else 0f
                LinearProgressIndicator(
                    progress = { frac.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "États : ${p.discoveredStates} • Actions : ${p.processedActions}/${p.plannedActions}" +
                        (p.currentStateId?.let { " • $it" } ?: "") +
                        (p.currentActionLabel?.let { " → $it" } ?: ""),
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

@Composable
private fun ConfigRow(state: AppState) {
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
                label = { Text("Package cible") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                val suggested = state.suggestTargetPackage()
                if (suggested.isNotBlank()) {
                    pkg = suggested
                    state.updateExplorerConfig { copy(targetPackage = suggested) }
                }
            }) { Text("Depuis la capture") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Max états", maxStates, { maxStates = it }) { v ->
                state.updateExplorerConfig { copy(maxStates = v.coerceAtLeast(1)) }
            }
            NumField("Max profondeur", maxDepth, { maxDepth = it }) { v ->
                state.updateExplorerConfig { copy(maxDepth = v.coerceAtLeast(0)) }
            }
            NumField("Max actions/état", maxClicks, { maxClicks = it }) { v ->
                state.updateExplorerConfig { copy(maxClickablesPerState = v.coerceAtLeast(1)) }
            }
            NumField("Attente (ms)", settle, { settle = it }) { v ->
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
    val logs = state.explorerLog
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    Column(modifier = modifier) {
        Text("Log", style = MaterialTheme.typography.titleSmall)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
        ) {
            items(logs) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun SummaryPanel(state: AppState, modifier: Modifier = Modifier) {
    val session = state.explorerSession
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Résumé", style = MaterialTheme.typography.titleSmall)
        if (session == null) {
            Text("Aucune session en cours.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Package : ${session.targetPackage}", style = MaterialTheme.typography.bodySmall)
            Text("États : ${session.states.size}", style = MaterialTheme.typography.bodySmall)
            Text("Transitions : ${session.transitions.size}", style = MaterialTheme.typography.bodySmall)
            state.explorerStore?.let {
                Text("Répertoire :", style = MaterialTheme.typography.bodySmall)
                Text(
                    it.baseDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
