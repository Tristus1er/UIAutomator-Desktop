package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.adb.AdbGateway
import com.salaun.tristan.uiautomator.model.DumpParser
import com.salaun.tristan.uiautomator.model.UiNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class ExplorerProgress(
    val discoveredStates: Int,
    val processedActions: Int,
    val plannedActions: Int,
    val currentStateId: String?,
    val currentActionLabel: String?,
)

/**
 * Iterative breadth-first crawler.
 *
 * Each state's clickables are exercised in one pass, with a BACK between
 * siblings to keep the device on the source. Newly-discovered child states
 * are pushed onto a FIFO queue so the explorer fans out across siblings of
 * the root before diving into descendants — this prevents one greedy branch
 * (a NumberPicker, a multi-page wizard) from exhausting the state budget
 * before the user's other entry points are even visited.
 *
 * Three exception paths short-circuit the naive "tap and capture" loop:
 *  - **Wheel pickers** (NumberPicker / DatePicker / TimePicker): every
 *    visible cell is collapsed into a single self-loop without an actual
 *    tap, because by construction those widgets cycle their visible labels
 *    rather than navigating away. A 24-cell hour picker now consumes one
 *    transition instead of 24 spurious states.
 *  - **System permission dialogs**: a screen on
 *    `com.android.permissioncontroller` (or its Google variant) — whether it
 *    appears at launch, after a tap, or after a BACK — is auto-granted by
 *    clicking the most permissive Allow-style button (never a deny button,
 *    even though "Don't allow" contains the substring "allow"). The dialog
 *    itself is registered as a captured state and the grant recorded as a
 *    transition, so the permission is visible in the session; the crawler
 *    then continues against the screen behind the gate instead of recording
 *    `leftApp` and giving up.
 *  - **Picker-like selection changes**: when the post-tap screen has the
 *    same structure fingerprint as the source and the click came from a
 *    sibling group of [pickerSiblingThreshold]+ cells, we record a
 *    self-loop and remember the post-tap fingerprint as still-pointing-at
 *    the source so that future captures don't trigger an expensive
 *    relaunch+replay recovery.
 *
 * Drifts (BACK landing us on an unexpected screen, taps leaving the target
 * package without going through a permission dialog, capture failures) are
 * handled by relaunching the app and replaying the path that originally
 * led to the desired source state. A trailing fix-up pass re-attempts every
 * state still holding unexercised clickables once more before quitting.
 */
