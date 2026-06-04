package com.salaun.tristan.uiautomator.rules

import com.salaun.tristan.uiautomator.adb.AdbGateway
import com.salaun.tristan.uiautomator.explorer.ScrollCapture
import com.salaun.tristan.uiautomator.explorer.StateOps
import com.salaun.tristan.uiautomator.model.DumpParser
import com.salaun.tristan.uiautomator.model.UiBounds
import com.salaun.tristan.uiautomator.model.UiNode
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * Matches custom [ScreenRule]s against a live UI tree and executes their
 * routines on the device. Used by the explorer to apply app-specific
 * behaviours (scroll-to-bottom-then-accept onboardings, multi-step gates) with
 * priority over its generic heuristics.
 *
 * Matching is pure ([matches]); execution drives an [AdbGateway]. The scroll
 * geometry is factored into the pure, device-free [computeSwipes] so it can be
 * unit-tested without a phone.
 */
class RuleEngine(private val ruleStore: RuleStore) {

    /** Enabled, non-empty rules for [pkg], in priority order (first match wins). */
    fun rulesFor(pkg: String): List<ScreenRule> =
        ruleStore.load(pkg).rules.filter { it.enabled && !it.signature.isEmpty }

    /** First rule in [rules] whose signature matches [root], or null. */
    fun matchRule(root: UiNode, pkg: String, rules: List<ScreenRule>): ScreenRule? =
        rules.firstOrNull { matches(it.signature, root, pkg) }

    /** True when every set criterion of [sig] is satisfied by [root]. */
    fun matches(sig: ScreenSignature, root: UiNode, pkg: String): Boolean {
        if (sig.isEmpty) return false

        val ids = HashSet<String>()
        val texts = ArrayList<String>()
        val descs = ArrayList<String>()
        root.walk { n ->
            if (n.resourceId.isNotBlank()) ids += n.resourceId
            if (n.text.isNotBlank()) texts += n.text
            if (n.contentDesc.isNotBlank()) descs += n.contentDesc
        }
        // rootId is satisfied when it IS the screen's detected root container
        // (StateOps.rootScreenId) OR simply present somewhere in the tree. Many
        // apps wrap every screen in one shared full-screen container (e.g. a
        // Compose `root_app`), so the per-screen container id is rarely the one
        // rootScreenId returns; matching on its mere presence keeps the rule
        // usable while still distinguishing screens that don't carry that id.
        val rootReq = sig.rootId?.trim()
        if (!rootReq.isNullOrBlank()) {
            val isContainer = StateOps.rootScreenId(root, pkg)?.let { idHit(it, rootReq) } ?: false
            if (!isContainer && ids.none { idHit(it, rootReq) }) return false
        }
        if (sig.requiredResourceIds.any { req -> ids.none { idHit(it, req) } }) return false
        if (sig.requiredTexts.any { req -> texts.none { textHit(it, req, sig.textMatch) } }) return false
        if (sig.requiredContentDescs.any { req -> descs.none { textHit(it, req, sig.textMatch) } }) return false
        return true
    }

