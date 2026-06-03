package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.model.UiBounds
import kotlinx.serialization.Serializable

@Serializable
data class ExplorationConfig(
    val targetPackage: String,
    /**
     * Hard ceiling on the number of distinct states registered. Raised from
     * the original 30 to 200: fingerprint dedup already terminates the crawl
     * when no new screen appears, so this is a safety bound, not the normal
     * stopping point — a low value would truncate a thorough exploration.
     */
    val maxStates: Int = 200,
    /**
     * Maximum *branch* depth (see Explorer.branchDepth). Unlike a raw path
     * length, a linear chain of single-exit screens (a multi-step onboarding /
     * wizard) does NOT consume this budget — only screens that actually fork
     * the navigation do. Raised from 5 so a long onboarding can't starve the
     * real feature tree behind it.
     */
    val maxDepth: Int = 25,
    /** Per-state cap on exercised clickables (after sibling-group collapsing). */
    val maxClickablesPerState: Int = 25,
    /**
     * Upper bound on the number of forward scroll steps taken per screen while
     * harvesting off-screen clickables. 0 disables in-screen scrolling.
     */
    val maxScrollFrames: Int = 6,
    /**
     * When `true`, empty editable text fields on a screen are auto-filled with
     * sensible defaults before its buttons are exercised, so forms / login
     * gates that block on empty input can be walked through.
     */
    val fillTextFields: Boolean = true,
    /** Initial debounce after an action; the screen-idle polling runs on top of this. */
    val settleDelayMs: Long = 500,
    /** Upper bound for the active "wait until two screenshots match" loop. */
    val idleMaxWaitMs: Long = 8_000,
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
    /**
     * Number of UI siblings sharing this clickable's parent + class + resource-id
     * at collect time. A picker / list / grid produces a large group (e.g. 24
     * hour cells); a wizard's "Next" button stands alone (group size 1). The
     * explorer uses this signal to detect that a tap stays on the same logical
     * screen (selection-only change) and avoid registering each cell as a new
     * state. Default 1 keeps the field backward-compatible with sessions
     * persisted before the picker-detection feature was introduced.
     */
    val siblingGroupSize: Int = 1,
    /**
     * `true` when this clickable lives inside a wheel-style picker
     * (NumberPicker / DatePicker / TimePicker). Wheel pickers cycle their
     * visible labels on every tap without ever leaving the host screen, so
     * the explorer treats taps on these nodes as self-loops without probing
     * each rotation as a fresh state — that single optimisation prevents the
     * 24-state explosion observed on the Laqi onboarding hour picker.
     * Default `false` keeps older serialised sessions readable as-is.
     */
    val insideWheelPicker: Boolean = false,
    /**
     * Number of forward swipes on the screen's main scrollable container needed
     * to bring this clickable into view before it can be tapped. 0 means it is
     * visible without scrolling. Set by the explorer's in-screen scroll pass so
     * that off-screen elements (and the path replay that revisits them) scroll
     * first, then tap. Default 0 keeps older serialised sessions valid.
     */
    val scrollToReveal: Int = 0,
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
    val errorMessage: String? = null,
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
