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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import com.salaun.tristan.uiautomator.i18n.LocalStrings
import com.salaun.tristan.uiautomator.i18n.Strings
import com.salaun.tristan.uiautomator.rules.ElementBehavior
import com.salaun.tristan.uiautomator.rules.ElementRule
import com.salaun.tristan.uiautomator.rules.ElementSelector
import com.salaun.tristan.uiautomator.rules.MatchMode
import com.salaun.tristan.uiautomator.rules.ScreenRule
import com.salaun.tristan.uiautomator.rules.SelectorBy
import java.util.UUID

@Composable
fun RulePackageScreen(state: AppState) {
    val strings = LocalStrings.current
    val pkg = state.selectedRulePackage ?: run {
        state.go(Screen.Rules)
        return
    }
    val rules = remember(state.ruleRevision, pkg) { state.rulesFor(pkg) }
    val elementRules = remember(state.ruleRevision, pkg) { state.elementRulesFor(pkg) }
    var toDelete by remember { mutableStateOf<ScreenRule?>(null) }
    var elementToDelete by remember { mutableStateOf<ElementRule?>(null) }
    var editingElement by remember { mutableStateOf<ElementRule?>(null) }
    var showElementEditor by remember { mutableStateOf(false) }

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

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            if (rules.isEmpty()) {
                item { Text(strings.rulePackageNoRules) }
            } else {
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

            item {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.elementRulesTitle, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { editingElement = null; showElementEditor = true }) {
                        Text(strings.elementRuleAdd)
                    }
                }
                Text(
                    strings.elementRulesHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (elementRules.isEmpty()) {
                item { Text(strings.elementRulesEmpty, style = MaterialTheme.typography.bodySmall) }
            } else {
                itemsIndexed(elementRules, key = { _, r -> r.id }) { _, rule ->
                    ElementRuleRow(
                        rule = rule,
                        strings = strings,
                        onOpen = { editingElement = rule; showElementEditor = true },
                        onToggle = { state.setElementRuleEnabled(pkg, rule.id, it) },
                        onDelete = { elementToDelete = rule },
                    )
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

    elementToDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { elementToDelete = null },
            title = { Text(strings.elementRuleDeleteConfirmTitle) },
            text = { Text(strings.ruleDeleteConfirmBodyFmt.format(rule.name.ifBlank { rule.selector.value })) },
            confirmButton = {
                TextButton(onClick = {
                    state.deleteElementRule(pkg, rule.id)
                    elementToDelete = null
                }) { Text(strings.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { elementToDelete = null }) { Text(strings.cancel) } },
        )
    }

    if (showElementEditor) {
        ElementRuleEditDialog(
            strings = strings,
            existing = editingElement,
            onDismiss = { showElementEditor = false },
            onSave = { rule ->
                state.saveElementRule(pkg, rule)
                showElementEditor = false
            },
        )
    }
}

/**
 * Inline editor for one [ElementRule]: a selector (how to find the element on
 * any screen of the package) and the behavior to apply to it. Kept as a
 * dialog because an element rule is two fields and a choice — far lighter
 * than the full-screen routine editor a [ScreenRule] needs.
 */
@Composable
private fun ElementRuleEditDialog(
    strings: Strings,
    existing: ElementRule?,
    onDismiss: () -> Unit,
    onSave: (ElementRule) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var by by remember { mutableStateOf(existing?.selector?.by ?: SelectorBy.RESOURCE_ID) }
    var value by remember { mutableStateOf(existing?.selector?.value.orEmpty()) }
    var match by remember { mutableStateOf(existing?.selector?.match ?: MatchMode.EXACT) }
    var behavior by remember { mutableStateOf(existing?.behavior ?: ElementBehavior.CLICK) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) strings.elementRuleEditTitleNew else strings.elementRuleEditTitleEdit) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.ruleNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SmallDropdown(
                    label = strings.ruleSelectorBy,
                    options = SelectorBy.entries,
                    selectedLabel = selectorByLabel(strings, by),
                    optionLabel = { selectorByLabel(strings, it) },
                    onSelected = { by = it },
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(strings.ruleSelectorValue) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (by != SelectorBy.RESOURCE_ID) {
                    SmallDropdown(
                        label = strings.ruleSelectorMatchContains,
                        options = MatchMode.entries,
                        selectedLabel = match.name,
                        optionLabel = { it.name },
                        onSelected = { match = it },
                    )
                }
                SmallDropdown(
                    label = strings.elementRuleBehaviorLabel,
                    options = ElementBehavior.entries,
                    selectedLabel = behaviorLabel(strings, behavior),
                    optionLabel = { behaviorLabel(strings, it) },
                    onSelected = { behavior = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = {
                    val now = System.currentTimeMillis()
                    onSave(
                        ElementRule(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name,
                            enabled = existing?.enabled ?: true,
                            selector = ElementSelector(by, value.trim(), match),
                            behavior = behavior,
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                        ),
                    )
                },
            ) { Text(strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun <T> SmallDropdown(
    label: String,
    options: List<T>,
    selectedLabel: String,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: $selectedLabel ▾", maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(optionLabel(opt)) }, onClick = {
                    open = false
                    onSelected(opt)
                })
            }
        }
    }
}

private fun selectorByLabel(strings: Strings, by: SelectorBy): String = when (by) {
    SelectorBy.RESOURCE_ID -> strings.ruleSelectorByResourceId
    SelectorBy.CONTENT_DESC -> strings.ruleSelectorByContentDesc
    SelectorBy.TEXT -> strings.ruleSelectorByText
}

private fun behaviorLabel(strings: Strings, behavior: ElementBehavior): String = when (behavior) {
    ElementBehavior.CLICK -> strings.elementRuleBehaviorClick
    ElementBehavior.LONG_PRESS -> strings.elementRuleBehaviorLongPress
    ElementBehavior.SWIPE -> strings.elementRuleBehaviorSwipe
    ElementBehavior.AVOID -> strings.elementRuleBehaviorAvoid
}

@Composable
private fun ElementRuleRow(
    rule: ElementRule,
    strings: Strings,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
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
            Text(rule.name.ifBlank { rule.selector.value }, style = MaterialTheme.typography.titleSmall)
            Text(
                "${behaviorLabel(strings, rule.behavior)} · ${rule.selector.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            Text(strings.ruleEnabledLabel, style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = onDelete) { Text(strings.delete, color = MaterialTheme.colorScheme.error) }
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