    /**
     * Runs [rule]'s routine on the live device, re-reading the screen before
     * each action so selectors resolve against what is actually displayed.
     */
    suspend fun execute(
        rule: ScreenRule,
        adb: AdbGateway,
        serial: String?,
        settleDelayMs: Long,
        /**
         * Invoked for each [RuleAction.Capture] in the routine. The caller
         * (the explorer) snapshots the live screen and registers it as a step.
         */
        onCaptureStep: suspend () -> Unit = {},
        log: (String) -> Unit = {},
    ): RuleOutcome {
        rule.routine.forEachIndexed { index, action ->
            // A Capture needs no selector resolution and must not dump twice —
            // it just hands control back so the explorer can register a step.
            if (action is RuleAction.Capture) {
                log("capture step")
                onCaptureStep()
                delay(settleDelayMs)
                return@forEachIndexed
            }
            val root = runCatching { dump(adb, serial) }.getOrNull()
                ?: return RuleOutcome.Aborted(index, "could not read the screen")
            when (action) {
                is RuleAction.Click -> {
                    val node = resolve(root, action.selector)
                        ?: return RuleOutcome.Aborted(index, "element not found: ${action.selector.label}")
                    val b = node.bounds ?: return RuleOutcome.Aborted(index, "element has no bounds")
                    log("click ${action.selector.label} @ (${b.centerX}, ${b.centerY})")
                    adb.inputTap(serial, b.centerX, b.centerY)
                }
                is RuleAction.TypeText -> {
                    val node = resolve(root, action.selector)
                        ?: return RuleOutcome.Aborted(index, "field not found: ${action.selector.label}")
                    val b = node.bounds ?: return RuleOutcome.Aborted(index, "field has no bounds")
                    log("type \"${action.text}\" into ${action.selector.label}")
                    adb.inputTap(serial, b.centerX, b.centerY)
                    delay(settleDelayMs)
                    adb.inputText(serial, action.text)
                }
                is RuleAction.Scroll -> {
                    val container = action.selector?.let { resolve(root, it) }
                        ?: ScrollCapture.findScrollable(root)
                    val bounds = container?.bounds
                        ?: return RuleOutcome.Aborted(index, "no scrollable container found")
                    log("scroll ${action.label}")
                    executeScroll(adb, serial, bounds, action, settleDelayMs)
                }
                is RuleAction.Wait -> {
                    log("wait ${action.ms}ms")
                    delay(action.ms)
                }
                RuleAction.Back -> {
                    log("press BACK")
                    adb.pressBack(serial)
                }
                RuleAction.Capture -> Unit // handled before the dump above
            }
            delay(settleDelayMs)
        }
        return RuleOutcome.Completed(rule.routine.size)
    }

    private suspend fun executeScroll(
        adb: AdbGateway,
        serial: String?,
        bounds: UiBounds,
        scroll: RuleAction.Scroll,
        settleDelayMs: Long,
    ) {
        if (scroll.amount is ScrollAmount.ToEnd) {
            // Swipe one ~70% page at a time until the screen stops changing.
            var before = runCatching { StateOps.fingerprint(dump(adb, serial)) }.getOrNull()
            repeat(SCROLL_TO_END_MAX_SWIPES) {
                val sw = pageSwipe(bounds, scroll.direction, TO_END_PAGE_PERCENT)
                adb.inputSwipe(serial, sw.x1, sw.y1, sw.x2, sw.y2, sw.durationMs)
                delay(settleDelayMs)
                val after = runCatching { StateOps.fingerprint(dump(adb, serial)) }.getOrNull()
                if (after != null && after == before) return
                before = after
            }
            return
        }
        for (sw in computeSwipes(bounds, scroll.direction, scroll.amount, scroll.itemHeightPx)) {
            adb.inputSwipe(serial, sw.x1, sw.y1, sw.x2, sw.y2, sw.durationMs)
            delay(settleDelayMs)
        }
    }

    private fun resolve(root: UiNode, selector: ElementSelector): UiNode? {
        var best: UiNode? = null
        root.walk { n ->
            if (best != null) return@walk
            val b = n.bounds
            if (b == null || b.width <= 0 || b.height <= 0) return@walk
            val hit = when (selector.by) {
                SelectorBy.RESOURCE_ID -> n.resourceId.isNotBlank() && idHit(n.resourceId, selector.value)
                SelectorBy.CONTENT_DESC -> n.contentDesc.isNotBlank() && textHit(n.contentDesc, selector.value, selector.match)
                SelectorBy.TEXT -> n.text.isNotBlank() && textHit(n.text, selector.value, selector.match)
            }
            if (hit) best = n
        }
        return best
    }

    private suspend fun dump(adb: AdbGateway, serial: String?): UiNode {
        val xml = adb.dumpUiXml(serial)
        return DumpParser.parse(xml) ?: error("invalid UIAutomator dump")
    }

