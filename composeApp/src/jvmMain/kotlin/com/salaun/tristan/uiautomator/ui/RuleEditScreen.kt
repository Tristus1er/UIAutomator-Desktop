package com.salaun.tristan.uiautomator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.salaun.tristan.uiautomator.AppState
import com.salaun.tristan.uiautomator.Screen
import com.salaun.tristan.uiautomator.explorer.StateOps
import com.salaun.tristan.uiautomator.i18n.LocalStrings
import com.salaun.tristan.uiautomator.i18n.Strings
import com.salaun.tristan.uiautomator.model.UiNode
import com.salaun.tristan.uiautomator.rules.ElementSelector
import com.salaun.tristan.uiautomator.rules.MatchMode
import com.salaun.tristan.uiautomator.rules.RuleAction
import com.salaun.tristan.uiautomator.rules.ScreenRule
import com.salaun.tristan.uiautomator.rules.ScreenSignature
import com.salaun.tristan.uiautomator.rules.ScrollAmount
import com.salaun.tristan.uiautomator.rules.ScrollDirection
import com.salaun.tristan.uiautomator.rules.SelectorBy
import java.util.UUID

/**
 * Fills a field from a picked node. Registered by whichever field/selector was
 * focused last, and invoked when the user clicks a node in the tree or the
 * screenshot — so "focus a field, then click an element" populates it.
 */
private typealias NodeFiller = (UiNode) -> Unit

