package com.salaun.tristan.uiautomator.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data model for *custom screen rules*: app-specific routines that the explorer
 * applies with priority when it recognises a particular screen, instead of
 * walking it with generic heuristics. The motivating case is an onboarding that
 * blocks until the user scrolls to the bottom and taps "Accept" — a sequence no
 * heuristic discovers, but which a rule can encode as
 * `Scroll(DOWN, ToEnd)` then `Click(text "Accept")`.
 *
 * The sealed [RuleAction] / [ScrollAmount] hierarchies serialise via
 * kotlinx.serialization's default class-discriminator (`"type"`). Each subclass
 * carries a short, stable [SerialName] so the on-disk JSON stays readable and
 * survives class renames. None of the data fields is named `type`, so there is
 * no collision with the discriminator. The whole model is read/written by the
 * shared `Json { ignoreUnknownKeys = true; encodeDefaults = true }` instance.
 */

/** How an [ElementSelector] addresses a UI node. */
@Serializable
enum class SelectorBy { RESOURCE_ID, CONTENT_DESC, TEXT }

/** Exact equality vs. case-insensitive substring, for text/content-desc matching. */
@Serializable
enum class MatchMode { EXACT, CONTAINS }

/**
 * Addresses a UI node on the live screen at execution time. A resource-id may
 * be given in full (`pkg:id/foo`) or short (`foo`); text / content-desc obey
 * [match].
 */
@Serializable
data class ElementSelector(
    val by: SelectorBy,
    val value: String,
    val match: MatchMode = MatchMode.EXACT,
) {
    val label: String
        get() = "${by.name.lowercase()}=\"$value\"" + if (match == MatchMode.CONTAINS) " (contains)" else ""
}

/** Direction a [RuleAction.Scroll] gesture travels. */
@Serializable
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** How far one [RuleAction.Scroll] travels. Exactly one interpretation is active. */
@Serializable
sealed class ScrollAmount {
    /** Roughly [count] item-heights (see [RuleAction.Scroll.itemHeightPx]). */
    @Serializable
    @SerialName("items")
    data class Items(val count: Int) : ScrollAmount()

    /** A percentage of the scrollable container's size along the scroll axis. */
    @Serializable
    @SerialName("percent")
    data class Percent(val percent: Int) : ScrollAmount()

    /** A raw number of device pixels. */
    @Serializable
    @SerialName("pixels")
    data class Pixels(val pixels: Int) : ScrollAmount()

    /** Swipe repeatedly until the screen stops changing (bottom / end reached). */
    @Serializable
    @SerialName("toEnd")
    data object ToEnd : ScrollAmount()
}

/** One step of a rule's routine. */
@Serializable
sealed class RuleAction {
    abstract val label: String

    @Serializable
    @SerialName("click")
    data class Click(val selector: ElementSelector) : RuleAction() {
        override val label: String get() = "Click ${selector.label}"
    }

    @Serializable
    @SerialName("typeText")
    data class TypeText(val selector: ElementSelector, val text: String) : RuleAction() {
        override val label: String get() = "Type \"$text\" into ${selector.label}"
    }

    @Serializable
    @SerialName("scroll")
    data class Scroll(
        /** `null` = the screen's largest scrollable container. */
        val selector: ElementSelector? = null,
        val direction: ScrollDirection = ScrollDirection.DOWN,
        val amount: ScrollAmount = ScrollAmount.ToEnd,
        /** Pixels per "item" when [amount] is [ScrollAmount.Items]; 0 = heuristic. */
        val itemHeightPx: Int = 0,
    ) : RuleAction() {
        override val label: String
            get() {
                val amt = when (val a = amount) {
                    is ScrollAmount.Items -> "${a.count} items"
                    is ScrollAmount.Percent -> "${a.percent}%"
                    is ScrollAmount.Pixels -> "${a.pixels}px"
                    ScrollAmount.ToEnd -> "to end"
                }
                return "Scroll ${direction.name.lowercase()} $amt"
            }
    }

    @Serializable
    @SerialName("wait")
    data class Wait(val ms: Long) : RuleAction() {
        override val label: String get() = "Wait ${ms}ms"
    }

