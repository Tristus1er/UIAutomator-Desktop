package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import com.salaun.tristan.uiautomator.rules.ScreenRule
import com.salaun.tristan.uiautomator.i18n.LocalStrings

@Composable
fun RulePackageScreen(state: AppState) {
    val strings = LocalStrings.current
    val pkg = state.selectedRulePackage ?: run {
        state.go(Screen.Rules)
        return
    }
    val rules = remember(state.ruleRevision, pkg) { state.rulesFor(pkg) }
    var toDelete by remember { mutableStateOf<ScreenRule?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = strings.rulePackageTitleFmt.format(pkg),
            middle = {
                Button(onClick = { state.startNewRule(pkg) }) { Text(strings.ruleAddScreen) }
            },
            nav = {
                ToolbarNavButton(strings.back, onClick = { state.go(Screen.Rules) })
                ToolbarNavButton(strings.home, onClick = { state.go(Screen.Main) })
            },
        )

        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (rules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(strings.rulePackageNoRules) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rules, key = { _, r -> r.id }) { index, rule ->
                        RuleRow(
                            rule = rule,
                            isFirst = index == 0,
                            isLast = index == rules.lastIndex,
                            actionsFmt = strings.ruleActionsCountFmt,
                            enabledLabel = strings.ruleEnabledLabel,
                            upLabel = strings.ruleMoveUp,
                            downLabel = strings.ruleMoveDown,
                            deleteLabel = strings.delete,
                            onOpen = { state.editRule(pkg, rule) },
                            onToggle = { state.setRuleEnabled(pkg, rule.id, it) },
                            onUp = { state.moveRule(pkg, rule.id, -1) },
                            onDown = { state.moveRule(pkg, rule.id, +1) },
                            onDelete = { toDelete = rule },
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(strings.ruleDeleteConfirmTitle) },
            text = { Text(strings.ruleDeleteConfirmBodyFmt.format(rule.name)) },
            confirmButton = {
                TextButton(onClick = {
                    state.deleteRule(pkg, rule.id)
                    toDelete = null
                }) { Text(strings.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun RuleRow(
    rule: ScreenRule,
    isFirst: Boolean,
    isLast: Boolean,
    actionsFmt: String,
    enabledLabel: String,
    upLabel: String,
    downLabel: String,
    deleteLabel: String,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f).clickable { onOpen() }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(rule.name.ifBlank { rule.id.take(8) }, style = MaterialTheme.typography.titleSmall)
            Text(
                rule.signature.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                actionsFmt.format(rule.routine.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OutlinedButton(onClick = onUp, enabled = !isFirst) { Text(upLabel) }
            OutlinedButton(onClick = onDown, enabled = !isLast) { Text(downLabel) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            Text(enabledLabel, style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = onDelete) { Text(deleteLabel, color = MaterialTheme.colorScheme.error) }
    }
}