@Composable
fun RuleEditScreen(state: AppState) {
    val strings = LocalStrings.current
    val pkg = state.ruleEditPackage
    val existing = state.editingRule

    // Local editable model, seeded once from the rule being edited (if any).
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var rootId by remember(existing) { mutableStateOf(existing?.signature?.rootId ?: "") }
    val requiredIds = remember(existing) { mutableStateListOf<String>().apply { existing?.signature?.requiredResourceIds?.let { addAll(it) } } }
    val requiredTexts = remember(existing) { mutableStateListOf<String>().apply { existing?.signature?.requiredTexts?.let { addAll(it) } } }
    val requiredDescs = remember(existing) { mutableStateListOf<String>().apply { existing?.signature?.requiredContentDescs?.let { addAll(it) } } }
    var textContains by remember(existing) { mutableStateOf((existing?.signature?.textMatch ?: MatchMode.CONTAINS) == MatchMode.CONTAINS) }
    val routine = remember(existing) { mutableStateListOf<RuleAction>().apply { existing?.routine?.let { addAll(it) } } }

    // The field that "aims" at the next node pick. Set on focus, kept until
    // another field is focused — clicking a row steals focus, so we must NOT
    // clear it on focus loss.
    var activeKey by remember { mutableStateOf<String?>(null) }
    var activeFiller by remember { mutableStateOf<NodeFiller?>(null) }
    val onActivate: (String, NodeFiller) -> Unit = { key, filler -> activeKey = key; activeFiller = filler }

    // The node committed by a deliberate CLICK. Unlike `state.selectedNode`
    // (which the XML tree updates on hover and clears the moment the pointer
    // leaves the panel), this sticks until the next click — so the signature's
    // Require id/text/desc buttons stay enabled while the user reaches for them.
    var pickedNode by remember(existing) { mutableStateOf<UiNode?>(null) }

    fun commitPick(node: UiNode) {
        pickedNode = node
        activeFiller?.invoke(node)
    }

    fun buildSignature() = ScreenSignature(
        rootId = rootId.trim().ifBlank { null },
        requiredResourceIds = requiredIds.toList(),
        requiredTexts = requiredTexts.toList(),
        requiredContentDescs = requiredDescs.toList(),
        textMatch = if (textContains) MatchMode.CONTAINS else MatchMode.EXACT,
    )

    val canSave = name.isNotBlank() && !buildSignature().isEmpty

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenToolbar(
            title = if (existing == null) strings.ruleEditTitleNew else strings.ruleEditTitleEdit,
            middle = {
                Button(onClick = { state.capture() }, enabled = !state.busy && state.adbPath.isNotBlank()) {
                    Text(strings.ruleCaptureButton)
                }
                Button(
                    enabled = canSave,
                    onClick = {
                        val now = System.currentTimeMillis()
                        val rule = ScreenRule(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            enabled = existing?.enabled ?: true,
                            signature = buildSignature(),
                            routine = routine.toList(),
                            captureScreenshotPath = existing?.captureScreenshotPath,
                            captureXmlPath = existing?.captureXmlPath,
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                        )
                        state.saveRule(pkg, rule)
                        state.openRulePackage(pkg)
                    },
                ) { Text(strings.save) }
                if (!canSave) {
                    Text(strings.ruleSaveDisabledHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            },
            nav = {
                ToolbarNavButton(strings.back, onClick = { state.openRulePackage(pkg) })
                ToolbarNavButton(strings.home, onClick = { state.go(Screen.Main) })
            },
        )

        Row(modifier = Modifier.fillMaxSize()) {
            ScreenshotPanel(
                pngBytes = state.screenshotPng,
                rootNode = state.rootNode,
                selectedNode = state.selectedNode,
                onNodeHovered = { state.selectNode(it) },
                onNodeClicked = { it?.let { n -> state.selectNode(n); commitPick(n) } },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            VerticalDivider(modifier = Modifier.fillMaxHeight())
            XmlTreePanel(
                root = state.rootNode,
                expanded = state.expanded,
                selectedNode = state.selectedNode,
                onToggle = { state.toggle(it) },
                onSelect = { state.selectNode(it) },
                onNodeClicked = { node -> commitPick(node) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onExpandedChange = { state.setExpanded(it) },
            )
            VerticalDivider(modifier = Modifier.fillMaxHeight())
            EditorColumn(
                strings = strings,
                selected = pickedNode,
                rootNode = state.rootNode,
                targetPackage = pkg,
                name = name,
                onName = { name = it },
                rootId = rootId,
                onRootId = { rootId = it },
                requiredIds = requiredIds,
                requiredTexts = requiredTexts,
                requiredDescs = requiredDescs,
                textContains = textContains,
                onTextContains = { textContains = it },
                routine = routine,
                activeKey = activeKey,
                onActivate = onActivate,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorColumn(
    strings: Strings,
    selected: UiNode?,
    rootNode: UiNode?,
    targetPackage: String,
    name: String,
    onName: (String) -> Unit,
    rootId: String,
    onRootId: (String) -> Unit,
    requiredIds: MutableList<String>,
    requiredTexts: MutableList<String>,
    requiredDescs: MutableList<String>,
    textContains: Boolean,
    onTextContains: (Boolean) -> Unit,
    routine: MutableList<RuleAction>,
    activeKey: String?,
    onActivate: (String, NodeFiller) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            label = { Text(strings.ruleNameLabel) },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(strings.ruleEditPickHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // -- Signature ---------------------------------------------------------
        Text(strings.ruleSignatureSection, style = MaterialTheme.typography.titleSmall)
        TargetableField(
            value = rootId,
            onValueChange = onRootId,
            label = "root id",
            fieldKey = "rootId",
            activeKey = activeKey,
            onActivate = onActivate,
            // A focused root-id field is filled with the picked node's id.
            filler = { node -> onRootId(node.resourceId) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                rootNode?.let { StateOps.rootScreenId(it, targetPackage)?.let(onRootId) }
            }, enabled = rootNode != null) { Text(strings.ruleSignatureUseRootId) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = textContains, onCheckedChange = onTextContains)
            Text(strings.ruleSelectorMatchContains, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
        }
        Text(
            selected?.let { "▸ ${it.label}" } ?: strings.ruleSignaturePickHint,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { selected?.resourceId?.takeIf { it.isNotBlank() }?.let { if (it !in requiredIds) requiredIds.add(it) } },
                enabled = selected?.resourceId?.isNotBlank() == true,
            ) { Text(strings.ruleSignatureAddResourceId) }
            OutlinedButton(
                onClick = { selected?.text?.takeIf { it.isNotBlank() }?.let { if (it !in requiredTexts) requiredTexts.add(it) } },
                enabled = selected?.text?.isNotBlank() == true,
            ) { Text(strings.ruleSignatureAddText) }
            OutlinedButton(
                onClick = { selected?.contentDesc?.takeIf { it.isNotBlank() }?.let { if (it !in requiredDescs) requiredDescs.add(it) } },
                enabled = selected?.contentDesc?.isNotBlank() == true,
            ) { Text(strings.ruleSignatureAddContentDesc) }
        }
        ChipList(prefix = "id", values = requiredIds)
        ChipList(prefix = "txt", values = requiredTexts)
        ChipList(prefix = "desc", values = requiredDescs)
        if (rootId.isBlank() && requiredIds.isEmpty() && requiredTexts.isEmpty() && requiredDescs.isEmpty()) {
            Text(strings.ruleSignatureEmpty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        HorizontalDivider()

        // -- Routine -----------------------------------------------------------
        Text(strings.ruleRoutineSection, style = MaterialTheme.typography.titleSmall)
        AddActionMenu(strings = strings, selected = selected, onAdd = { routine.add(it) })
        if (routine.isEmpty()) {
            Text(strings.ruleRoutineEmpty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            routine.forEachIndexed { index, action ->
                ActionCard(
                    strings = strings,
                    index = index,
                    action = action,
                    isFirst = index == 0,
                    isLast = index == routine.lastIndex,
                    activeKey = activeKey,
                    onActivate = onActivate,
                    onChange = { routine[index] = it },
                    onMoveUp = { if (index > 0) routine.add(index - 1, routine.removeAt(index)) },
                    onMoveDown = { if (index < routine.lastIndex) routine.add(index + 1, routine.removeAt(index)) },
                    onRemove = { routine.removeAt(index) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipList(prefix: String, values: MutableList<String>) {
    if (values.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.toList().forEach { v ->
            AssistChip(
                onClick = { values.remove(v) },
                label = { Text("$prefix: $v  ✕", style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun AddActionMenu(strings: Strings, selected: UiNode?, onAdd: (RuleAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { open = true }) { Text(strings.ruleAddAction + " ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(strings.ruleActionClick) }, onClick = {
                open = false
                onAdd(RuleAction.Click(selectorFrom(selected)))
            })
            DropdownMenuItem(text = { Text(strings.ruleActionTypeText) }, onClick = {
                open = false
                onAdd(RuleAction.TypeText(selectorFrom(selected), ""))
            })
            DropdownMenuItem(text = { Text(strings.ruleActionScroll) }, onClick = {
                open = false
                onAdd(RuleAction.Scroll())
            })
            DropdownMenuItem(text = { Text(strings.ruleActionWait) }, onClick = {
                open = false
                onAdd(RuleAction.Wait(1000))
            })
            DropdownMenuItem(text = { Text(strings.ruleActionBack) }, onClick = {
                open = false
                onAdd(RuleAction.Back)
            })
            DropdownMenuItem(text = { Text(strings.ruleActionCapture) }, onClick = {
                open = false
                onAdd(RuleAction.Capture)
            })
        }
    }
}

@Composable
private fun ActionCard(
    strings: Strings,
    index: Int,
    action: RuleAction,
    isFirst: Boolean,
    isLast: Boolean,
    activeKey: String?,
    onActivate: (String, NodeFiller) -> Unit,
    onChange: (RuleAction) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${index + 1}. ${action.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = onMoveUp, enabled = !isFirst) { Text(strings.ruleMoveUp) }
            OutlinedButton(onClick = onMoveDown, enabled = !isLast) { Text(strings.ruleMoveDown) }
            OutlinedButton(onClick = onRemove) { Text(strings.ruleRemoveAction, color = MaterialTheme.colorScheme.error) }
        }
        when (action) {
            is RuleAction.Click -> SelectorEditor(strings, "sel-$index", action.selector, activeKey, onActivate) { onChange(RuleAction.Click(it)) }
            is RuleAction.TypeText -> {
                SelectorEditor(strings, "sel-$index", action.selector, activeKey, onActivate) { onChange(action.copy(selector = it)) }
                OutlinedTextField(
                    value = action.text,
                    onValueChange = { onChange(action.copy(text = it)) },
                    singleLine = true,
                    label = { Text(strings.ruleTypeTextValue) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is RuleAction.Scroll -> ScrollEditor(strings, index, action, activeKey, onActivate, onChange)
            is RuleAction.Wait -> OutlinedTextField(
                value = action.ms.toString(),
                onValueChange = { v -> v.toLongOrNull()?.let { onChange(action.copy(ms = it)) } },
                singleLine = true,
                label = { Text(strings.ruleWaitMs) },
                modifier = Modifier.width(160.dp),
            )
            RuleAction.Back -> Unit
            RuleAction.Capture -> Unit
        }
    }
}

@Composable
private fun SelectorEditor(
    strings: Strings,
    fieldKey: String,
    selector: ElementSelector,
    activeKey: String?,
    onActivate: (String, NodeFiller) -> Unit,
    onChange: (ElementSelector) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Dropdown(
            label = strings.ruleSelectorBy,
            options = SelectorBy.entries.toList(),
            selectedLabel = selectorByLabel(strings, selector.by),
            optionLabel = { selectorByLabel(strings, it) },
            onSelected = {
                // Switching the kind re-derives a sensible default match mode and
                // re-registers the filler so a subsequent node pick uses the new kind.
                val match = if (it == SelectorBy.RESOURCE_ID) MatchMode.EXACT else MatchMode.CONTAINS
                onChange(selector.copy(by = it, match = match))
            },
        )
        TargetableField(
            value = selector.value,
            onValueChange = { onChange(selector.copy(value = it)) },
            label = strings.ruleSelectorValue,
            fieldKey = fieldKey,
            activeKey = activeKey,
            onActivate = onActivate,
            // The picked node fills the value with the attribute matching `by`.
            filler = { node -> onChange(selector.copy(value = attrFor(node, selector.by))) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScrollEditor(
    strings: Strings,
    index: Int,
    scroll: RuleAction.Scroll,
    activeKey: String?,
    onActivate: (String, NodeFiller) -> Unit,
    onChange: (RuleAction.Scroll) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Dropdown(
            label = strings.ruleScrollDirection,
            options = ScrollDirection.entries.toList(),
            selectedLabel = scrollDirLabel(strings, scroll.direction),
            optionLabel = { scrollDirLabel(strings, it) },
            onSelected = { onChange(scroll.copy(direction = it)) },
        )
        val amountKey = when (scroll.amount) {
            is ScrollAmount.Items -> strings.ruleScrollAmountItems
            is ScrollAmount.Percent -> strings.ruleScrollAmountPercent
            is ScrollAmount.Pixels -> strings.ruleScrollAmountPixels
            ScrollAmount.ToEnd -> strings.ruleScrollAmountToEnd
        }
        Dropdown(
            label = strings.ruleScrollAmount,
            options = listOf("items", "percent", "pixels", "toEnd"),
            selectedLabel = amountKey,
            optionLabel = { key ->
                when (key) {
                    "items" -> strings.ruleScrollAmountItems
                    "percent" -> strings.ruleScrollAmountPercent
                    "pixels" -> strings.ruleScrollAmountPixels
                    else -> strings.ruleScrollAmountToEnd
                }
            },
            onSelected = { key ->
                onChange(
                    scroll.copy(
                        amount = when (key) {
                            "items" -> ScrollAmount.Items(3)
                            "percent" -> ScrollAmount.Percent(80)
                            "pixels" -> ScrollAmount.Pixels(800)
                            else -> ScrollAmount.ToEnd
                        }
                    )
                )
            },
        )
        val amount = scroll.amount
        if (amount !is ScrollAmount.ToEnd) {
            val current = when (amount) {
                is ScrollAmount.Items -> amount.count
                is ScrollAmount.Percent -> amount.percent
                is ScrollAmount.Pixels -> amount.pixels
                ScrollAmount.ToEnd -> 0
            }
            OutlinedTextField(
                value = current.toString(),
                onValueChange = onValueChange@{ v ->
                    val n = v.toIntOrNull() ?: return@onValueChange
                    onChange(
                        scroll.copy(
                            amount = when (amount) {
                                is ScrollAmount.Items -> ScrollAmount.Items(n)
                                is ScrollAmount.Percent -> ScrollAmount.Percent(n)
                                is ScrollAmount.Pixels -> ScrollAmount.Pixels(n)
                                ScrollAmount.ToEnd -> ScrollAmount.ToEnd
                            }
                        )
                    )
                },
                singleLine = true,
                label = { Text(strings.ruleScrollValue) },
                modifier = Modifier.width(120.dp),
            )
        }
    }
}

/**
 * An [OutlinedTextField] that, while focused, becomes the target a node pick
 * fills. The label shows a ● marker when this field is the active target.
 */
@Composable
private fun TargetableField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    fieldKey: String,
    activeKey: String?,
    onActivate: (String, NodeFiller) -> Unit,
    filler: NodeFiller,
    modifier: Modifier = Modifier,
) {
    val isActive = activeKey == fieldKey
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(if (isActive) "● $label" else label) },
        modifier = modifier.onFocusChanged { if (it.isFocused) onActivate(fieldKey, filler) },
    )
}

@Composable
private fun <T> Dropdown(
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

private fun scrollDirLabel(strings: Strings, dir: ScrollDirection): String = when (dir) {
    ScrollDirection.UP -> strings.ruleScrollDirUp
    ScrollDirection.DOWN -> strings.ruleScrollDirDown
    ScrollDirection.LEFT -> strings.ruleScrollDirLeft
    ScrollDirection.RIGHT -> strings.ruleScrollDirRight
}

private fun attrFor(node: UiNode, by: SelectorBy): String = when (by) {
    SelectorBy.RESOURCE_ID -> node.resourceId
    SelectorBy.CONTENT_DESC -> node.contentDesc
    SelectorBy.TEXT -> node.text
}

/**
 * Builds a selector from a node. Defaults to addressing by resource-id (the most
 * stable handle); falls back to content-desc then text only when no id forces a
 * different kind. [forceBy] pins the kind explicitly.
 */
private fun selectorFrom(node: UiNode?, forceBy: SelectorBy? = null): ElementSelector {
    val by = forceBy ?: SelectorBy.RESOURCE_ID
    val value = node?.let { attrFor(it, by) }.orEmpty()
    val match = if (by == SelectorBy.RESOURCE_ID) MatchMode.EXACT else MatchMode.CONTAINS
    return ElementSelector(by, value, match)
}
