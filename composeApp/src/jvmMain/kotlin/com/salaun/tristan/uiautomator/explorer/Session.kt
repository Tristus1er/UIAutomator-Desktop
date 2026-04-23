package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.model.UiBounds
import kotlinx.serialization.Serializable

@Serializable
data class ExplorationConfig(
    val targetPackage: String,
    val maxStates: Int = 30,
    val maxDepth: Int = 5,
    val maxClickablesPerState: Int = 12,
    val settleDelayMs: Long = 900,
    val relaunchBetweenActions: Boolean = true,
)

@Serializable
data class ClickableRef(
    val resourceId: String,
    val className: String,
    val text: String,
    val contentDesc: String,
    val bounds: SerialBounds,
    val tapX: Int,
    val tapY: Int,
) {
    val label: String get() {
        val cls = className.substringAfterLast('.').ifBlank { className }
        val id = resourceId.substringAfterLast('/').ifBlank { "" }
        val txt = text.ifBlank { contentDesc }
        return buildString {
            append(cls)
            if (id.isNotBlank()) append("#").append(id)
            if (txt.isNotBlank()) append(" \"").append(txt.take(30)).append('"')
        }
    }
}

@Serializable
data class SerialBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        fun from(b: UiBounds) = SerialBounds(b.left, b.top, b.right, b.bottom)
    }
}

@Serializable
data class StateEntry(
    val id: String,
    val fingerprint: String,
    val packageName: String,
    val depth: Int,
    val screenshotPath: String,
    val xmlPath: String,
    val clickables: List<ClickableRef>,
    val pathFromRoot: List<ClickableRef>,
)

@Serializable
data class TransitionEntry(
    val from: String,
    val to: String?,
    val action: ClickableRef,
    val leftApp: Boolean = false,
    val loop: Boolean = false,
)

@Serializable
data class ExplorationSession(
    val version: Int = 1,
    val id: String,
    val targetPackage: String,
    val startedAt: Long,
    var endedAt: Long? = null,
    val config: ExplorationConfig,
    val states: MutableList<StateEntry> = mutableListOf(),
    val transitions: MutableList<TransitionEntry> = mutableListOf(),
)
