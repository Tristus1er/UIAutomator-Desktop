package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.model.UiBounds
import kotlinx.serialization.Serializable

/**
 * What the explorer does with a clickable whose label / id marks it as
 * *destructive* (logout, delete, reset, purchase, call… — see
 * [com.salaun.tristan.uiautomator.explorer.StateOps.isLikelyDestructive]).
 * One mis-tap on "Sign out" can strand every screen behind the login gate for
 * the rest of the crawl, so the default is the safest policy.
 */
@Serializable
enum class DestructivePolicy {
    /** Never tap it. A `skipped` transition is recorded so the graph shows it was seen. */
    SKIP,

    /** Tap it, but only after every other element of the screen is exhausted. */
    LAST,

    /**
     * Tap it last; if the tap lands on a *new* screen (almost always a
     * confirmation dialog), that screen is captured but immediately marked
     * exhausted so its confirm button is never pressed — the crawler captures
     * the dialog then backs away.
     */
    CONFIRM_ABORT,

    /** Legacy behavior: treat it like any other clickable. */
    TAP,
}

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
    /**
     * Upper bound on how long the explorer waits out a *long-running operation*
     * screen — a firmware update, a download, a "connecting / please wait"
     * spinner — before giving up and proceeding with whatever is on screen.
     * Such screens routinely outlast [idleMaxWaitMs] by minutes (a first-time
     * Hub firmware flash, for instance). The explorer detects them and polls
     * until the app moves on, capped here; it exits early the moment the
     * operation finishes, so a generous cap only costs time when something is
     * genuinely stuck. 0 disables the extended wait.
     */
    val longOperationMaxWaitMs: Long = 300_000,
    /** Policy applied to destructive-looking clickables (logout / delete / buy / call…). */
    val destructivePolicy: DestructivePolicy = DestructivePolicy.SKIP,
    /**
     * When `true`, exported activities declared in the package manifest that
     * the UI crawl never reached are launched directly (`am start -n`) at the
     * end of the exploration and explored from there. This reaches screens
     * only accessible through deep links or conditional flows, and makes the
     * coverage report meaningful.
     */
    val launchUnvisitedActivities: Boolean = true,
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
    /**
     * The gesture this ref represents: [GESTURE_TAP] (default — a plain tap),
     * [GESTURE_LONG_PRESS] (a long press on a `long-clickable` node, which
     * opens context menus invisible to taps), or [GESTURE_SWIPE_LEFT] (a
     * forward page-swipe on a horizontal pager / carousel — the only way to
     * advance swipe-only onboardings that expose no Next button). Default
     * keeps older serialised sessions readable as-is.
     */
    val gesture: String = GESTURE_TAP,
) {
    val label: String get() {
        val cls = className.substringAfterLast('.').ifBlank { className }
        val id = resourceId.substringAfterLast('/').ifBlank { "" }
        val txt = text.ifBlank { contentDesc }
        return buildString {
            when (gesture) {
                GESTURE_LONG_PRESS -> append("⊙ ")
                GESTURE_SWIPE_LEFT -> append("⇆ ")
            }
            append(cls)
            if (id.isNotBlank()) append("#").append(id)
            if (txt.isNotBlank()) append(" \"").append(txt.take(30)).append('"')
        }
    }

    companion object {
        const val GESTURE_TAP = "tap"
        const val GESTURE_LONG_PRESS = "longPress"
        const val GESTURE_SWIPE_LEFT = "swipeLeft"
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
    /**
     * Optional stitched scroll capture of the whole screen (the same
     * scroll-and-stitch pass as the manual mode's « Capture scroll »), taken
     * when the screen scrolled during exploration. The graph cards keep
     * showing [screenshotPath]; the detail window offers this full view.
     * `null` for non-scrolling screens and for sessions recorded before the
     * feature existed.
     */
    val scrollScreenshotPath: String? = null,
    val xmlPath: String,
    val clickables: List<ClickableRef>,
    val pathFromRoot: List<ClickableRef>,
    /**
     * Fully-qualified name of the Activity that hosted this screen when it was
     * captured (from `dumpsys activity`), or `null` when unavailable. Serves
     * as a strong extra identity signal during state dedup and feeds the
     * end-of-run activity coverage report. Default keeps old sessions readable.
     */
    val activity: String? = null,
    /**
     * Component (`pkg/cls`) this state was reached through when it was opened
     * directly with `am start -n` (manifest-coverage phase) rather than by
     * tapping through the UI. Recovery for this state and its descendants
     * re-launches this component instead of replaying taps from the app root.
     */
    val directLaunchComponent: String? = null,
)

@Serializable
data class TransitionEntry(
    val from: String,
    val to: String?,
    val action: ClickableRef,
    val leftApp: Boolean = false,
    val loop: Boolean = false,
    val errorMessage: String? = null,
    /** The action crashed the app (crash dialog detected, or a FATAL EXCEPTION in logcat). */
    val crashed: Boolean = false,
    /** The action was deliberately NOT performed ([DestructivePolicy.SKIP] on a destructive element). */
    val skipped: Boolean = false,
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