    companion object {
        /** Hard cap on swipes a single Scroll(ToEnd) issues before giving up. */
        const val SCROLL_TO_END_MAX_SWIPES = 30

        /** Page fraction one Scroll(ToEnd) iteration travels. */
        const val TO_END_PAGE_PERCENT = 70

        /** Gesture duration, matching the explorer's own scroll harvest. */
        const val SWIPE_DURATION_MS = 350

        /** Fraction of the container a single swipe may span (avoid fling overshoot). */
        private const val MAX_TRAVEL_FRACTION = 0.9

        /** Default items-per-screen heuristic when no itemHeightPx is given. */
        private const val DEFAULT_VISIBLE_ITEMS = 10

        // Both sides are trimmed so a stray newline / space picked up when a
        // value was pasted into the editor (e.g. "\ntag_…_button_agree") still
        // matches the clean id / text on the live screen.
        private fun idHit(actual: String, required: String): Boolean {
            val a = actual.trim()
            val r = required.trim()
            return a == r || a.substringAfterLast('/') == r
        }

        private fun textHit(actual: String, required: String, mode: MatchMode): Boolean {
            val a = actual.trim()
            val r = required.trim()
            return when (mode) {
                MatchMode.EXACT -> a == r
                MatchMode.CONTAINS -> a.contains(r, ignoreCase = true)
            }
        }

        /**
         * Translates a bounded scroll [amount] over a container into one or more
         * device swipes. Pure and device-free so the geometry can be unit-tested.
         * A single swipe never spans more than [MAX_TRAVEL_FRACTION] of the
         * container along the scroll axis; longer travels split into equal swipes.
         * [ScrollAmount.ToEnd] is handled by the caller's loop, not here, and
         * yields an empty list.
         */
        fun computeSwipes(
            bounds: UiBounds,
            direction: ScrollDirection,
            amount: ScrollAmount,
            itemHeightPx: Int = 0,
        ): List<Swipe> {
            val vertical = direction == ScrollDirection.UP || direction == ScrollDirection.DOWN
            val span = if (vertical) bounds.height else bounds.width
            if (span <= 0) return emptyList()
            val maxTravel = (span * MAX_TRAVEL_FRACTION).toInt().coerceAtLeast(1)
            val total = when (amount) {
                is ScrollAmount.Percent -> span * amount.percent / 100
                is ScrollAmount.Pixels -> amount.pixels
                is ScrollAmount.Items -> {
                    val itemPx = if (itemHeightPx > 0) itemHeightPx else (span / DEFAULT_VISIBLE_ITEMS).coerceAtLeast(1)
                    amount.count * itemPx
                }
                ScrollAmount.ToEnd -> 0
            }
            if (total <= 0) return emptyList()
            val swipeCount = ceil(total.toDouble() / maxTravel).toInt().coerceAtLeast(1)
            val perSwipe = (total / swipeCount).coerceAtLeast(1)
            return List(swipeCount) { directionalSwipe(bounds, direction, perSwipe) }
        }

        /** A single ~[percent]%-of-container page swipe (used by Scroll(ToEnd)). */
        private fun pageSwipe(bounds: UiBounds, direction: ScrollDirection, percent: Int): Swipe {
            val vertical = direction == ScrollDirection.UP || direction == ScrollDirection.DOWN
            val span = if (vertical) bounds.height else bounds.width
            val travel = (span * percent / 100).coerceAtLeast(1)
            return directionalSwipe(bounds, direction, travel)
        }

        /**
         * Builds one swipe travelling [travel] px along [direction] centred on
         * [bounds]. DOWN/RIGHT reveal content further along, so the finger moves
         * *against* that direction (Android's natural scroll); UP/LEFT reverse it.
         */
        private fun directionalSwipe(bounds: UiBounds, direction: ScrollDirection, travel: Int): Swipe {
            val cx = bounds.centerX
            val cy = bounds.centerY
            val half = travel / 2
            return when (direction) {
                ScrollDirection.DOWN -> Swipe(cx, cy + half, cx, cy - half, SWIPE_DURATION_MS)
                ScrollDirection.UP -> Swipe(cx, cy - half, cx, cy + half, SWIPE_DURATION_MS)
                ScrollDirection.RIGHT -> Swipe(cx + half, cy, cx - half, cy, SWIPE_DURATION_MS)
                ScrollDirection.LEFT -> Swipe(cx - half, cy, cx + half, cy, SWIPE_DURATION_MS)
            }
        }
    }
}

/** A single swipe gesture in device pixels. */
data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int)

/** Result of running a rule routine. */
sealed class RuleOutcome {
    data class Completed(val actionsRun: Int) : RuleOutcome()
    data class Aborted(val atAction: Int, val reason: String) : RuleOutcome()
}

private val UiBounds.centerX: Int get() = (left + right) / 2
private val UiBounds.centerY: Int get() = (top + bottom) / 2
