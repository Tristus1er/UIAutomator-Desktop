package com.salaun.tristan.uiautomator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.runtime.mutableStateSetOf
import com.salaun.tristan.uiautomator.adb.AdbDevice
import com.salaun.tristan.uiautomator.adb.AdbError
import com.salaun.tristan.uiautomator.adb.AdbService
import com.salaun.tristan.uiautomator.explorer.ExplorationConfig
import com.salaun.tristan.uiautomator.explorer.ExplorationSession
import com.salaun.tristan.uiautomator.explorer.Explorer
import com.salaun.tristan.uiautomator.explorer.ExplorerProgress
import com.salaun.tristan.uiautomator.explorer.GraphLayout
import com.salaun.tristan.uiautomator.explorer.ManualExplorer
import com.salaun.tristan.uiautomator.explorer.ScrollCapture
import com.salaun.tristan.uiautomator.explorer.SessionStore
import com.salaun.tristan.uiautomator.explorer.SessionSummary
import com.salaun.tristan.uiautomator.explorer.SessionZip
import com.salaun.tristan.uiautomator.explorer.StateOps
import com.salaun.tristan.uiautomator.i18n.Language
import com.salaun.tristan.uiautomator.i18n.Strings
import com.salaun.tristan.uiautomator.i18n.Translations
import com.salaun.tristan.uiautomator.model.DumpParser
import com.salaun.tristan.uiautomator.model.UiNode
import com.salaun.tristan.uiautomator.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Main, Settings, Explorer, ManualExplorer, Graph, Sessions }

