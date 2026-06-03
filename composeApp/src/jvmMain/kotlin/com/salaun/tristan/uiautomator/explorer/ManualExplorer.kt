package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.adb.AdbGateway
import com.salaun.tristan.uiautomator.model.DumpParser
import com.salaun.tristan.uiautomator.model.UiNode
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Driver for "manual" exploration: the user tells the explorer what to do
 * (tap here, press BACK, relaunch) and the explorer captures + records the
 * resulting state in the same on-disk format as the automatic mode. Once
 * persisted, a manual session is indistinguishable from an automated one in
 * the graph viewer, the sessions list, and the export/import flows.
 *
 * The class is single-threaded — concurrent calls would race on the in-memory
 * session — and is meant to be driven from a Compose coroutine scope, one
 * action at a time.
 */
class ManualExplorer(
    private val adb: AdbGateway,
    private val serial: String?,
    private val targetPackage: String,
    private val store: SessionStore,
    /** Idle wait budget after each action — same default as the auto mode. */
    private val idleMaxWaitMs: Long = 8_000,
    /** Initial debounce after every action before the idle-wait loop kicks in. */
    private val settleDelayMs: Long = 500,
) {

    interface Listener {
        fun onLog(msg: String)
        /** Fired after every state change so the UI can refresh its preview. */
        fun onSessionUpdated(
            session: ExplorationSession,
            currentScreenshotPng: ByteArray?,
            currentRoot: UiNode?,
            currentStateId: String?,
        )
        /**
         * Fired after a successful scroll-capture pass. The UI displays the
         * tall stitched image; subsequent taps go through [tapVirtual] until
         * [exitScrollMode] is called or a tap navigates away.
         */
        fun onScrollCaptureReady(stitched: ScrollCapture.Stitched) {}
        /** Fired when the scroll-capture overlay is dismissed (by tap, cancel or navigation). */
        fun onScrollCaptureDismissed() {}
    }

    val session: ExplorationSession = ExplorationSession(
        id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()),
        targetPackage = targetPackage,
        startedAt = System.currentTimeMillis(),
        config = ExplorationConfig(targetPackage = targetPackage),
    )

    /** fingerprint -> stateId, used to dedupe revisited screens. */
    private val fingerprintToId = HashMap<String, String>()

    /** SHA-1 of the structural skeleton of every state we've registered. */
    private val structureFpById = HashMap<String, String>()

    var currentStateId: String? = null
        private set
    var currentPng: ByteArray? = null
        private set
    var currentRoot: UiNode? = null
        private set

    private var demoOn: Boolean = false

    /**
     * Currently-active scroll capture, if the user is interacting with the
     * stitched view. While non-null, the device sits at frame 0 and taps go
     * through [tapVirtual]. Cleared on [exitScrollMode], on a successful
     * navigation tap, or on any other action that moves to a different state.
     */
    var activeScrollCapture: ScrollCapture.Stitched? = null
        private set

    /**
     * Bootstraps the session: enables demo mode (best-effort), launches the
     * target app, captures the initial screen and registers it as the root
     * state. Subsequent calls to [tap]/[pressBack]/[relaunch] grow the session.
     */
    suspend fun start(listener: Listener): Boolean {
        listener.onLog("Session: ${store.baseDir.absolutePath}")
        demoOn = runCatching { adb.enterDemoMode(serial) }.getOrDefault(false)
        if (demoOn) {
            listener.onLog("✓ SystemUI demo mode enabled (clock/battery/signal frozen)")
        } else {
            listener.onLog(
                "⚠ Could not enable SystemUI demo mode — minor status-bar changes may " +
                    "split duplicate states. To allow it: " +
                    "`adb shell settings put global sysui_demo_allowed 1`"
            )
        }
        listener.onLog("Launching $targetPackage…")
        return try {
            adb.launchApp(serial, targetPackage)
            delay(settleDelayMs + 500)
            captureAndRegister(transitionFrom = null, action = null, listener = listener)
            true
        } catch (e: Exception) {
            listener.onLog("✘ Initial launch failed: ${e.message ?: e::class.simpleName}")
            false
        }
    }

    /**
     * Forwards a tap to the device at the supplied screen-pixel coordinates,
     * waits for the screen to settle, captures, and records the corresponding
     * transition. The action's [ClickableRef] is built from the smallest UI
     * node containing the tap point — gives meaningful labels in the graph
     * even when the user clicked between elements.
     */
    suspend fun tap(x: Int, y: Int, listener: Listener) {
        // A normal tap dismisses the scroll overlay if any (the user clicked
        // outside the stitched view, e.g. on the side panel — but if we're in
        // scroll mode the UI calls tapVirtual instead).
        if (activeScrollCapture != null) dismissScrollMode(listener)
        val sourceId = currentStateId ?: run {
            listener.onLog("✘ Cannot tap before the initial capture — start the session first.")
            return
        }
        val source = session.states.firstOrNull { it.id == sourceId } ?: return
        val tappedNode = currentRoot?.findSmallestAt(x, y)
        // Match what `StateOps.collectClickables` records for tapX/tapY (the
        // bounds centre) when we did identify a node, so the transition's
        // ClickableRef equals the corresponding entry of `StateEntry.clickables`
        // — required for `ScreenshotDetailWindow` to find the destination of
        // a tap when reviewing the saved session. We fall back to the user's
        // actual tap coordinates only when no UI node was hit.
        val nodeBounds = tappedNode?.bounds
        val click = ClickableRef(
            resourceId = tappedNode?.resourceId.orEmpty(),
            className = tappedNode?.className?.takeIf { it.isNotBlank() } ?: "android.view.View",
            text = tappedNode?.text.orEmpty(),
            contentDesc = tappedNode?.contentDesc.orEmpty(),
            bounds = nodeBounds?.let { SerialBounds.from(it) } ?: SerialBounds(x, y, x + 1, y + 1),
            tapX = nodeBounds?.let { (it.left + it.right) / 2 } ?: x,
            tapY = nodeBounds?.let { (it.top + it.bottom) / 2 } ?: y,
        )
        listener.onLog("▶ ${source.id} · tap (${x}, ${y}) « ${click.label} »")
        runCatching { adb.inputTap(serial, x, y) }.onFailure {
            listener.onLog("✘ tap failed: ${it.message ?: it::class.simpleName}")
            return
        }
        delay(settleDelayMs)
        captureAndRegister(transitionFrom = source, action = click, listener = listener)
    }

    /** Press BACK on the device, then capture & record the resulting state. */
    suspend fun pressBack(listener: Listener) {
        if (activeScrollCapture != null) dismissScrollMode(listener)
        val sourceId = currentStateId ?: run {
            listener.onLog("✘ Cannot press BACK before starting the session.")
            return
        }
        val source = session.states.firstOrNull { it.id == sourceId } ?: return
        listener.onLog("◀ ${source.id} · BACK")
        runCatching { adb.pressBack(serial) }.onFailure {
            listener.onLog("✘ BACK failed: ${it.message ?: it::class.simpleName}")
            return
        }
        delay(settleDelayMs)
        captureAndRegister(transitionFrom = source, action = SYNTHETIC_BACK, listener = listener)
    }

    /**
     * Relaunches the target package and captures the resulting screen. When
     * called before [start] this becomes equivalent to [start] (registers a
     * fresh root state). After [start], it records a transition labelled
     * `HOME` from the previous current state.
     */
    suspend fun relaunch(listener: Listener) {
        if (activeScrollCapture != null) dismissScrollMode(listener)
        val source = currentStateId?.let { id -> session.states.firstOrNull { it.id == id } }
        listener.onLog("⌂ relaunch $targetPackage")
        runCatching { adb.launchApp(serial, targetPackage) }.onFailure {
            listener.onLog("✘ relaunch failed: ${it.message ?: it::class.simpleName}")
            return
        }
        delay(settleDelayMs + 500)
        captureAndRegister(
            transitionFrom = source,
            action = if (source != null) SYNTHETIC_HOME else null,
            listener = listener,
        )
    }

    /**
     * Re-captures the current screen without recording a transition. Useful
     * when the device's UI has changed asynchronously (e.g. a notification
     * faded, an animation finished) and the user wants the displayed image
     * to reflect the live state. If the freshly-captured screen happens to
     * have a different fingerprint, no transition is recorded — the user
     * intentionally asked for a refresh, not a navigation.
     */
    suspend fun recapture(listener: Listener) {
        if (activeScrollCapture != null) dismissScrollMode(listener)
        if (currentStateId == null) {
            listener.onLog("✘ Nothing to refresh yet — start the session first.")
            return
        }
        val snap = runCatching { capture() }.getOrElse {
            listener.onLog("✘ recapture failed: ${it.message ?: it::class.simpleName}")
            return
        }
        currentPng = snap.png
        currentRoot = snap.root
        listener.onLog("⟳ refreshed screen (fp=${snap.fingerprint.take(8)}…)")
        listener.onSessionUpdated(session, currentPng, currentRoot, currentStateId)
    }

    /**
     * Captures the current screen in several scrolled frames and stitches them
     * into a tall image. Sticky headers / footers inside the scrollable
     * container are detected and preserved at the top / bottom of the
     * stitched view. The device is brought back to its initial scroll
     * position before this returns, so the displayed [activeScrollCapture]
     * matches what the device actually shows from frame 0.
     */
    suspend fun captureScrollable(listener: Listener) {
        val sourceId = currentStateId ?: run {
            listener.onLog("✘ Cannot scroll-capture before the initial capture — start the session first.")
            return
        }
        if (activeScrollCapture != null) {
            listener.onLog("⚠ A scroll capture is already active — dismiss it first.")
            return
        }
        val rootForDetect = currentRoot ?: run {
            listener.onLog("✘ No UI tree available to locate a scrollable container.")
            return
        }
        val scrollableNode = ScrollCapture.findScrollable(rootForDetect) ?: run {
            listener.onLog("✘ No scrollable container detected on the current screen.")
            return
        }
        val initialPng = currentPng ?: run {
            listener.onLog("✘ No current screenshot to anchor the scroll capture.")
            return
        }
        // The current XML for the source state lives on disk; re-read it so we
        // can rebuild a Frame in the same shape as freshly-captured ones.
        val sourceState = session.states.firstOrNull { it.id == sourceId } ?: run {
            listener.onLog("✘ Could not locate source state $sourceId in session.")
            return
        }
        val initialXml = runCatching { java.io.File(sourceState.xmlPath).readText(Charsets.UTF_8) }
            .getOrElse {
                listener.onLog("✘ Could not read source state XML: ${it.message ?: it::class.simpleName}")
                return
            }

        listener.onLog(
            "↕ scroll-capture starting in ${scrollableNode.label} " +
                "(${scrollableNode.bounds?.width}×${scrollableNode.bounds?.height})"
        )
        val stitched: ScrollCapture.Stitched
        try {
            stitched = ScrollCapture.captureAndStitch(
                initial = ScrollCapture.Frame(
                    png = initialPng,
                    xml = initialXml,
                    root = rootForDetect,
                    scrollDelta = 0,
                ),
                scrollableNode = scrollableNode,
                doSwipe = { sw ->
                    adb.inputSwipe(serial, sw.x, sw.yStart, sw.x, sw.yEnd, sw.durationMs)
                    delay(settleDelayMs)
                    waitForScreenIdle(
                        takeScreenshot = { adb.screenshotPng(serial) },
                        maxMs = idleMaxWaitMs,
                    )
                },
                captureFrame = {
                    val xml = adb.dumpUiXml(serial)
                    val tree = DumpParser.parse(xml) ?: error("Invalid UIAutomator dump")
                    val png = adb.screenshotPng(serial)
                    png to (xml to tree)
                },
            )
        } catch (e: Exception) {
            listener.onLog("✘ scroll-capture failed: ${e.message ?: e::class.simpleName}")
            return
        }

        val frameCount = stitched.frames.size
        listener.onLog(
            "✓ scroll-capture: $frameCount frame(s), virtual height ${stitched.virtualHeight}px " +
                "(real ${stitched.realHeight}px), live=[${stitched.liveTop}, ${stitched.liveBottom})"
        )

        // Bring the device back to frame 0 by reverse-swiping (frameCount - 1)
        // times. The reverse swipe is the same gesture inverted; we accept
        // some imprecision (rubber-banding, fling residue) — the user can
        // still tap fixed bands directly, and live-zone taps re-navigate.
        val reverse = ScrollCapture.Swipe(
            x = stitched.swipe.x,
            yStart = stitched.swipe.yEnd,
            yEnd = stitched.swipe.yStart,
            durationMs = stitched.swipe.durationMs,
        )
        repeat(frameCount - 1) {
            runCatching { adb.inputSwipe(serial, reverse.x, reverse.yStart, reverse.x, reverse.yEnd, reverse.durationMs) }
            delay(settleDelayMs)
        }

        activeScrollCapture = stitched
        listener.onScrollCaptureReady(stitched)
    }

    /**
     * Routes a tap expressed in stitched-virtual coordinates: navigates the
     * device to the right frame (replaying the recorded swipe forward), then
     * taps at the corresponding real pixel. After the tap, scroll mode is
     * dismissed and the post-tap screen is captured & registered like any
     * other manual transition.
     */
    suspend fun tapVirtual(vx: Int, vy: Int, listener: Listener) {
        val stitched = activeScrollCapture ?: run {
            // No active scroll capture — fall back to a normal tap. This
            // matters when the UI's pointer handler races the dismissal.
            tap(vx, vy, listener)
            return
        }
        val sourceId = currentStateId ?: run {
            listener.onLog("✘ Cannot tap before the initial capture — start the session first.")
            return
        }
        val source = session.states.firstOrNull { it.id == sourceId } ?: return

        val target = stitched.virtualToReal(vx, vy)
        listener.onLog(
            "▶ ${source.id} · stitched tap (${vx}, ${vy}) → frame ${target.frameIndex} " +
                "[${target.kind}] real (${target.realX}, ${target.realY})"
        )

        // Forward-scroll to target frame from the assumed-current frame 0.
        val sw = stitched.swipe
        try {
            repeat(target.frameIndex) {
                adb.inputSwipe(serial, sw.x, sw.yStart, sw.x, sw.yEnd, sw.durationMs)
                delay(settleDelayMs)
            }
        } catch (e: Exception) {
            listener.onLog("✘ scroll-to-frame failed: ${e.message ?: e::class.simpleName}")
            // Best-effort: the post-tap recapture below will sync state regardless.
        }

        // Build a ClickableRef from the *frame*'s tree at the real coordinates,
        // so the recorded action matches what UIAutomator saw at tap time.
        val frame = stitched.frames[target.frameIndex]
        val tappedNode = frame.root.findSmallestAt(target.realX, target.realY)
        val nodeBounds = tappedNode?.bounds
        val click = ClickableRef(
            resourceId = tappedNode?.resourceId.orEmpty(),
            className = tappedNode?.className?.takeIf { it.isNotBlank() } ?: "android.view.View",
            text = tappedNode?.text.orEmpty(),
            contentDesc = tappedNode?.contentDesc.orEmpty(),
            bounds = nodeBounds?.let { SerialBounds.from(it) }
                ?: SerialBounds(target.realX, target.realY, target.realX + 1, target.realY + 1),
            tapX = nodeBounds?.let { (it.left + it.right) / 2 } ?: target.realX,
            tapY = nodeBounds?.let { (it.top + it.bottom) / 2 } ?: target.realY,
        )

        runCatching { adb.inputTap(serial, click.tapX, click.tapY) }.onFailure {
            listener.onLog("✘ tap failed: ${it.message ?: it::class.simpleName}")
            return
        }

        // Tap done — leave scroll mode and let the regular capture / register
        // path snapshot the resulting screen. The new state's fingerprint is
        // computed against the (possibly scrolled) live device, which is fine:
        // wherever we landed, that is now the current state.
        activeScrollCapture = null
        listener.onScrollCaptureDismissed()

        delay(settleDelayMs)
        captureAndRegister(transitionFrom = source, action = click, listener = listener)
    }

    /**
     * Dismisses the stitched overlay without performing a tap. Best-effort
     * scrolls the device back to frame 0 if it was left elsewhere, and
     * recaptures so the displayed screen matches the device.
     */
    suspend fun exitScrollMode(listener: Listener) {
        if (activeScrollCapture == null) return
        dismissScrollMode(listener)
        recapture(listener)
    }

    private fun dismissScrollMode(listener: Listener) {
        if (activeScrollCapture == null) return
        activeScrollCapture = null
        listener.onScrollCaptureDismissed()
    }

    /** Closes the session, restores demo mode, and persists a final snapshot. */
    suspend fun end(listener: Listener) {
        if (activeScrollCapture != null) dismissScrollMode(listener)
        if (demoOn) {
            runCatching { adb.exitDemoMode(serial) }
            listener.onLog("✓ SystemUI demo mode disabled")
            demoOn = false
        }
        session.endedAt = System.currentTimeMillis()
        store.save(session)
        listener.onLog(
            "Session terminée: ${session.states.size} états, ${session.transitions.size} transitions."
        )
        listener.onSessionUpdated(session, currentPng, currentRoot, currentStateId)
    }

    /**
     * Common path: capture the device, dedupe by fingerprint, register a new
     * state if needed, optionally append a transition from the supplied
     * `transitionFrom` source. The new state becomes the current one.
     */
    private suspend fun captureAndRegister(
        transitionFrom: StateEntry?,
        action: ClickableRef?,
        listener: Listener,
    ) {
        val snap = runCatching { capture() }.getOrElse { e ->
            val msg = e.message ?: e::class.simpleName.orEmpty()
            listener.onLog("✘ capture failed: $msg")
            if (transitionFrom != null && action != null) {
                session.transitions += TransitionEntry(
                    from = transitionFrom.id, to = null, action = action, errorMessage = msg,
                )
                store.save(session)
                listener.onSessionUpdated(session, currentPng, currentRoot, currentStateId)
            }
            return
        }

        currentPng = snap.png
        currentRoot = snap.root

        val knownId = fingerprintToId[snap.fingerprint]
        val targetId = knownId ?: registerState(snap, listener).id
        currentStateId = targetId

        if (transitionFrom != null && action != null) {
            val left = targetPackage.isNotBlank() && snap.pkg.isNotBlank() && snap.pkg != targetPackage
            session.transitions += TransitionEntry(
                from = transitionFrom.id,
                to = targetId,
                action = action,
                loop = (transitionFrom.id == targetId),
                leftApp = left,
            )
        }
        store.save(session)
        listener.onSessionUpdated(session, currentPng, currentRoot, currentStateId)
    }

    private fun registerState(snap: Snapshot, listener: Listener): StateEntry {
        val id = "S${session.states.size}"
        val screenshotPath = store.writeScreenshot(id, snap.png)
        val xmlPath = store.writeXml(id, snap.xml)
        val clickables = StateOps.collectClickables(
            root = snap.root,
            pkgFilter = targetPackage,
            max = Int.MAX_VALUE,
        )
        val entry = StateEntry(
            id = id,
            fingerprint = snap.fingerprint,
            packageName = snap.pkg,
            // depth is meaningless for manual exploration — the user picks any
            // path they want. We still record it as 0 for the root and as
            // (previous + 1) for descendants so the graph layout has a hint.
            depth = currentStateId?.let { src ->
                (session.states.firstOrNull { it.id == src }?.depth ?: 0) + 1
            } ?: 0,
            screenshotPath = screenshotPath,
            xmlPath = xmlPath,
            clickables = clickables,
            pathFromRoot = emptyList(),
        )
        session.states += entry
        fingerprintToId[snap.fingerprint] = id
        structureFpById[id] = StateOps.structureFingerprint(snap.root)
        listener.onLog("→ new state ${id} (${clickables.size} clickables, fp=${snap.fingerprint.take(8)}…)")
        return entry
    }

    private suspend fun capture(): Snapshot {
        // Same idle-wait dance as the auto Explorer: poll screenshots until two
        // consecutive frames are byte-identical, then dump the XML.
        val idle = waitForScreenIdle(
            takeScreenshot = { adb.screenshotPng(serial) },
            maxMs = idleMaxWaitMs,
        )
        val xml = adb.dumpUiXml(serial)
        val root = DumpParser.parse(xml) ?: error("Invalid UIAutomator dump")
        val pkg = StateOps.dominantPackage(root)
        return Snapshot(idle.png, xml, root, pkg, StateOps.fingerprint(root))
    }

    private data class Snapshot(
        val png: ByteArray,
        val xml: String,
        val root: UiNode,
        val pkg: String,
        val fingerprint: String,
    )

    private companion object {
        // Synthetic ClickableRefs for the BACK / HOME buttons so manual
        // transitions show up as labelled actions in the graph viewer.
        private val SYNTHETIC_BACK = ClickableRef(
            resourceId = "", className = "BACK", text = "BACK", contentDesc = "",
            bounds = SerialBounds(0, 0, 0, 0), tapX = 0, tapY = 0,
        )
        private val SYNTHETIC_HOME = ClickableRef(
            resourceId = "", className = "HOME", text = "HOME", contentDesc = "",
            bounds = SerialBounds(0, 0, 0, 0), tapX = 0, tapY = 0,
        )
    }
}