class Explorer(
    private val adb: AdbGateway,
    private val serial: String?,
    private val config: ExplorationConfig,
    private val store: SessionStore,
    /**
     * Optional custom-rule engine. When non-null and the target package has
     * rules, the explorer applies a matching rule's routine with priority over
     * its generic heuristics on every freshly-landed screen (see [tryApplyRule]).
     * Defaults to `null` so existing call sites and tests are unaffected.
     */
    private val ruleEngine: com.salaun.tristan.uiautomator.rules.RuleEngine? = null,
) {

    interface Listener {
        fun onLog(msg: String)
        fun onProgress(progress: ExplorerProgress)
        fun onSessionUpdated(session: ExplorationSession)
    }

    private data class Snapshot(
        val png: ByteArray,
        val xml: String,
        val root: UiNode,
        val pkg: String,
        val fingerprint: String,
    )

    private lateinit var session: ExplorationSession
    private lateinit var listener: Listener
    private val fingerprintToId = HashMap<String, String>()
    /**
     * Maps a screen's *root container id* (e.g. a Compose `settings_screen`
     * testTag — see [StateOps.rootScreenId]) to the first state that carried it.
     * This is the stable screen identity that lets the explorer recognise the
     * same screen across toggle flips, switch checks and scroll positions
     * (which all shift the structural fingerprint) instead of duplicating it
     * into S4, S5, S6… Only screens that expose such a root id participate;
     * everything else falls back to the exact fingerprint.
     */
    private val rootIdToStateId = HashMap<String, String>()
    /**
     * Per-state structure fingerprint (text-free SHA-1 of the DOM). Used to
     * recognise "the same logical screen with a different selection" after a
     * tap on a picker / list / grid cell — see [isSelectionWithinSameScreen].
     */
    private val structureFingerprintByStateId = HashMap<String, String>()
    /**
     * Per-state set of app resource-ids. Used to reject a root-id dedup merge
     * when the candidate screen barely overlaps the known one — i.e. two
     * unrelated screens sharing a generic app-shell container (`root_app`)
     * rather than two variants of the same screen.
     */
    private val resourceIdsByStateId = HashMap<String, Set<String>>()
    private var processed = 0
    private var planned = 0

    /**
     * Id of the app's actual root state (the first non-permission screen).
     * Not necessarily `session.states.first()`: a permission prompt fired at
     * launch is captured as its own state ahead of the root, so recovery /
     * path-replay must anchor on this explicit id rather than on index 0.
     */
    private var rootStateId: String? = null

    /**
     * Threshold above which a tapped clickable's sibling group is considered
     * "list-like" (a picker, grid, or long radio group) rather than a plain
     * action button row. Below this, structure-only matches are ignored to
     * avoid mistaking a wizard's "Next" button or a 3-tab bar for a picker.
     *
     * Lowered from 5 → 3 so wheel-style pickers — which only expose three
     * visible siblings (decrement/value/increment) — also collapse into
     * self-loops via the same [isSelectionWithinSameScreen] heuristic. The
     * dedicated [insideWheelPicker] short-circuit catches the common
     * NumberPicker/DatePicker/TimePicker case earlier; this threshold
     * remains the safety net for OEM widgets that render a wheel without
     * inheriting from the standard classes.
     */
    private val pickerSiblingThreshold = 3

    suspend fun run(listener: Listener): ExplorationSession {
        this.listener = listener
        session = ExplorationSession(
            id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()),
            targetPackage = config.targetPackage,
            startedAt = System.currentTimeMillis(),
            config = config,
        )
        listener.onLog("Session: ${store.baseDir.absolutePath}")

        pkgRules = ruleEngine?.rulesFor(config.targetPackage).orEmpty()
        if (pkgRules.isNotEmpty()) {
            listener.onLog("📐 ${pkgRules.size} custom screen rule(s) loaded for ${config.targetPackage}")
        }

        // Freeze the system status bar BEFORE the first capture so the very
        // first fingerprint already reflects a stable clock / battery / signal.
        // Otherwise the explorer would naturally re-encounter "the same screen
        // but with the clock advanced one minute" and register it as a new
        // state. This is best-effort: on locked-down OEM builds the broadcast
        // is rejected, in which case we fall back on the systemui filter
        // applied inside `StateOps.fingerprint`.
        val demoOn = runCatching { adb.enterDemoMode(serial) }.getOrDefault(false)
        if (demoOn) {
            listener.onLog("✓ SystemUI demo mode enabled (clock/battery/signal frozen)")
        } else {
            listener.onLog(
                "⚠ Could not enable SystemUI demo mode — minor status-bar changes may " +
                    "split duplicate states. To allow it: " +
                    "`adb shell settings put global sysui_demo_allowed 1`"
            )
        }

        try {
            listener.onLog("Launching ${config.targetPackage}…")

            adb.launchApp(serial, config.targetPackage)
            delay(config.settleDelayMs + 500)

            var initial = capture()

            // Many apps fire a permission prompt the moment they open. Resolve
            // it before the package check so the crawler isn't aborted by a
            // `com.android.permissioncontroller` top window — the dialog is
            // captured as its own state and the auto-grant linked to the
            // screen behind it once the root is registered below.
            var launchGrant: GrantOutcome? = null
            if (initial.pkg in StateOps.PERMISSION_PACKAGES) {
                listener.onLog("⚠ Permission dialog on launch — auto-granting…")
                launchGrant = grantPermissionChain(
                    first = initial, incomingFrom = null, incomingAction = null, triggerPath = emptyList(),
                )
                if (launchGrant == null) {
                    listener.onLog("⚠ Could not resolve the launch permission dialog. Aborted.")
                    session.endedAt = System.currentTimeMillis()
                    persist()
                    return session
                }
                initial = launchGrant.behind
            }

            if (config.targetPackage.isNotBlank() && initial.pkg != config.targetPackage) {
                listener.onLog("⚠ Top package is '${initial.pkg}', not '${config.targetPackage}'. Aborted.")
                session.endedAt = System.currentTimeMillis()
                persist()
                return session
            }

            val root = registerState(initial, depth = 0, path = emptyList())
            rootStateId = root.id
            fingerprintToId[root.fingerprint] = root.id
            // Link the last launch-permission dialog to the now-registered root.
            launchGrant?.let { g ->
                session.transitions += TransitionEntry(g.lastDialogId, root.id, g.lastAllowAction)
            }
            planned += root.clickables.size
            listener.onLog("Initial state ${root.id} (${root.clickables.size} actions)")
            emit(root.id, null)
            persist()

            // Context-preserving frontier exploration: keep the app running and
            // walk it like a human until every flagged element on every
            // discovered screen has been verified. No relaunch between actions
            // (a relaunch would discard one-time onboarding context and strand
            // everything behind it) — navigation between screens uses BACK and
            // the app's own recorded edges.
            frontierExplore(root)

            val spine = detectOnboardingSpine()
            if (spine.size >= 3) {
                listener.onLog("🚀 Onboarding sequence (shown once, then never again): ${spine.joinToString(" → ")} (${spine.size} screens)")
            }

            session.endedAt = System.currentTimeMillis()
            persist()
            listener.onLog("Exploration done: ${session.states.size} states, ${session.transitions.size} transitions.")
            return session
        } finally {
            // Always restore the live status bar, even on cancellation/exception,
            // otherwise the user is stuck with a frozen clock until they reboot.
            if (demoOn) {
                runCatching { adb.exitDemoMode(serial) }
                listener.onLog("✓ SystemUI demo mode disabled")
            }
        }
    }

    /**
     * Where a single BACK press leads, learned per state. Seeded optimistically
     * when we enter a fresh child (BACK from it should return to its parent) and
     * corrected whenever a real BACK lands somewhere else. The navigation graph
     * uses these as "up" edges so the crawler can climb back to a frontier
     * screen without relaunching.
     */
    private val backLeadsTo = HashMap<String, String>()

    /** States whose text fields were filled / scrollable content harvested already. */
    private val preparedStates = HashSet<String>()

    /**
     * Structure fingerprints of wait screens that already exhausted their wait
     * budget without finishing (e.g. a Hub that never connects). We never wait
     * on them again, so a single stuck spinner can't cost the exploration its
     * timeout over and over.
     */
    private val timedOutWaitScreens = HashSet<String>()

    /**
     * States we have given up reaching from the live context (navigation and a
     * last-resort relaunch both failed). Their remaining clickables stay
     * unexercised but the frontier loop no longer selects them, so it
     * terminates instead of spinning on an unreachable screen.
     */
    private val exhaustedStates = HashSet<String>()

    /** Custom screen rules for the target package, loaded once at the start of [run]. */
    private var pkgRules: List<com.salaun.tristan.uiautomator.rules.ScreenRule> = emptyList()

    /**
     * State ids for which a custom rule has already been considered. Used only
     * as the cheap first gate so revisiting a state without the live screen
     * changing pays no extra capture.
     */
    private val ruleHandledStates = HashSet<String>()

    /**
     * Exact fingerprints of the live screens already rule-checked. This is the
     * real guard: it lets the hook re-evaluate rules whenever the *physical*
     * screen differs, even when several physically-distinct screens collapse to
     * the same state id (apps that wrap every onboarding step in one shared
     * full-screen container dedupe to a single state via its root id, which
     * would otherwise let only the first step ever be rule-checked).
     */
    private val ruleCheckedFingerprints = HashSet<String>()

    /** Fingerprint of the most recent [capture]; what the device physically shows now. */
    private var lastLiveFingerprint: String? = null

    /**
     * Ids of captured permission-dialog screens. They are **one-time**: once
     * granted, the system never shows them again, so navigation must NOT route
     * through them (their incoming/outgoing edges are stale the moment the
     * grant happens). The crawler instead uses [navShortcuts] to jump straight
     * from the triggering screen to the screen behind the gate.
     */
    private val permissionDialogStateIds = HashSet<String>()

    /**
     * Post-grant navigation shortcuts: tapping the trigger element on the
     * source screen now lands directly on the behind-the-gate screen (the
     * permission dialog no longer intervenes). Recorded when a permission is
     * resolved and used by the navigation planner in place of the stale
     * source→dialog→…→behind chain.
     */
    private val navShortcuts = mutableListOf<NavStep.Forward>()

    /** A single navigation move: a recorded forward tap, or a BACK press. */
    private sealed class NavStep {
        abstract val from: String
        abstract val to: String
        data class Forward(override val from: String, override val to: String, val action: ClickableRef) : NavStep()
        data class Back(override val from: String, override val to: String) : NavStep()
    }

    /**
     * Forward edges usable for navigation: the recorded productive transitions
     * **minus** any edge touching a one-time permission-dialog state, **plus**
     * the post-grant [navShortcuts]. This is what makes re-reaching a screen
     * behind a granted permission deterministic instead of relying on drift
     * detection.
     */
    private fun navForwardEdges(): List<NavStep.Forward> {
        val out = ArrayList<NavStep.Forward>()
        for (t in session.transitions) {
            val to = t.to ?: continue
            if (t.loop || t.leftApp || t.errorMessage != null || to == t.from) continue
            if (t.from in permissionDialogStateIds || to in permissionDialogStateIds) continue
            out += NavStep.Forward(t.from, to, t.action)
        }
        out += navShortcuts
        return out
    }

    /** BACK edges usable for navigation (excluding any touching a one-time dialog). */
    private fun navBackEdges(): List<NavStep.Back> =
        backLeadsTo.entries
            .filter { it.key !in permissionDialogStateIds && it.value !in permissionDialogStateIds }
            .map { NavStep.Back(it.key, it.value) }

    /**
     * Context-preserving frontier exploration. Keeps the app running and walks
     * it like a human: exercise an unflagged element on the current screen,
     * follow wherever it leads, and only when the current screen is exhausted
     * navigate — via BACK and the app's own recorded edges — to the nearest
     * screen that still has work. Loops until every flagged element on every
     * discovered screen has been verified (or the state cap is hit).
     */
    private suspend fun frontierExplore(root: StateEntry) {
        var current: StateEntry = root
        while (coroutineContext.isActive && session.states.size < config.maxStates) {
            current = session.states.firstOrNull { it.id == current.id } ?: current

            // 0. Custom screen rules take priority over the generic heuristics.
            //    When a rule matches the freshly-landed screen, its routine is
            //    executed and the screen is treated as fully handled (strict
            //    pass-through — its other clickables are NOT exercised). The
            //    check fires when this is a new state OR when the device's live
            //    screen (fingerprint) hasn't been rule-checked yet — the latter
            //    catches physically-distinct screens that deduped to a known id.
            if (pkgRules.isNotEmpty() &&
                (current.id !in ruleHandledStates ||
                    lastLiveFingerprint?.let { it !in ruleCheckedFingerprints } == true)
            ) {
                val landed = tryApplyRule(current)
                if (landed != null) {
                    current = landed
                    continue
                }
            }

            // 1. Anything left to try on the current screen?
            if (current.id !in exhaustedStates && branchDepth(current.id) < config.maxDepth) {
                prepareStateForExploration(current)
                current = session.states.firstOrNull { it.id == current.id } ?: current
                // Exercise the screen's real content before its back/close
                // affordance, so we don't leave a screen the instant we tap the
                // top-left back arrow and abandon its primary buttons.
                val click = current.clickables
                    .filter { !isExercised(current, it) }
                    .minByOrNull { if (StateOps.isLikelyDismissAction(it)) 1 else 0 }
                if (click != null) {
                    val landed = exerciseClickable(current, click)
                    current = landed ?: reanchor() ?: relaunchToRoot() ?: break
                    continue
                }
            }

            // 2. Current screen exhausted → navigate to the nearest screen with
            //    unflagged elements. When none remain anywhere, we're done.
            val target = nearestFrontier(current) ?: break
            val reached = navigateTo(current, target)
            if (reached != null) {
                current = reached
            } else {
                // The target can't be reached from the live context. Try a
                // single relaunch + replay; if even that fails, abandon it so
                // the loop can finish rather than spin.
                val r = runCatching { recoverTo(target, target.pathFromRoot) }.getOrDefault(false)
                if (r) {
                    current = target
                } else {
                    listener.onLog("  ✘ ${target.id} unreachable from context — skipping its remaining elements")
                    exhaustedStates += target.id
                }
            }
        }
    }

    /**
     * Applies a custom screen rule to [current] when one matches the live
     * screen. Returns the state the device ends up on after the rule's routine
     * (a new child, a known screen, or `null` to fall through to the generic
     * explorer). Strict pass-through: when the routine moves the app forward,
     * [current] is marked exhausted so its other clickables are never exercised.
     *
     * The method is gated by [ruleHandledStates] so a rule fires at most once
     * per state, and falls through (returns `null`) when the routine leaves the
     * screen unchanged — the primary guard against a rule looping forever on its
     * own screen.
     */
    private suspend fun tryApplyRule(current: StateEntry): StateEntry? {
        val engine = ruleEngine ?: return null
        // Re-dump the live screen: rule matching must see what's on screen now,
        // not the on-disk XML captured when we first landed here.
        val snap = runCatching { capture() }.getOrNull() ?: run {
            ruleHandledStates += current.id
            return null
        }
        ruleHandledStates += current.id
        // Guard on the *live* fingerprint, not the state id: a screen that
        // deduped into a known state still gets one rule evaluation here.
        if (snap.fingerprint in ruleCheckedFingerprints) return null
        ruleCheckedFingerprints += snap.fingerprint
        if (config.targetPackage.isNotBlank() && snap.pkg != config.targetPackage) return null
        val rule = engine.matchRule(snap.root, config.targetPackage, pkgRules) ?: return null

        listener.onLog("📐 rule « ${rule.name} » matches ${current.id} — applying routine (${rule.routine.size} actions)")
        val ruleAction = ClickableRef(
            resourceId = "", className = "RULE", text = rule.name, contentDesc = "",
            bounds = SerialBounds(0, 0, 0, 0), tapX = 0, tapY = 0,
        )
        // Source of the next recorded edge. Advances each time a screen is
        // registered (a Capture step, or the routine's final screen), so the
        // routine appears as a chain S_from → step1 → step2 → final instead of
        // a single edge — surfacing transient dialogs / intermediate screens.
        var stepFrom = session.states.firstOrNull { it.id == current.id } ?: current

        suspend fun registerStep(stepSnap: Snapshot): StateEntry? {
            if (config.targetPackage.isNotBlank() && stepSnap.pkg != config.targetPackage) return null
            val known = resolveKnownStateId(stepSnap)
            val st = if (known != null) {
                session.states.firstOrNull { it.id == known }
            } else {
                registerState(stepSnap, depth = stepFrom.depth + 1, path = stepFrom.pathFromRoot + ruleAction).also {
                    fingerprintToId[stepSnap.fingerprint] = it.id
                    planned += it.clickables.size
                    listener.onLog("  → new ${it.id} (${it.clickables.size} actions)")
                }
            }
            if (st != null && st.id != stepFrom.id) {
                session.transitions += TransitionEntry(stepFrom.id, st.id, ruleAction)
                backLeadsTo[st.id] = stepFrom.id
                stepFrom = st
                emit(st.id, "RULE « ${rule.name} »")
                persist()
            }
            return st
        }

        val outcome = engine.execute(
            rule, adb, serial, config.settleDelayMs,
            onCaptureStep = {
                val mid = runCatching { waitOutLongOperation(capture()) }.getOrNull()
                if (mid != null) {
                    registerStep(mid)
                    listener.onLog("    📸 capture step → ${stepFrom.id}")
                }
            },
        ) { listener.onLog("    $it") }
        when (outcome) {
            is com.salaun.tristan.uiautomator.rules.RuleOutcome.Completed ->
                listener.onLog("  ✓ rule « ${rule.name} » completed (${outcome.actionsRun} actions)")
            is com.salaun.tristan.uiautomator.rules.RuleOutcome.Aborted ->
                listener.onLog("  ⚠ rule « ${rule.name} » aborted at action ${outcome.atAction}: ${outcome.reason}")
        }

        val after = runCatching { waitOutLongOperation(capture()) }.getOrNull()
            ?: return reanchor() ?: returnToApp(current)
        val finalState = registerStep(after)

        return if (finalState != null && finalState.id != current.id) {
            // Pass-through: the routine moved the app forward, so the matched
            // screen is done — its generic clickables are not exercised.
            exhaustedStates += current.id
            finalState
        } else {
            // The routine left us on the matched screen (no Capture steps moved
            // us either). Record a self-loop and fall through to generic
            // exploration — the guard against a rule looping on its own screen.
            if (stepFrom.id == current.id) {
                session.transitions += TransitionEntry(current.id, current.id, ruleAction, loop = true)
                persist()
            }
            null
        }
    }

    /**
     * Identifies the onboarding spine: the maximal chain of screens from the
     * root where each screen leads forward to exactly one next screen, i.e. the
     * linear welcome / wizard flow the app shows once on first run and never
     * again. It ends at the first screen that forks the navigation (the real
     * "hub"). Purely informational — the frontier crawler already handles these
     * screens correctly by consuming their single action on the way down and
     * never needing to replay them.
     */
    private fun detectOnboardingSpine(): List<String> {
        val root = rootStateId ?: return emptyList()
        val forward = HashMap<String, MutableSet<String>>()
        for (t in session.transitions) {
            val to = t.to
            if (to != null && !t.loop && !t.leftApp && t.errorMessage == null && to != t.from) {
                forward.getOrPut(t.from) { HashSet() }.add(to)
            }
        }
        val spine = ArrayList<String>()
        val seen = HashSet<String>()
        var cur = root
        while (seen.add(cur)) {
            spine += cur
            val nexts = forward[cur] ?: break
            if (nexts.size != 1) break // branch point — onboarding ends here
            cur = nexts.first()
        }
        return spine
    }

    /** `true` when [s] still has unflagged, in-budget clickables worth visiting. */
    private fun hasWork(s: StateEntry): Boolean =
        s.id !in exhaustedStates &&
            branchDepth(s.id) < config.maxDepth &&
            s.clickables.any { !isExercised(s, it) }

    /**
     * One-time per state: fill empty text fields and harvest off-screen
     * clickables by scrolling, so the frontier reflects everything reachable on
     * the screen before we start tapping. The device must already be on [state].
     */
    private suspend fun prepareStateForExploration(state: StateEntry) {
        if (!preparedStates.add(state.id)) return
        fillTextFieldsOn(state)
        val harvested = harvestScrolledClickables(state)
        if (harvested.isNotEmpty()) {
            val idx = session.states.indexOfFirst { it.id == state.id }
            if (idx >= 0) {
                session.states[idx] = session.states[idx].copy(
                    clickables = session.states[idx].clickables + harvested,
                )
            }
            planned += harvested.size
            persist()
        }
    }

    /**
     * Breadth-first search over the navigation graph (recorded forward edges +
     * learned BACK edges) for the nearest reachable screen that still has work.
     * Falls back to the lowest-id frontier state when none is reachable from the
     * live context, so the caller can attempt a last-resort relaunch.
     */
    private fun nearestFrontier(from: StateEntry): StateEntry? {
        val byId = session.states.associateBy { it.id }
        val frontier = session.states.filter { hasWork(it) }
        if (frontier.isEmpty()) return null
        val adj = navAdjacency()
        val visited = HashSet<String>().apply { add(from.id) }
        val queue = ArrayDeque<String>().apply { add(from.id) }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            val st = byId[u]
            if (st != null && st.id != from.id && hasWork(st)) return st
            for (v in adj[u].orEmpty()) if (visited.add(v)) queue += v
        }
        return frontier.minByOrNull { it.id }
    }

    /** Forward + BACK adjacency for navigation BFS (one-time dialogs excluded). */
    private fun navAdjacency(): Map<String, Set<String>> {
        val adj = HashMap<String, MutableSet<String>>()
        for (e in navForwardEdges()) adj.getOrPut(e.from) { HashSet() }.add(e.to)
        for (e in navBackEdges()) adj.getOrPut(e.from) { HashSet() }.add(e.to)
        return adj
    }

    /**
     * Drives the device from [from] to [target], **re-planning after every
     * move**. Each step we compute only the *next* hop toward [target], execute
     * it, observe which known state we actually landed on, and plan again from
     * there.
     *
     * Re-planning (instead of committing to one precomputed path) is what makes
     * navigation robust to edges that have gone stale since they were recorded
     * — most importantly a permission dialog that no longer appears once
     * granted, so its trigger now lands one (or several) screens *past* the
     * dialog state in the recorded chain. The old "follow the path, fail on the
     * first mismatch" logic treated that as drift and relaunched, stranding
     * every screen behind the permission gate (this is exactly why "Start
     * detection" on the egg-config screen never got tapped). Now a drift onto a
     * known state simply re-plans from it and keeps going.
     *
     * Returns [target] on success, or `null` when a move lands on an unknown
     * screen or no path remains (the caller then falls back to a relaunch).
     */
    private suspend fun navigateTo(from: StateEntry, target: StateEntry): StateEntry? {
        var curId = from.id
        if (curId == target.id) return if (verifyOn(target)) target else null
        listener.onLog("→ ${from.id} ⇒ ${target.id}")
        val maxMoves = session.states.size + BACK_RECOVERY_ATTEMPTS + 4
        repeat(maxMoves) {
            if (curId == target.id) return if (verifyOn(target)) target else null
            val step = navPath(curId, target.id)?.firstOrNull() ?: return null
            val landed: String? = when (step) {
                is NavStep.Forward -> {
                    if (step.action.scrollToReveal > 0) scrollDownTimes(step.action.scrollToReveal)
                    runCatching { adb.inputTap(serial, step.action.tapX, step.action.tapY) }
                    delay(config.settleDelayMs)
                    val snap = runCatching { capture() }.getOrNull() ?: return null
                    resolveKnownStateId(snap)
                }
                is NavStep.Back -> climbBackTo(step)
            }
            if (landed == null) {
                listener.onLog("  ✘ nav lost on an unknown screen heading for ${target.id}")
                return null
            }
            if (step is NavStep.Forward && landed != step.to) {
                listener.onLog("  ↻ ${step.from}→${step.to} actually landed on $landed (stale edge) — re-planning")
            }
            if (landed == curId && step is NavStep.Back) return null // BACK made no progress
            curId = landed
        }
        return null
    }

    /**
     * Presses BACK up to [BACK_RECOVERY_ATTEMPTS] times and returns the known
     * state it lands on (the navigation target, or any other known screen so
     * the caller can re-plan), or `null` if it only ever lands on unknown
     * screens. Multiple presses climb past interstitials (a confirmation
     * overlay, an IME, a transient screen the app pops on its own).
     */
    private suspend fun climbBackTo(step: NavStep.Back): String? {
        repeat(BACK_RECOVERY_ATTEMPTS) {
            runCatching { adb.pressBack(serial) }
            delay(config.settleDelayMs)
            val snap = runCatching { capture() }.getOrNull() ?: return null
            val landed = resolveKnownStateId(snap)
            if (landed != null) {
                backLeadsTo[step.from] = landed
                return landed
            }
            // Unknown screen: keep pressing BACK (likely a dismissible overlay).
        }
        return null
    }

    /** Shortest navigation path [from]→[target], or `null` when none is known. */
    private fun navPath(from: String, target: String): List<NavStep>? {
        val adj = HashMap<String, MutableList<NavStep>>()
        for (e in navForwardEdges()) adj.getOrPut(e.from) { mutableListOf() } += e
        for (e in navBackEdges()) adj.getOrPut(e.from) { mutableListOf() } += e

        val prev = HashMap<String, NavStep>()
        val visited = HashSet<String>().apply { add(from) }
        val queue = ArrayDeque<String>().apply { add(from) }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            if (u == target) break
            for (step in adj[u].orEmpty()) if (visited.add(step.to)) { prev[step.to] = step; queue += step.to }
        }
        if (target !in visited) return null
        val path = ArrayList<NavStep>()
        var cur = target
        while (cur != from) {
            val step = prev[cur] ?: return null
            path += step
            cur = step.from
        }
        path.reverse()
        return path
    }

    /** Capture the current screen and return the known state it maps to, if any. */
    private suspend fun reanchor(): StateEntry? {
        val snap = runCatching { capture() }.getOrNull() ?: return null
        val id = resolveKnownStateId(snap) ?: return null
        return session.states.firstOrNull { it.id == id }
    }

    /** Last resort when the live context is lost entirely: relaunch and attach. */
    private suspend fun relaunchToRoot(): StateEntry? = runCatching {
        adb.launchApp(serial, config.targetPackage)
        waitForKnownState()
    }.getOrNull()

    /**
     * Recovers after a tap pushed us *outside* the target app. The external
     * screen has already been captured as a terminal state; now we get back in.
     * We press BACK up to [BACK_RECOVERY_ATTEMPTS] times — some external screens
     * (a browser, a file picker) are several activities deep — and the moment a
     * BACK lands on a known in-app screen we resume from there. If we still
     * can't find our way back into the app, it is force-stopped and relaunched
     * (kill + restart) and we re-attach to the root, so the crawl never stalls
     * on a foreign screen.
     */
    private suspend fun returnToApp(fallback: StateEntry): StateEntry {
        repeat(BACK_RECOVERY_ATTEMPTS) {
            runCatching { adb.pressBack(serial) }
            delay(config.settleDelayMs)
            val snap = runCatching { capture() }.getOrNull() ?: return@repeat
            // Still on a foreign screen — press BACK again.
            if (config.targetPackage.isNotBlank() && snap.pkg != config.targetPackage) return@repeat
            val known = resolveKnownStateId(snap)
                ?.let { id -> session.states.firstOrNull { it.id == id } }
            if (known != null) return known
        }
        listener.onLog("  ↻ couldn't return to ${config.targetPackage} — kill + relaunch")
        return relaunchToRoot() ?: fallback
    }

    /**
     * Taps a single [click] on [source] and returns the state the device ends
     * up on afterwards (which may be [source] itself for a self-loop, a fresh
     * child, or a previously-known screen we are now physically on). Returns
     * `null` when the action threw and left us on an unknown screen — the
     * caller re-anchors. Unlike the old BFS loop this never presses BACK to
     * "return to source": staying wherever we landed is what preserves context
     * and lets the crawl keep diving.
     */
    private suspend fun exerciseClickable(source: StateEntry, click: ClickableRef): StateEntry? {
        if (session.states.size >= config.maxStates) return source

        // Wheel-picker children only rotate labels — record a synthetic
        // self-loop without an actual tap so the picker doesn't drift.
        if (click.insideWheelPicker) {
            listener.onLog("  ↺ wheel-picker click « ${click.label} » (synthetic self-loop)")
            session.transitions += TransitionEntry(source.id, source.id, click, loop = true)
            countProcessed(source.id, click.label); persist()
            return source
        }

        listener.onLog(
            "▶ ${source.id} · tap « ${click.label} »" +
                if (click.scrollToReveal > 0) " (scroll ${click.scrollToReveal})" else "",
        )
        val after: Snapshot
        try {
            if (click.scrollToReveal > 0) scrollDownTimes(click.scrollToReveal)
            adb.inputTap(serial, click.tapX, click.tapY)
            delay(config.settleDelayMs)
            // If the tap kicked off a firmware flash / download / connecting
            // spinner, patiently wait it out so we classify the screen the app
            // moves on to — not the transient progress screen.
            after = waitOutLongOperation(capture())
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName.orEmpty()
            listener.onLog("  ✘ « ${click.label} » failed: $reason")
            session.transitions += TransitionEntry(source.id, null, click, errorMessage = reason)
            countProcessed(source.id, click.label); persist()
            return null
        }

        // System permission dialog: capture + auto-grant; continue on the
        // screen behind the gate.
        if (after.pkg in StateOps.PERMISSION_PACKAGES) {
            val grant = grantPermissionChain(after, source, click, source.pathFromRoot + click)
            countProcessed(source.id, click.label); persist()
            if (grant == null) {
                listener.onLog("  ⚠ permission dialog unresolved on ${after.pkg}")
                return reanchor() ?: returnToApp(source)
            }
            listener.onLog("  🔓 auto-granted permission(s) → behind ${grant.behind.pkg}")
            return classifyBehindPermission(source, click, grant) ?: reanchor() ?: returnToApp(source)
        }

        // Left the target app (e.g. a tap opened a web page in the browser).
        // We don't explore foreign apps, but instead of leaving a dangling
        // arrow we capture that external screen as a terminal state — so its
        // screenshot is kept and the edge points at it — then BACK out to the
        // source and carry on.
        if (config.targetPackage.isNotBlank() && after.pkg != config.targetPackage) {
            val knownId = fingerprintToId[after.fingerprint]
            val externalId = if (knownId != null) {
                knownId
            } else {
                registerExternalState(after, source.pathFromRoot + click)
                    .also { fingerprintToId[after.fingerprint] = it.id }.id
            }
            listener.onLog("  ⇗ left app (${after.pkg}) → captured as $externalId")
            session.transitions += TransitionEntry(source.id, externalId, click, leftApp = true)
            backLeadsTo[externalId] = source.id
            countProcessed(source.id, click.label); persist()
            return returnToApp(source)
        }

        val knownId = resolveKnownStateId(after)
        val result: StateEntry = when {
            knownId == source.id -> {
                listener.onLog("  ↺ self-loop")
                session.transitions += TransitionEntry(source.id, source.id, click, loop = true)
                source
            }
            knownId != null && shouldFanOutDetail(source, click, knownId, after) -> {
                // A bare media screen (video / full-bleed image) reached again
                // from the same menu via a different item: structurally identical
                // to its siblings but a distinct screenshot. Register a separate
                // state per entry so each video / image gets its own node, and
                // mark it exhausted (the layout is identical — nothing new to
                // explore). The fingerprint stays aliased to the canonical state,
                // so the next sibling fans out the same way.
                val clone = registerState(after, depth = source.depth + 1, path = source.pathFromRoot + click)
                session.transitions += TransitionEntry(source.id, clone.id, click)
                backLeadsTo[clone.id] = source.id
                exhaustedStates += clone.id
                listener.onLog("  ✶ detail fan-out → new ${clone.id} (same layout, distinct screenshot)")
                clone
            }
            knownId != null -> {
                listener.onLog("  → known state $knownId")
                session.transitions += TransitionEntry(source.id, knownId, click, loop = false)
                session.states.firstOrNull { it.id == knownId } ?: source
            }
            isSelectionWithinSameScreen(source, click, after) -> {
                listener.onLog("  ↺ same screen — selection in a list/picker (group=${click.siblingGroupSize})")
                session.transitions += TransitionEntry(source.id, source.id, click, loop = true)
                fingerprintToId[after.fingerprint] = source.id
                source
            }
            else -> {
                val child = registerState(after, depth = source.depth + 1, path = source.pathFromRoot + click)
                fingerprintToId[after.fingerprint] = child.id
                session.transitions += TransitionEntry(source.id, child.id, click)
                backLeadsTo[child.id] = source.id
                listener.onLog("  → new ${child.id} (${child.clickables.size} actions)")
                child
            }
        }
        countProcessed(source.id, click.label); persist()
        return result
    }

    /**
     * Outcome of a resolved permission chain: the [behind] snapshot is the
     * first non-permission screen reached, [lastDialogId] the state id of the
     * dialog that granted access to it, and [lastAllowAction] the button that
     * was tapped — so the caller can record `lastDialog --Allow--> behind`.
     */
    private data class GrantOutcome(
        val behind: Snapshot,
        val lastDialogId: String,
        val lastAllowAction: ClickableRef,
    )

    /**
     * Drives a (possibly multi-round) system permission dialog to completion,
     * always tapping the most permissive Allow-style affordance. Every
     * distinct dialog screen is registered as its own captured state (so the
     * permission shows up in the session graph) and each auto-Allow tap is
     * recorded as a transition between consecutive dialogs.
     *
     * [incomingFrom] / [incomingAction] wire the *first* dialog to whatever
     * triggered the permission (the source state and the tap on it). At app
     * launch both are `null` — the first dialog simply becomes a registered
     * state with no incoming edge yet (the caller links it to the root once
     * that is registered).
     *
     * Returns the [GrantOutcome] for the screen behind the last gate, or
     * `null` when an Allow button could not be located or the dialog outlived
     * the [PERMISSION_FLOW_MAX_STEPS] budget — in which case the dialog state(s)
     * already captured remain, and the caller recovers to its source.
     */
    private suspend fun grantPermissionChain(
        first: Snapshot,
        incomingFrom: StateEntry?,
        incomingAction: ClickableRef?,
        triggerPath: List<ClickableRef>,
    ): GrantOutcome? {
        var current = first
        var prevDialogId: String? = null
        var prevAllow: ClickableRef? = null
        repeat(PERMISSION_FLOW_MAX_STEPS) {
            when {
                // The screen behind the gate has been reached — done.
                current.pkg !in StateOps.PERMISSION_PACKAGES &&
                    current.pkg !in StateOps.PERMISSION_SETTINGS_PACKAGES -> {
                    val did = prevDialogId
                    val act = prevAllow
                    return if (did != null && act != null) {
                        GrantOutcome(behind = current, lastDialogId = did, lastAllowAction = act)
                    } else {
                        null
                    }
                }

                // The permission flow bounced through the system Settings app
                // (app-info / special-access deep link). Climb back out toward
                // the target app; the Settings pages are navigation plumbing,
                // not app states, so they are not recorded.
                current.pkg in StateOps.PERMISSION_SETTINGS_PACKAGES -> {
                    listener.onLog("  ↩ backing out of ${current.pkg} after grant")
                    runCatching { adb.pressBack(serial) }
                    delay(config.settleDelayMs)
                    current = runCatching { capture() }.getOrElse { return null }
                    return@repeat
                }

                // A permission dialog: capture it as a state and auto-grant.
                else -> {
                    val existingId = fingerprintToId[current.fingerprint]
                    val dialog = if (existingId != null) {
                        session.states.first { it.id == existingId }
                    } else {
                        registerPermissionDialogState(current, triggerPath).also {
                            fingerprintToId[current.fingerprint] = it.id
                        }
                    }

                    // Wire the incoming edge: source→firstDialog, then dialog→dialog.
                    if (prevDialogId == null) {
                        if (incomingFrom != null && incomingAction != null) {
                            session.transitions += TransitionEntry(incomingFrom.id, dialog.id, incomingAction)
                        }
                    } else {
                        session.transitions += TransitionEntry(prevDialogId, dialog.id, prevAllow!!)
                    }
                    persist()

                    val node = StateOps.findPermissionAllowNode(current.root)
                    if (node == null) {
                        listener.onLog("  ⚠ no Allow button on permission dialog ${dialog.id}")
                        return null
                    }
                    val allowAction = clickableFromNode(node)
                    try {
                        adb.inputTap(serial, allowAction.tapX, allowAction.tapY)
                        delay(config.settleDelayMs)
                        current = capture()
                    } catch (e: Exception) {
                        listener.onLog("  ⚠ auto-Allow failed: ${e.message ?: e::class.simpleName}")
                        return null
                    }
                    prevDialogId = dialog.id
                    prevAllow = allowAction
                }
            }
        }
        listener.onLog("  ⚠ permission flow unresolved after $PERMISSION_FLOW_MAX_STEPS steps")
        return null
    }

    /**
     * Classifies the screen reached behind a resolved permission gate and
     * records the granting transition from [grant].lastDialogId. Returns the
     * [StateEntry] the device is now on (a fresh child registered with the
     * trigger-only path, or an already-known screen), or `null` when the grant
     * left us off-app — so the frontier loop keeps diving from the right place.
     */
    private suspend fun classifyBehindPermission(
        source: StateEntry,
        click: ClickableRef,
        grant: GrantOutcome,
    ): StateEntry? {
        val behind = grant.behind
        if (config.targetPackage.isNotBlank() && behind.pkg != config.targetPackage) {
            // Granting the permission bounced us out of the app (e.g. it fired a
            // call / share intent that opened the dialer). Capture that external
            // screen as a terminal state so the edge points at a real screenshot
            // instead of a dangling dead-end, then climb back into the app rather
            // than carrying on while physically stranded on a foreign screen.
            val knownExt = fingerprintToId[behind.fingerprint]
            val externalId = knownExt
                ?: registerExternalState(behind, source.pathFromRoot + click)
                    .also { fingerprintToId[behind.fingerprint] = it.id }.id
            listener.onLog("  ⇗ behind permission left the app (${behind.pkg}) → captured as $externalId")
            session.transitions += TransitionEntry(grant.lastDialogId, externalId, grant.lastAllowAction, leftApp = true)
            backLeadsTo[externalId] = source.id
            return returnToApp(source)
        }
        val knownId = fingerprintToId[behind.fingerprint]
        if (knownId != null) {
            listener.onLog("  → behind permission → known state $knownId")
            session.transitions += TransitionEntry(
                grant.lastDialogId, knownId, grant.lastAllowAction, loop = knownId == grant.lastDialogId,
            )
            // Post-grant shortcut: tapping the trigger on `source` now lands
            // straight on this screen (the dialog is gone).
            navShortcuts += NavStep.Forward(source.id, knownId, click)
            return session.states.firstOrNull { it.id == knownId }
        }
        val child = registerState(
            snap = behind,
            depth = source.depth + 1,
            path = source.pathFromRoot + click,
        )
        fingerprintToId[behind.fingerprint] = child.id
        session.transitions += TransitionEntry(grant.lastDialogId, child.id, grant.lastAllowAction)
        backLeadsTo[child.id] = source.id
        navShortcuts += NavStep.Forward(source.id, child.id, click)
        listener.onLog("  → new ${child.id} behind permission (${child.clickables.size} actions)")
        emit(child.id, null)
        return child
    }

    /**
     * Registers a permission-dialog screenshot + dump as a terminal state.
     * Its clickables are deliberately left empty: the only useful action on a
     * permission dialog is the Allow we just exercised, so we don't want the
     * BFS or fix-up pass trying to navigate its buttons as app screens (and
     * the dialog never reappears once granted, so it couldn't be replayed
     * anyway). The screenshot is what satisfies "present in the captures".
     */
    private fun registerPermissionDialogState(snap: Snapshot, path: List<ClickableRef>): StateEntry {
        val id = "S${session.states.size}"
        val screenshotPath = store.writeScreenshot(id, snap.png)
        val xmlPath = store.writeXml(id, snap.xml)
        val entry = StateEntry(
            id = id,
            fingerprint = snap.fingerprint,
            packageName = snap.pkg,
            depth = path.size,
            screenshotPath = screenshotPath,
            xmlPath = xmlPath,
            clickables = emptyList(),
            pathFromRoot = path,
        )
        session.states += entry
        structureFingerprintByStateId[id] = StateOps.structureFingerprint(snap.root)
        // Flag it as one-time: navigation must never try to route back through
        // this dialog, because once granted the system won't show it again.
        permissionDialogStateIds += id
        listener.onLog("  📋 captured permission dialog as $id (one-time)")
        return entry
    }

    /**
     * Registers a screen that belongs to a *foreign* app (a browser page, a
     * share sheet, an external viewer) as a terminal state: its screenshot and
     * dump are kept so the graph shows where the tap led, but its clickables
     * are left empty so the crawler never tries to explore inside another app.
     */
    private fun registerExternalState(snap: Snapshot, path: List<ClickableRef>): StateEntry {
        val id = "S${session.states.size}"
        val screenshotPath = store.writeScreenshot(id, snap.png)
        val xmlPath = store.writeXml(id, snap.xml)
        val entry = StateEntry(
            id = id,
            fingerprint = snap.fingerprint,
            packageName = snap.pkg,
            depth = path.size,
            screenshotPath = screenshotPath,
            xmlPath = xmlPath,
            clickables = emptyList(),
            pathFromRoot = path,
        )
        session.states += entry
        structureFingerprintByStateId[id] = StateOps.structureFingerprint(snap.root)
        listener.onLog("  📸 captured external screen (${snap.pkg}) as $id")
        return entry
    }

    /** Builds a [ClickableRef] from a permission button node for the graph. */
    private fun clickableFromNode(n: UiNode): ClickableRef {
        val b = n.bounds!!
        return ClickableRef(
            resourceId = n.resourceId,
            className = n.className,
            text = n.text,
            contentDesc = n.contentDesc,
            bounds = SerialBounds.from(b),
            tapX = (b.left + b.right) / 2,
            tapY = (b.top + b.bottom) / 2,
        )
    }

    /**
     * Replays a forward sequence of actions on the device. A step that was only
     * reachable after scrolling ([ClickableRef.scrollToReveal] > 0) first
     * scrolls the current screen's main scrollable container that many times so
     * the element is back in view before the tap. Shared by [recoverTo] and
     * [pressBackAndEnsure].
     */
    private suspend fun replaySteps(steps: List<ClickableRef>) {
        for (step in steps) {
            if (step.scrollToReveal > 0) scrollDownTimes(step.scrollToReveal)
            adb.inputTap(serial, step.tapX, step.tapY)
            delay(config.settleDelayMs)
        }
    }

    /**
     * Swipes the current screen's largest scrollable container upward [times]
     * times (revealing content further down). Best-effort: a no-op when no
     * scrollable container is present. Used both to re-reveal an off-screen
     * clickable before replaying a tap and by the in-screen scroll harvest.
     */
    private suspend fun scrollDownTimes(times: Int) {
        if (times <= 0) return
        val snap = runCatching { capture() }.getOrNull() ?: return
        val scrollable = ScrollCapture.findScrollable(snap.root) ?: return
        val b = scrollable.bounds ?: return
        val midX = (b.left + b.right) / 2
        val travel = (b.height * 70) / 100
        val centerY = (b.top + b.bottom) / 2
        repeat(times) {
            runCatching { adb.inputSwipe(serial, midX, centerY + travel / 2, midX, centerY - travel / 2, 350) }
            delay(config.settleDelayMs)
        }
    }

    /**
     * Scrolls [source] (the screen the device is currently on) downward up to
     * [ExplorationConfig.maxScrollFrames] times, collecting clickables that
     * weren't visible at scroll 0. Each newly-found clickable is tagged with
     * the number of scrolls that reveal it (its recorded bounds are its
     * *revealed* on-screen position) so the explorer can scroll-then-tap it and
     * replay it later. The device is returned to the top before this returns,
     * leaving `source` exactly as the caller found it.
     *
     * Wheel pickers (NumberPicker/DatePicker/TimePicker) are skipped: they are
     * `scrollable` too, but "scrolling" them only rotates labels — harvesting
     * those would resurrect the very state explosion the wheel-picker
     * short-circuit exists to prevent.
     */
    private suspend fun harvestScrolledClickables(source: StateEntry): List<ClickableRef> {
        if (config.maxScrollFrames <= 0) return emptyList()
        val first = runCatching { capture() }.getOrNull() ?: return emptyList()
        val scrollable = ScrollCapture.findScrollable(first.root) ?: return emptyList()
        if (StateOps.isInsideWheelPicker(scrollable)) return emptyList()
        val b0 = scrollable.bounds ?: return emptyList()
        val midX = (b0.left + b0.right) / 2
        val travel = (b0.height * 70) / 100
        val centerY = (b0.top + b0.bottom) / 2

        // Don't re-add anything already exposed at scroll 0. Match on identity
        // (class + id + copy), NOT bounds, since bounds shift as we scroll.
        fun sig(c: ClickableRef) = "${c.className}|${c.resourceId}|${c.text}|${c.contentDesc}"
        val seen = HashSet<String>().apply { source.clickables.forEach { add(sig(it)) } }

        val harvested = ArrayList<ClickableRef>()
        var prevFp = first.fingerprint
        var steps = 0
        while (steps < config.maxScrollFrames && session.states.size < config.maxStates) {
            runCatching { adb.inputSwipe(serial, midX, centerY + travel / 2, midX, centerY - travel / 2, 350) }
            delay(config.settleDelayMs)
            val snap = runCatching { capture() }.getOrNull() ?: break
            steps++
            if (snap.fingerprint == prevFp) break // bottom reached — nothing new uncovered
            prevFp = snap.fingerprint
            val frameClicks = StateOps.collectClickables(snap.root, config.targetPackage, config.maxClickablesPerState)
            for (c in frameClicks) {
                val s = sig(c)
                if (!seen.add(s)) continue
                harvested += c.copy(scrollToReveal = steps)
            }
        }
        // Restore the scroll position so siblings / recovery see `source` at top.
        repeat(steps) {
            runCatching { adb.inputSwipe(serial, midX, centerY - travel / 2, midX, centerY + travel / 2, 350) }
            delay(config.settleDelayMs)
        }
        if (harvested.isNotEmpty()) {
            listener.onLog("  ↕ scroll harvest on ${source.id}: +${harvested.size} off-screen clickable(s) over $steps step(s)")
        }
        return harvested
    }

    private suspend fun verifyOn(target: StateEntry): Boolean {
        val s = runCatching { capture() }.getOrNull() ?: return false
        if (s.fingerprint == target.fingerprint) return true
        // Fingerprints recorded as "still on this state" by selection-only
        // taps (picker rotations etc.) live in [fingerprintToId] without
        // appearing in the canonical `target.fingerprint` slot — accept
        // them here so a wheel tap doesn't trigger a costly recovery. This also
        // accepts a different toggle/scroll variant of the same root screen.
        return resolveKnownStateId(s) == target.id
    }

    /**
     * Relaunches the app, waits for any known state to appear, and replays
     * only the portion of [path] that still applies from that anchor point.
     *
     * This handles the onboarding case: when the first launch of the app
     * showed a welcome flow (registered as `S0` … `Sn`), a subsequent launch
     * typically skips it and lands directly on a later state (say `Sn+1`).
     * Rather than give up because `S0` never reappears, we attach to the
     * first known fingerprint we observe and replay only the suffix of the
     * original click chain starting from that state. If the landed state
     * isn't on the path to [target], the recovery fails and the caller will
     * let the fix-up pass retry later.
     */
    private suspend fun recoverTo(target: StateEntry, path: List<ClickableRef>): Boolean {
        listener.onLog("  ↻ recover → ${target.id} (relaunch + replay ${path.size} taps)")
        return try {
            adb.launchApp(serial, config.targetPackage)
            val landed = waitForKnownState()
            if (landed == null) {
                listener.onLog("  ✘ no known state appeared after launch")
                return false
            }
            val rootId = rootStateId
            val suffix = if (landed.id == rootId) {
                path
            } else {
                val skipped = pathSuffixFrom(landed.id, path) ?: run {
                    listener.onLog("  ✘ landed on ${landed.id} but it's not on the path to ${target.id}")
                    return false
                }
                listener.onLog("  ↳ attached to ${landed.id}, replaying ${skipped.size}/${path.size} tap(s)")
                skipped
            }
            replaySteps(suffix)
            val ok = verifyOn(target)
            if (!ok) listener.onLog("  ✘ recovery landed on a different state")
            ok
        } catch (e: Exception) {
            listener.onLog("  ✘ recovery threw: ${e.message}")
            false
        }
    }

    /**
     * Polls captures until any registered state's fingerprint appears, and
     * returns that [StateEntry]. Returns `null` if no known state shows up
     * within `maxAttempts` captures. Each iteration reuses [capture] (idle
     * wait + XML dump) so a long splash screen is given room to finish.
     *
     * Match is widened beyond `state.fingerprint`: any fingerprint
     * previously mapped to a state via [fingerprintToId] (e.g. a wheel
     * picker rotation we deliberately collapsed onto the source) also
     * counts as "you are on that state", so recovery after a picker tap
     * does not insist on seeing the canonical fingerprint we first saw.
     */
    private suspend fun waitForKnownState(): StateEntry? {
        val statesById = session.states.associateBy { it.id }
        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            val snap = runCatching { capture() }.getOrNull()
            // A permission prompt fired by the relaunch (e.g. a previously
            // "Only this time" grant re-asking) must be auto-allowed rather
            // than mistaken for the screen we're waiting for — tap through it
            // quietly (it was already captured on the first pass) and retry.
            if (snap != null && snap.pkg in StateOps.PERMISSION_PACKAGES) {
                val node = StateOps.findPermissionAllowNode(snap.root)
                val b = node?.bounds
                if (b != null) {
                    listener.onLog("  🔓 auto-granting permission during recovery")
                    runCatching { adb.inputTap(serial, (b.left + b.right) / 2, (b.top + b.bottom) / 2) }
                    delay(config.settleDelayMs)
                }
                continue
            }
            val match = snap?.let { resolveKnownStateId(it) }?.let { id -> statesById[id] }
            if (match != null) {
                if (attempt > 1) listener.onLog("  ✓ ${match.id} reached after $attempt captures")
                return match
            }
            val current = snap?.fingerprint?.take(8)
            listener.onLog("  ⏳ waiting for a known state (seen fp=${current ?: "?"}…)")
            delay(config.settleDelayMs)
        }
        return null
    }

    /**
     * Walks the recorded forward transitions to find the suffix of [path]
     * that begins *after* [landedId] is reached. Returns `null` when the
     * landed state isn't on this particular path. Self-loops and error
     * edges are ignored — we only follow forward transitions whose action
     * matches the corresponding [ClickableRef] by resource id + bounds.
     */
    private fun pathSuffixFrom(landedId: String, path: List<ClickableRef>): List<ClickableRef>? {
        val rootId = rootStateId ?: return null
        if (landedId == rootId) return path
        var current = rootId
        for ((index, action) in path.withIndex()) {
            val nextId = session.transitions.firstOrNull { t ->
                t.from == current &&
                    t.to != null &&
                    !t.loop &&
                    t.errorMessage == null &&
                    t.action.resourceId == action.resourceId &&
                    t.action.bounds == action.bounds
            }?.to ?: return null
            current = nextId
            if (current == landedId) return path.subList(index + 1, path.size)
        }
        return null
    }

    /**
     * "Branch depth" of a state: the number of *forks* on the shortest forward
     * path from the root to it. A screen reached by walking a chain of
     * single-exit screens (a wizard / onboarding) shares its predecessor's
     * branch depth — only a source that offered more than one forward
     * destination increments it. This keeps a long linear onboarding from
     * eating the [ExplorationConfig.maxDepth] budget meant for the real feature
     * tree, so "explore everything" isn't cut short three wizard steps in.
     *
     * Under-counting early (a source whose siblings aren't discovered yet looks
     * linear) only ever errs toward exploring more; total work stays bounded by
     * [ExplorationConfig.maxStates].
     */
    private fun branchDepth(stateId: String): Int {
        val root = rootStateId ?: return 0
        if (stateId == root) return 0
        val forward = HashMap<String, MutableSet<String>>()
        for (t in session.transitions) {
            if (t.to == null || t.loop || t.leftApp || t.errorMessage != null) continue
            if (t.to == t.from) continue
            forward.getOrPut(t.from) { HashSet() }.add(t.to)
        }
        // BFS shortest path root → stateId, keeping parent pointers.
        val parent = HashMap<String, String>()
        val visited = HashSet<String>().apply { add(root) }
        val queue = ArrayDeque<String>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n == stateId) break
            for (m in forward[n].orEmpty()) if (visited.add(m)) { parent[m] = n; queue += m }
        }
        if (stateId !in visited) return 0 // only reachable via a cross-edge — treat as shallow
        var depth = 0
        var cur = stateId
        while (cur != root) {
            val p = parent[cur] ?: break
            if ((forward[p]?.size ?: 0) >= 2) depth++
            cur = p
        }
        return depth
    }

    private fun isExercised(source: StateEntry, click: ClickableRef): Boolean =
        session.transitions.any { t ->
            t.from == source.id &&
                t.action.resourceId == click.resourceId &&
                t.action.bounds == click.bounds
        }

    /**
     * Auto-fills empty editable fields on [source] (the screen the device is
     * currently on) with [StateOps.defaultValueFor] values so input gates that
     * block on empty fields can be walked through. Field geometry is read from
     * the state's saved dump, which matches the live screen since the caller
     * has just confirmed we're on it. Best-effort: failures are swallowed.
     *
     * Filling does not change the state's fingerprint — editable text is
     * excluded from it (see [StateOps.fingerprint]) — so the filled form is
     * still recognised as the same state on subsequent post-tap captures.
     */
    private suspend fun fillTextFieldsOn(source: StateEntry) {
        if (!config.fillTextFields) return
        val xml = runCatching {
            java.io.File(store.baseDir, source.xmlPath).readText(Charsets.UTF_8)
        }.getOrNull() ?: return
        val root = DumpParser.parse(xml) ?: return
        val fields = StateOps.collectEditableFields(root, config.targetPackage)
            .filter { it.text.isBlank() }
        if (fields.isEmpty()) return
        listener.onLog("  ⌨ filling ${fields.size} empty text field(s) on ${source.id}")
        for (f in fields) {
            val b = f.bounds ?: continue
            val value = StateOps.defaultValueFor(f)
            runCatching {
                adb.inputTap(serial, (b.left + b.right) / 2, (b.top + b.bottom) / 2)
                delay(config.settleDelayMs)
                adb.inputText(serial, value)
                delay(config.settleDelayMs)
            }.onFailure {
                listener.onLog("  ⚠ could not fill « ${f.label} »: ${it.message ?: it::class.simpleName}")
            }
        }
    }

    /**
     * If [initial] is a long-running operation screen (firmware update,
     * download, "connecting / please wait" spinner — see
     * [StateOps.isLongRunningOperation]), polls the device until the app moves
     * on to a different, non-operation screen, then returns that capture. Caps
     * the wait at [ExplorationConfig.longOperationMaxWaitMs] and exits the
     * moment the operation finishes, so it costs real time only while something
     * is actually running. Returns [initial] unchanged for normal screens.
     *
     * Completion is detected on the **structure** fingerprint (text-free DOM
     * skeleton): a progress bar filling or a "50% → 51%" caption does not change
     * the skeleton, but navigating to the next screen does — so we wait exactly
     * as long as the operation, not a fixed delay.
     */
    private suspend fun waitOutLongOperation(initial: Snapshot): Snapshot {
        if (config.longOperationMaxWaitMs <= 0) return initial
        // Never wait twice on the same screen that already timed out — a stuck
        // spinner (a Hub that won't connect) must cost its budget at most once.
        val key = StateOps.structureFingerprint(initial.root)
        if (key in timedOutWaitScreens) return initial
        if (!isWaitScreen(initial)) return initial
        val label = StateOps.longOperationLabel(initial.root)
        val budgetS = config.longOperationMaxWaitMs / 1000
        listener.onLog("  ⏳ long operation${if (label.isNotBlank()) " « $label »" else ""} — waiting up to ${budgetS}s for it to finish…")
        val start = System.currentTimeMillis()
        var lastHeartbeat = start
        while (System.currentTimeMillis() - start < config.longOperationMaxWaitMs && coroutineContext.isActive) {
            delay(LONG_OP_POLL_MS)
            val snap = runCatching { capture() }.getOrNull() ?: continue
            // Done once the app reaches a screen that is no longer a wait screen
            // (it has real actions, or it's a static non-operation screen). This
            // walks chains like connecting → updating → dashboard.
            if (!isWaitScreen(snap)) {
                listener.onLog("  ✓ long operation finished after ${(System.currentTimeMillis() - start) / 1000}s")
                return snap
            }
            if (System.currentTimeMillis() - lastHeartbeat > 30_000) {
                lastHeartbeat = System.currentTimeMillis()
                listener.onLog("  ⏳ still running (${(System.currentTimeMillis() - start) / 1000}s elapsed)…")
            }
        }
        listener.onLog("  ⚠ long operation still running after ${budgetS}s — giving up on this screen (won't wait on it again)")
        timedOutWaitScreens += key
        return runCatching { capture() }.getOrDefault(initial)
    }

    /**
     * `true` when [snap] is a screen the explorer should patiently wait out:
     * either it explicitly names an active operation (firmware / updating /
     * progress bar — see [StateOps.isLongRunningOperation]), or it has **no
     * actionable element, no content text, and is animating**. The latter
     * catches a Compose "Connecting / loading" spinner whose caption is painted
     * but never reaches the accessibility dump — recognised by its motion plus
     * the absence of anything to tap or read. The "no content text" clause is
     * what keeps an animated *content* screen (an auto-scrolling review
     * carousel) from being mistaken for a wait screen.
     */
    private suspend fun isWaitScreen(snap: Snapshot): Boolean {
        if (StateOps.isLongRunningOperation(snap.root)) return true
        val noActions = StateOps.collectClickables(snap.root, config.targetPackage, 1).isEmpty() &&
            StateOps.collectEditableFields(snap.root, config.targetPackage).isEmpty()
        if (!noActions || StateOps.hasMeaningfulText(snap.root)) return false
        return isScreenAnimating()
    }

    /** Takes two screenshots a short moment apart and reports whether the screen is moving. */
    private suspend fun isScreenAnimating(): Boolean {
        val a = runCatching { adb.screenshotPng(serial) }.getOrNull() ?: return false
        delay(450)
        val b = runCatching { adb.screenshotPng(serial) }.getOrNull() ?: return false
        return screenshotsDiffer(a, b)
    }

    private suspend fun capture(): Snapshot {
        // 1. Actively wait for the screen to stabilise, polling screenshots only.
        //    This keeps us out of `uiautomator dump`'s "could not get idle state" trap:
        //    we only fire the expensive dump *after* two consecutive screenshots
        //    match (or after we reach the idle timeout).
        val idle = waitForScreenIdle(
            takeScreenshot = { adb.screenshotPng(serial) },
            maxMs = config.idleMaxWaitMs,
        )
        if (!idle.stable) {
            listener.onLog("  ⚠ screen not idle after ${config.idleMaxWaitMs}ms, dumping anyway")
        } else if (idle.screenshotsTaken > 2) {
            listener.onLog("  ⏳ waited ${idle.screenshotsTaken} polls for idle")
        }
        // 2. Now that the UI has settled (or we've given up), take the XML dump.
        val xml = adb.dumpUiXml(serial)
        val root = DumpParser.parse(xml) ?: error("Invalid UIAutomator dump")
        val pkg = StateOps.dominantPackage(root)
        val fp = StateOps.fingerprint(root)
        lastLiveFingerprint = fp
        return Snapshot(idle.png, xml, root, pkg, fp)
    }

    private fun registerState(
        snap: Snapshot,
        depth: Int,
        path: List<ClickableRef>,
    ): StateEntry {
        val id = "S${session.states.size}"
        val screenshotPath = store.writeScreenshot(id, snap.png)
        val xmlPath = store.writeXml(id, snap.xml)
        val clickables = StateOps.collectClickables(snap.root, config.targetPackage, config.maxClickablesPerState)
        val entry = StateEntry(
            id = id,
            fingerprint = snap.fingerprint,
            packageName = snap.pkg,
            depth = depth,
            screenshotPath = screenshotPath,
            xmlPath = xmlPath,
            clickables = clickables,
            pathFromRoot = path,
        )
        session.states += entry
        structureFingerprintByStateId[id] = StateOps.structureFingerprint(snap.root)
        resourceIdsByStateId[id] = StateOps.screenResourceIds(snap.root, config.targetPackage)
        // Record the screen's root-container id so future captures of the same
        // screen (different toggle / scroll / selection) resolve back to it
        // instead of registering a near-duplicate. First writer wins.
        StateOps.rootScreenId(snap.root, config.targetPackage)?.let { rid ->
            rootIdToStateId.putIfAbsent(rid, id)
        }
        return entry
    }

    /**
     * Resolves a snapshot to an already-known state. Tries an exact fingerprint
     * match first; failing that, falls back to the screen's *root container id*
     * (e.g. a Compose `settings_screen` testTag) so that the same screen with a
     * toggle flipped, a switch checked or a row scrolled into view is recognised
     * as the SAME state instead of spawning S4…S10 duplicates. On a root-id
     * match the new fingerprint is aliased to the known state and any
     * freshly-revealed clickables are merged in, so they still get exercised.
     */
    private fun resolveKnownStateId(snap: Snapshot): String? {
        fingerprintToId[snap.fingerprint]?.let { return it }
        val rid = StateOps.rootScreenId(snap.root, config.targetPackage) ?: return null
        val stateId = rootIdToStateId[rid] ?: return null
        // The root-container id matches a known state, but that alone over-merges
        // when the id is a generic app shell (e.g. `root_app`) shared by unrelated
        // screens. Only treat this as the same screen when the two share most of
        // their resource-ids — toggle / scroll variants of one screen do; FAQ vs
        // About vs a video player inside the same shell do not.
        val knownIds = resourceIdsByStateId[stateId]
        if (knownIds != null && knownIds.isNotEmpty()) {
            val newIds = StateOps.screenResourceIds(snap.root, config.targetPackage)
            if (jaccard(knownIds, newIds) < ROOT_MERGE_MIN_JACCARD) return null
        }
        fingerprintToId[snap.fingerprint] = stateId
        mergeRevealedClickables(stateId, snap)
        return stateId
    }

    /** Overlap ratio |A∩B| / |A∪B|; 0 when both sets are empty. */
    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /**
     * Absorbs clickables that a variant of an already-known screen exposes but
     * the stored state doesn't yet have (e.g. notification sub-options revealed
     * once a switch is on, or rows scrolled into view). Newly-seen actions are
     * appended as un-exercised work so the frontier still visits them — this is
     * what stops the export / import / "rate app" rows from being skipped when
     * the settings screen collapses into a single state.
     */
    private fun mergeRevealedClickables(stateId: String, snap: Snapshot) {
        val idx = session.states.indexOfFirst { it.id == stateId }
        if (idx < 0) return
        val existing = session.states[idx]
        fun key(c: ClickableRef) = if (c.resourceId.isNotBlank()) "rid:${c.resourceId}" else "bnd:${c.bounds}"
        val have = existing.clickables.mapTo(HashSet()) { key(it) }
        val fresh = StateOps.collectClickables(snap.root, config.targetPackage, config.maxClickablesPerState)
            .filter { key(it) !in have }
        if (fresh.isEmpty()) return
        session.states[idx] = existing.copy(clickables = existing.clickables + fresh)
        planned += fresh.size
        persist()
    }

    /**
     * Heuristic: the tap landed on what is structurally the same screen as
     * `source`, just with a different cell highlighted. We only trust this
     * when the tapped clickable was part of a sufficiently large sibling
     * group ([pickerSiblingThreshold]+) — otherwise wizard "Next" buttons or
     * small tab bars whose target screens happen to share the source's DOM
     * skeleton would all collapse into self-loops, hiding real navigation.
     */
    /**
     * `true` when the tap landed on a *bare media* screen (see
     * [StateOps.isBareMediaScreen]) that has already been reached from [source]
     * via a **different** element — i.e. a list/menu whose items each open a
     * structurally-identical full-screen video / image. Those screens are
     * indistinguishable in the accessibility tree (only the pixels differ), so
     * fingerprint dedup would collapse them into one node; this lets each entry
     * keep its own state and screenshot. Dismiss / back affordances are excluded
     * so closing such a screen does not spawn a clone.
     */
    private fun shouldFanOutDetail(source: StateEntry, click: ClickableRef, knownId: String, after: Snapshot): Boolean {
        if (knownId == source.id) return false
        if (StateOps.isLikelyDismissAction(click)) return false
        if (!StateOps.isBareMediaScreen(after.root, config.targetPackage)) return false
        return session.transitions.any { t ->
            t.from == source.id && t.to == knownId && !t.loop && !t.leftApp &&
                t.errorMessage == null && t.action.resourceId != click.resourceId
        }
    }

    private fun isSelectionWithinSameScreen(source: StateEntry, click: ClickableRef, after: Snapshot): Boolean {
        if (click.siblingGroupSize < pickerSiblingThreshold) return false
        val sourceStructure = structureFingerprintByStateId[source.id] ?: return false
        return sourceStructure == StateOps.structureFingerprint(after.root)
    }

    private fun countProcessed(stateId: String, actionLabel: String) {
        processed++
        emit(stateId, actionLabel)
    }

    private fun emit(stateId: String?, actionLabel: String?) {
        listener.onProgress(
            ExplorerProgress(
                discoveredStates = session.states.size,
                processedActions = processed,
                plannedActions = planned,
                currentStateId = stateId,
                currentActionLabel = actionLabel,
            )
        )
    }

    private fun persist() {
        store.save(session)
        listener.onSessionUpdated(session)
    }

    private companion object {
        /**
         * Hard cap on the number of steps the explorer takes while resolving a
         * system permission flow — each step is either an Allow tap on a
         * dialog or a BACK out of a Settings page the flow bounced through.
         * Most permissions resolve in one or two steps; an app that deep-links
         * into Settings and back may take a handful. Beyond this budget we
         * assume the flow is stuck and bail out (the dialog states captured so
         * far are kept; the caller recovers to its source).
         */
        const val PERMISSION_FLOW_MAX_STEPS = 8

        /**
         * How many BACK presses [pressBackAndEnsure] will try before paying for
         * a full relaunch + replay. Covers a screen sitting two or three levels
         * above the one we need to return to; beyond that a relaunch is more
         * reliable than guessing.
         */
        const val BACK_RECOVERY_ATTEMPTS = 3

        /** Poll interval while waiting out a long-running operation screen. */
        const val LONG_OP_POLL_MS = 3_000L

        /**
         * Minimum resource-id overlap (Jaccard) for two screens that share a
         * root-container id to be merged into one state. Above it: toggle /
         * scroll variants of the same screen (they share nearly all ids). Below
         * it: distinct screens that merely share a generic app-shell container.
         */
        const val ROOT_MERGE_MIN_JACCARD = 0.5
    }
}