class AppState(
    val settings: AppSettings,
    val scope: CoroutineScope,
) {
    val adb: AdbService = AdbService(settings.adbPath.orEmpty())

    var screen: Screen by mutableStateOf(Screen.Main)
    var adbPath: String by mutableStateOf(settings.adbPath.orEmpty())
    var adbPathStatus: String by mutableStateOf("")

    // `languagePreference` is the user's explicit pick (null = follow the OS).
    // `currentLanguage` is what the UI is actually showing right now.
    var languagePreference: Language? by mutableStateOf(
        settings.languageCode?.let(Language::fromCode)
    )
    var currentLanguage: Language by mutableStateOf(languagePreference ?: Language.fromSystem())

    /** Live view over the current [Strings] bundle — used when the call is not inside a Composable. */
    val strings: Strings get() = Translations.of(currentLanguage)

    val devices: SnapshotStateList<AdbDevice> = mutableStateListOf()
    var selectedSerial: String? by mutableStateOf(settings.lastDeviceSerial)

    var busy: Boolean by mutableStateOf(false)
    var statusMessage: String by mutableStateOf("")
    var errorMessage: String? by mutableStateOf(null)

    /**
     * Centralised navigation. The capture status line is deliberately NOT
     * cleared here: it belongs to the Main screen's capture (like the
     * screenshot and the XML) and only Main renders it, so it persists while
     * you go to another screen and back. The transient error message, on the
     * other hand, is dropped so a stale error from one screen doesn't bleed
     * onto the next.
     */
    fun go(target: Screen) {
        errorMessage = null
        screen = target
    }

    var screenshotPng: ByteArray? by mutableStateOf(null)
    var xmlText: String? by mutableStateOf(null)
    var rootNode: UiNode? by mutableStateOf(null)
    val expanded: SnapshotStateSet<UiNode> = mutableStateSetOf()
    var selectedNode: UiNode? by mutableStateOf(null)

    // Exploration — the previous run's config is rehydrated from disk so the
    // user does not have to retype their package / caps after a restart.
    var explorerConfig: ExplorationConfig by mutableStateOf(
        loadExplorationConfig(settings)
    )
    var explorerRunning: Boolean by mutableStateOf(false)
    private var explorerJob: Job? = null
    val explorerLog: SnapshotStateList<String> = mutableStateListOf()
    var explorerProgress: ExplorerProgress? by mutableStateOf(null)
    // `neverEqualPolicy` is critical: ExplorationSession is a data class
    // backed by mutable lists. The Explorer mutates `session.states` /
    // `session.transitions` in place and re-assigns the SAME instance via
    // `explorerSession = session` after each click. With the default
    // structural-equality policy, Compose compares the new value with the
    // previous one — same reference, same content (since the lists were
    // mutated in place) — concludes "nothing changed", and skips
    // recomposition. The UI then freezes on the first observed snapshot.
    // `neverEqualPolicy` forces every assignment to invalidate readers.
    var explorerSession: ExplorationSession? by mutableStateOf(null, neverEqualPolicy())
    var explorerStore: SessionStore? by mutableStateOf(null)

    // -- Manual exploration ---------------------------------------------------
    // The manual driver is kept in-state so its session/screenshot can be
    // displayed in the dedicated screen. We use neverEqualPolicy on the
    // session for the same reason as `explorerSession`: the Explorer mutates
    // the embedded MutableLists in place and re-assigns the same instance, so
    // structural equality would never trigger recomposition.
    private var manualExplorer: ManualExplorer? = null
    var manualSession: ExplorationSession? by mutableStateOf(null, neverEqualPolicy())
    var manualStore: SessionStore? by mutableStateOf(null)
    var manualScreenshotPng: ByteArray? by mutableStateOf(null)
    var manualRootNode: com.salaun.tristan.uiautomator.model.UiNode? by mutableStateOf(null)
    var manualCurrentStateId: String? by mutableStateOf(null)
    val manualLog: SnapshotStateList<String> = mutableStateListOf()
    var manualBusy: Boolean by mutableStateOf(false)
    /**
     * When non-null, the manual screen displays the stitched scroll capture
     * instead of the regular screenshot. `neverEqualPolicy` because Compose
     * needs to recompose even when the same instance is re-published.
     */
    var manualScrollCapture: ScrollCapture.Stitched? by mutableStateOf(null, neverEqualPolicy())

    fun initialize() {
        if (adbPath.isBlank()) {
            com.salaun.tristan.uiautomator.adb.AdbAutoDetect.detect()?.let {
                adbPath = it
                settings.adbPath = it
                adb.adbPath = it
            }
        } else {
            adb.adbPath = adbPath
        }
        refreshAdbStatus()
        if (adbPath.isNotBlank()) refreshDevices()
    }

    fun applyAdbPath(newPath: String) {
        adbPath = newPath.trim()
        settings.adbPath = adbPath.takeIf { it.isNotBlank() }
        adb.adbPath = adbPath
        refreshAdbStatus()
        if (adbPath.isNotBlank()) refreshDevices()
    }

    fun autoDetectAdb() {
        scope.launch {
            statusMessage = strings.settingsAdbAutoDetecting
            val detected = withContext(Dispatchers.IO) { com.salaun.tristan.uiautomator.adb.AdbAutoDetect.detect() }
            if (detected == null) {
                adbPathStatus = strings.settingsAdbNotFound
                statusMessage = ""
            } else {
                applyAdbPath(detected)
                statusMessage = strings.settingsAdbDetected.format(detected)
            }
        }
    }

    private fun refreshAdbStatus() {
        if (adbPath.isBlank()) {
            adbPathStatus = strings.settingsAdbNotConfigured
            return
        }
        scope.launch {
            adbPathStatus = strings.settingsAdbChecking
            try {
                val v = adb.version()
                adbPathStatus = strings.settingsAdbOk.format(v)
            } catch (e: Exception) {
                adbPathStatus = strings.settingsAdbFail.format(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    fun refreshDevices() {
        if (adbPath.isBlank()) return
        scope.launch {
            try {
                val list = adb.listDevices()
                devices.clear()
                devices.addAll(list)
                if (selectedSerial == null || list.none { it.serial == selectedSerial }) {
                    selectedSerial = list.firstOrNull()?.serial
                    settings.lastDeviceSerial = selectedSerial
                }
            } catch (e: Exception) {
                errorMessage = "Erreur listage devices : ${e.message}"
            }
        }
    }

    fun selectDevice(serial: String?) {
        selectedSerial = serial
        settings.lastDeviceSerial = serial
    }

    /** Picks an explicit language; pass `null` to go back to "follow the OS". */
    fun applyLanguagePreference(language: Language?) {
        languagePreference = language
        settings.languageCode = language?.code
        currentLanguage = language ?: Language.fromSystem()
    }

    fun capture() {
        if (adbPath.isBlank()) {
            errorMessage = strings.captureErrorAdbMissing
            return
        }
        val serial = selectedSerial
        if (serial == null && devices.isEmpty()) {
            errorMessage = strings.captureErrorNoDevice
            return
        }
        scope.launch {
            busy = true
            errorMessage = null
            statusMessage = strings.captureStatusInProgress
            try {
                val png = adb.screenshotPng(serial)
                statusMessage = strings.captureStatusDumping
                val xml = adb.dumpUiXml(serial)
                val tree = withContext(Dispatchers.Default) { DumpParser.parse(xml) }
                screenshotPng = png
                xmlText = xml
                rootNode = tree
                selectedNode = null
                expanded.clear()
                tree?.let { expanded += it }
                // Pre-fill the exploration form with the package dominant in
                // the capture, so the user does not have to retype it when
                // switching to the Explorer screen.
                tree?.let {
                    val detected = StateOps.dominantPackage(it)
                    if (detected.isNotBlank() && detected != explorerConfig.targetPackage) {
                        updateExplorerConfig { copy(targetPackage = detected) }
                    }
                }
                statusMessage = strings.captureStatusOkFmt.format(png.size / 1024, xml.length)
            } catch (e: AdbError) {
                errorMessage = e.message
                statusMessage = ""
            } catch (e: Exception) {
                errorMessage = strings.captureErrorFmt.format(e.message ?: e::class.simpleName.orEmpty())
                statusMessage = ""
            } finally {
                busy = false
            }
        }
    }

    fun toggle(node: UiNode) {
        if (node in expanded) expanded -= node else expanded += node
    }

    /** Replaces the whole tree-expansion set (expand-all / collapse-all / deep toggle). */
    fun setExpanded(nodes: Set<UiNode>) {
        expanded.clear()
        expanded.addAll(nodes)
    }

    fun selectNode(node: UiNode?) {
        selectedNode = node
        if (node != null) {
            var p = node.parent
            while (p != null) { expanded += p; p = p.parent }
        }
    }

    // --- Capture actions -----------------------------------------------------

    fun copyScreenshotToClipboard() {
        val png = screenshotPng
        if (png == null) { errorMessage = strings.captureNoneHint; return }
        errorMessage = null
        try {
            if (com.salaun.tristan.uiautomator.ui.copyPngToClipboard(png)) {
                statusMessage = strings.captureCopiedImage
            } else {
                errorMessage = strings.captureActionFailedFmt.format("decode")
            }
        } catch (e: Exception) {
            errorMessage = strings.captureActionFailedFmt.format(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    fun copyXmlToClipboard() {
        val xml = xmlText
        if (xml == null) { errorMessage = strings.captureNoneHint; return }
        errorMessage = null
        try {
            com.salaun.tristan.uiautomator.ui.copyTextToClipboard(xml)
            statusMessage = strings.captureCopiedXml
        } catch (e: Exception) {
            errorMessage = strings.captureActionFailedFmt.format(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    fun saveScreenshotTo(file: java.io.File) {
        val png = screenshotPng
        if (png == null) { errorMessage = strings.captureNoneHint; return }
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { file.writeBytes(png) }
                statusMessage = strings.captureSavedFmt.format(file.absolutePath)
            } catch (e: Exception) {
                errorMessage = strings.captureActionFailedFmt.format(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    fun saveXmlTo(file: java.io.File) {
        val xml = xmlText
        if (xml == null) { errorMessage = strings.captureNoneHint; return }
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { file.writeText(xml, Charsets.UTF_8) }
                statusMessage = strings.captureSavedFmt.format(file.absolutePath)
            } catch (e: Exception) {
                errorMessage = strings.captureActionFailedFmt.format(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    fun exportCaptureTo(file: java.io.File) {
        val png = screenshotPng
        val xml = xmlText
        if (png == null || xml == null) { errorMessage = strings.captureNoneHint; return }
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.salaun.tristan.uiautomator.ui.CaptureIO.exportToZip(png, xml, file)
                }
                statusMessage = strings.captureExportedFmt.format(file.absolutePath)
            } catch (e: Exception) {
                errorMessage = strings.captureActionFailedFmt.format(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    fun importCaptureFrom(file: java.io.File) {
        errorMessage = null
        scope.launch {
            try {
                val bundle = withContext(Dispatchers.IO) {
                    com.salaun.tristan.uiautomator.ui.CaptureIO.importFromZip(file)
                }
                val tree = withContext(Dispatchers.Default) { DumpParser.parse(bundle.xml) }
                screenshotPng = bundle.png
                xmlText = bundle.xml
                rootNode = tree
                selectedNode = null
                expanded.clear()
                tree?.let { expanded += it }
                tree?.let {
                    val detected = StateOps.dominantPackage(it)
                    if (detected.isNotBlank() && detected != explorerConfig.targetPackage) {
                        updateExplorerConfig { copy(targetPackage = detected) }
                    }
                }
                statusMessage = strings.captureImportedFmt.format(file.name)
            } catch (e: Exception) {
                errorMessage = strings.captureActionFailedFmt.format(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    fun suggestTargetPackage(): String {
        val hint = rootNode?.let {
            val counts = HashMap<String, Int>()
            it.walk { n -> if (n.packageName.isNotBlank()) counts.merge(n.packageName, 1, Int::plus) }
            counts.entries.maxByOrNull { it.value }?.key
        }
        return hint.orEmpty()
    }

    fun updateExplorerConfig(update: ExplorationConfig.() -> ExplorationConfig) {
        explorerConfig = explorerConfig.update()
        persistExplorationConfig(settings, explorerConfig)
    }

    fun startExploration() {
        if (explorerRunning) return
        if (adbPath.isBlank()) { errorMessage = strings.explorerErrorAdbMissing; return }
        val pkg = explorerConfig.targetPackage.trim()
        if (pkg.isBlank()) { errorMessage = strings.explorerErrorNoPackage; return }
        val serial = selectedSerial
        val store = SessionStore.create(SessionStore.defaultRoot(), pkg)
        val explorer = Explorer(adb, serial, explorerConfig, store)
        explorerLog.clear()
        explorerProgress = null
        explorerSession = null
        explorerStore = store
        errorMessage = null
        explorerRunning = true

        explorerJob = scope.launch {
            try {
                // Listener callbacks run on whichever dispatcher `explorer.run`
                // is currently on — i.e. Dispatchers.IO. Mutating Compose
                // SnapshotStateList / mutableStateOf from the IO thread while
                // the UI measures the LazyColumn has been observed to crash
                // with "Index 0, size 0". We redirect every mutation to the
                // Main dispatcher so Compose state is only touched from one
                // thread.
                val mainScope = this
                val listener = object : Explorer.Listener {
                    override fun onLog(msg: String) {
                        mainScope.launch(Dispatchers.Main.immediate) {
                            explorerLog += msg
                            if (explorerLog.size > 500) explorerLog.removeAt(0)
                        }
                    }
                    override fun onProgress(progress: ExplorerProgress) {
                        mainScope.launch(Dispatchers.Main.immediate) {
                            explorerProgress = progress
                        }
                    }
                    override fun onSessionUpdated(session: ExplorationSession) {
                        mainScope.launch(Dispatchers.Main.immediate) {
                            explorerSession = session
                        }
                    }
                }
                val session = withContext(Dispatchers.IO) { explorer.run(listener) }
                explorerSession = session
            } catch (_: CancellationException) {
                explorerLog += strings.explorerCancelled
            } catch (e: Exception) {
                errorMessage = strings.explorerErrorFmt.format(e.message ?: e::class.simpleName.orEmpty())
            } finally {
                explorerRunning = false
            }
        }
    }

    fun stopExploration() {
        explorerJob?.cancel()
    }

    // --- Manual exploration --------------------------------------------------

    /**
     * Listener that funnels every ManualExplorer notification through the
     * Main dispatcher: the Compose state we mutate here (lists, bytearrays,
     * mutableStateOf-wrapped properties) cannot be safely touched from the
     * IO thread that executes the ADB calls.
     */
    private inner class ManualListenerImpl : ManualExplorer.Listener {
        override fun onLog(msg: String) {
            scope.launch(Dispatchers.Main.immediate) {
                manualLog += msg
                if (manualLog.size > 500) manualLog.removeAt(0)
            }
        }
        override fun onSessionUpdated(
            session: ExplorationSession,
            currentScreenshotPng: ByteArray?,
            currentRoot: com.salaun.tristan.uiautomator.model.UiNode?,
            currentStateId: String?,
        ) {
            scope.launch(Dispatchers.Main.immediate) {
                manualSession = session
                manualScreenshotPng = currentScreenshotPng
                manualRootNode = currentRoot
                manualCurrentStateId = currentStateId
            }
        }
        override fun onScrollCaptureReady(stitched: ScrollCapture.Stitched) {
            scope.launch(Dispatchers.Main.immediate) {
                manualScrollCapture = stitched
            }
        }
        override fun onScrollCaptureDismissed() {
            scope.launch(Dispatchers.Main.immediate) {
                manualScrollCapture = null
            }
        }
    }

    fun startManualExploration() {
        if (manualBusy || manualExplorer != null) return
        if (adbPath.isBlank()) { errorMessage = strings.explorerErrorAdbMissing; return }
        val pkg = explorerConfig.targetPackage.trim()
        if (pkg.isBlank()) { errorMessage = strings.explorerErrorNoPackage; return }
        val serial = selectedSerial
        val store = SessionStore.create(SessionStore.defaultRoot(), pkg)
        val driver = ManualExplorer(adb, serial, pkg, store)
        manualExplorer = driver
        manualStore = store
        manualSession = driver.session
        manualScreenshotPng = null
        manualRootNode = null
        manualCurrentStateId = null
        manualScrollCapture = null
        manualLog.clear()
        errorMessage = null
        runManualAction { driver.start(ManualListenerImpl()) }
    }

    fun manualTap(x: Int, y: Int) {
        val driver = manualExplorer ?: return
        runManualAction { driver.tap(x, y, ManualListenerImpl()) }
    }

    fun manualPressBack() {
        val driver = manualExplorer ?: return
        runManualAction { driver.pressBack(ManualListenerImpl()) }
    }

    fun manualRelaunch() {
        val driver = manualExplorer ?: return
        runManualAction { driver.relaunch(ManualListenerImpl()) }
    }

    fun manualRecapture() {
        val driver = manualExplorer ?: return
        runManualAction { driver.recapture(ManualListenerImpl()) }
    }

    fun manualCaptureScrollable() {
        val driver = manualExplorer ?: return
        runManualAction { driver.captureScrollable(ManualListenerImpl()) }
    }

    fun manualTapVirtual(vx: Int, vy: Int) {
        val driver = manualExplorer ?: return
        runManualAction { driver.tapVirtual(vx, vy, ManualListenerImpl()) }
    }

    fun manualExitScrollMode() {
        val driver = manualExplorer ?: return
        runManualAction { driver.exitScrollMode(ManualListenerImpl()) }
    }

    /**
     * Closes the manual session: persists, restores demo mode, then routes
     * the user to the graph view if at least one state was recorded so they
     * can immediately inspect what they captured.
     */
    fun endManualExploration() {
        val driver = manualExplorer ?: return
        scope.launch {
            manualBusy = true
            try {
                withContext(Dispatchers.IO) { driver.end(ManualListenerImpl()) }
                // Promote the manual session into `explorerSession` + `explorerStore`
                // so the Graph view (which reads those) shows what we just built.
                val session = driver.session
                explorerSession = session
                explorerStore = manualStore
                if (session.states.isNotEmpty()) screen = Screen.Graph
            } catch (e: Exception) {
                errorMessage = strings.explorerErrorFmt.format(e.message ?: e::class.simpleName.orEmpty())
            } finally {
                manualExplorer = null
                manualScrollCapture = null
                manualBusy = false
            }
        }
    }

    private fun runManualAction(action: suspend () -> Unit) {
        scope.launch {
            manualBusy = true
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (e: Exception) {
                errorMessage = strings.explorerErrorFmt.format(e.message ?: e::class.simpleName.orEmpty())
            } finally {
                manualBusy = false
            }
        }
    }

    fun openGraphForCurrentSession() {
        if (explorerSession != null) screen = Screen.Graph
    }

    // --- Graph editing -------------------------------------------------------

    /**
     * Removes the [ids] states from the current session: drops every transition
     * that touches them, deletes their on-disk screenshot/XML, and updates the
     * persisted layout overrides. Operates on the loaded session in place; the
     * `neverEqualPolicy` re-assignment at the end forces every Compose reader
     * to recompose against the mutated lists.
     */
    fun deleteGraphStates(ids: Set<String>) {
        if (ids.isEmpty()) return
        val session = explorerSession ?: return
        val store = explorerStore ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (id in ids) {
                        val state = session.states.firstOrNull { it.id == id } ?: continue
                        runCatching { java.io.File(store.baseDir, state.screenshotPath).delete() }
                        runCatching { java.io.File(store.baseDir, state.xmlPath).delete() }
                    }
                    session.transitions.removeAll { it.from in ids || it.to in ids }
                    session.states.removeAll { it.id in ids }
                    store.save(session)
                    val layout = store.loadLayout()
                    if (layout != null) {
                        store.saveLayout(GraphLayout(layout.positions.filterKeys { it !in ids }))
                    }
                }
                explorerSession = session
            } catch (e: Exception) {
                errorMessage = strings.captureErrorFmt.format(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /**
     * Merges the [ids] states into a single one — the primary being whichever
     * appears earliest in `session.states` ("le premier état"). Every transition
     * pointing to a merged-out state is rerouted onto the primary; every
     * transition leaving a merged-out state now leaves from the primary.
     * Self-references (primary→other or other→primary) collapse into loops.
     * The merged-out screenshots/XML are removed, layout overrides cleaned up,
     * and the session persisted to disk.
     */
    fun mergeGraphStates(ids: Set<String>) {
        if (ids.size < 2) return
        val session = explorerSession ?: return
        val store = explorerStore ?: return
        // The primary is the earliest-in-session-order state, which is also
        // the one whose screenshot we want to keep ("garder un seul
        // screenshot bien entendu : le premier état").
        val primary = session.states.firstOrNull { it.id in ids } ?: return
        val others = ids - primary.id
        if (others.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val rerouted = session.transitions.map { t ->
                        val newFrom = if (t.from in others) primary.id else t.from
                        val newTo = if (t.to in others) primary.id else t.to
                        if (newFrom == t.from && newTo == t.to) {
                            t
                        } else {
                            t.copy(
                                from = newFrom,
                                to = newTo,
                                // A rerouted transition that now connects the
                                // primary to itself is a loop, by definition.
                                loop = (newTo != null && newFrom == newTo),
                            )
                        }
                    }
                    session.transitions.clear()
                    session.transitions += rerouted

                    for (id in others) {
                        val state = session.states.firstOrNull { it.id == id } ?: continue
                        runCatching { java.io.File(store.baseDir, state.screenshotPath).delete() }
                        runCatching { java.io.File(store.baseDir, state.xmlPath).delete() }
                    }
                    session.states.removeAll { it.id in others }

                    store.save(session)
                    val layout = store.loadLayout()
                    if (layout != null) {
                        store.saveLayout(GraphLayout(layout.positions.filterKeys { it !in others }))
                    }
                }
                explorerSession = session
            } catch (e: Exception) {
                errorMessage = strings.captureErrorFmt.format(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    fun loadSessionFromDir(dir: java.io.File) {
        val loaded = SessionStore.load(dir) ?: run {
            errorMessage = strings.sessionsSessionUnreadableFmt.format(dir.absolutePath)
            return
        }
        explorerSession = loaded
        explorerStore = SessionStore(dir)
        screen = Screen.Graph
    }

    // --- Session management --------------------------------------------------

    val sessionsRoot: java.io.File get() = SessionStore.defaultRoot()

    fun listSessions(): List<SessionSummary> = SessionStore.listAll(sessionsRoot)

    fun deleteSession(dir: java.io.File) {
        try {
            if (!dir.canonicalPath.startsWith(sessionsRoot.canonicalPath)) {
                errorMessage = strings.sessionsDeleteOutside
                return
            }
            if (dir.deleteRecursively()) {
                statusMessage = strings.sessionsDeletedFmt.format(dir.name)
                if (explorerStore?.baseDir?.canonicalPath == dir.canonicalPath) {
                    explorerSession = null
                    explorerStore = null
                }
            } else {
                errorMessage = strings.sessionsDeleteFailedFmt.format(dir.name)
            }
        } catch (e: Exception) {
            errorMessage = strings.captureErrorFmt.format(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    fun exportSession(dir: java.io.File, outFile: java.io.File) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SessionZip.exportToZip(dir, outFile) }
                statusMessage = strings.sessionsExportedFmt.format(outFile.absolutePath)
            } catch (e: Exception) {
                errorMessage = strings.sessionsExportFailedFmt.format(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /**
     * Renders the currently-loaded graph as a self-contained HTML viewer
     * (cards + edges + side panel, view-only) and writes it to [outFile].
     * The screenshots are inlined as base64 so the file works offline.
     */
    fun exportGraphHtml(outFile: java.io.File) {
        val session = explorerSession ?: run {
            errorMessage = strings.graphNoSession
            return
        }
        val store = explorerStore ?: run {
            errorMessage = strings.graphNoSession
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.salaun.tristan.uiautomator.ui.GraphExportHtml.exportTo(session, store, outFile)
                }
                statusMessage = strings.graphHtmlExportedFmt.format(outFile.absolutePath)
            } catch (e: Exception) {
                errorMessage = strings.graphHtmlExportFailedFmt.format(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    fun importSession(zipFile: java.io.File, openAfter: Boolean = true) {
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    SessionZip.importFromZip(zipFile, sessionsRoot)
                }
                statusMessage = strings.sessionsImportedFmt.format(imported.name)
                if (openAfter) loadSessionFromDir(imported)
            } catch (e: Exception) {
                errorMessage = strings.sessionsImportFailedFmt.format(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }
}

// --- Exploration config persistence helpers ---------------------------------

private fun loadExplorationConfig(settings: AppSettings): ExplorationConfig {
    val default = ExplorationConfig(targetPackage = "")
    return ExplorationConfig(
        targetPackage = settings.explorationTargetPackage.orEmpty(),
        maxStates = settings.explorationMaxStates ?: default.maxStates,
        maxDepth = settings.explorationMaxDepth ?: default.maxDepth,
        maxClickablesPerState = settings.explorationMaxClickablesPerState ?: default.maxClickablesPerState,
        settleDelayMs = settings.explorationSettleDelayMs ?: default.settleDelayMs,
        idleMaxWaitMs = settings.explorationIdleMaxWaitMs ?: default.idleMaxWaitMs,
    )
}

private fun persistExplorationConfig(settings: AppSettings, cfg: ExplorationConfig) {
    settings.explorationTargetPackage = cfg.targetPackage.takeIf { it.isNotBlank() }
    settings.explorationMaxStates = cfg.maxStates
    settings.explorationMaxDepth = cfg.maxDepth
    settings.explorationMaxClickablesPerState = cfg.maxClickablesPerState
    settings.explorationSettleDelayMs = cfg.settleDelayMs
    settings.explorationIdleMaxWaitMs = cfg.idleMaxWaitMs
}