    @Serializable
    @SerialName("back")
    data object Back : RuleAction() {
        override val label: String get() = "Press BACK"
    }

    /**
     * Snapshots the current screen as an explicit step (a new state + edge in
     * the session graph). Lets a routine surface a transient screen — a dialog,
     * a confirmation, an intermediate view — that would otherwise be invisible
     * because only the routine's final screen is normally recorded.
     */
    @Serializable
    @SerialName("capture")
    data object Capture : RuleAction() {
        override val label: String get() = "Capture screen (new step)"
    }
}

/**
 * Identifies a screen. A rule matches when **all** of the criteria that are set
 * are satisfied by the live UI tree (AND semantics). At least one criterion must
 * be present or the signature is [isEmpty] and the rule is ignored.
 *
 * [rootId] is compared against `StateOps.rootScreenId` — the stable Compose
 * testTag / view-id container that survives toggles and scrolls. The required-*
 * lists let a rule key off "a screen that contains a button labelled Accept"
 * even when there is no clean root id.
 */
@Serializable
data class ScreenSignature(
    val rootId: String? = null,
    val requiredResourceIds: List<String> = emptyList(),
    val requiredTexts: List<String> = emptyList(),
    val requiredContentDescs: List<String> = emptyList(),
    val textMatch: MatchMode = MatchMode.CONTAINS,
) {
    val isEmpty: Boolean
        get() = rootId.isNullOrBlank() &&
            requiredResourceIds.isEmpty() &&
            requiredTexts.isEmpty() &&
            requiredContentDescs.isEmpty()

    val summary: String
        get() = buildList {
            rootId?.takeIf { it.isNotBlank() }?.let { add("root=$it") }
            if (requiredResourceIds.isNotEmpty()) add("ids=${requiredResourceIds.joinToString(",")}")
            if (requiredTexts.isNotEmpty()) add("texts=${requiredTexts.joinToString(",")}")
            if (requiredContentDescs.isNotEmpty()) add("desc=${requiredContentDescs.joinToString(",")}")
        }.joinToString(" · ").ifBlank { "(empty)" }
}

/** A single screen rule: a signature plus the routine to run when it matches. */
@Serializable
data class ScreenRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val signature: ScreenSignature = ScreenSignature(),
    val routine: List<RuleAction> = emptyList(),
    /** Optional reference capture taken while editing (relative to the rules root). */
    val captureScreenshotPath: String? = null,
    val captureXmlPath: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * What the explorer does with an element matched by an [ElementRule].
 * Unlike a [ScreenRule] routine (strict pass-through that replaces generic
 * exploration of the screen), an element rule only adds or removes ONE work
 * item — the rest of the screen keeps being explored normally.
 */
@Serializable
enum class ElementBehavior {
    /**
     * Tap it like a regular clickable — even when the accessibility tree marks
     * it `clickable="false"` (Compose surfaces often swallow the flag while
     * still handling taps). This is how an otherwise-invisible interactive
     * image becomes part of the crawl.
     */
    CLICK,

    /** Long-press it (context menus, drag affordances). */
    LONG_PRESS,

    /** Page-swipe leftward on it (carousels, horizontal pagers). */
    SWIPE,

    /** Never touch it. A `skipped` transition is recorded so the graph shows it was seen. */
    AVOID,
}

/**
 * A per-element directive: when [selector] matches an element on any screen of
 * the package, apply [behavior] to it — while the generic exploration of every
 * other element continues unchanged. Complements [ScreenRule] (whole-screen
 * routines with pass-through) for the "just click / just avoid this one id"
 * cases.
 */
@Serializable
data class ElementRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val selector: ElementSelector,
    val behavior: ElementBehavior = ElementBehavior.CLICK,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val summary: String get() = "${behavior.name} · ${selector.label}"
}

/** All rules for one package. Persisted as a single JSON file per package. */
@Serializable
data class PackageRuleSet(
    val version: Int = 1,
    val packageName: String,
    val rules: MutableList<ScreenRule> = mutableListOf(),
    /** Per-element directives, applied on top of (not instead of) generic exploration. */
    val elementRules: MutableList<ElementRule> = mutableListOf(),
)
