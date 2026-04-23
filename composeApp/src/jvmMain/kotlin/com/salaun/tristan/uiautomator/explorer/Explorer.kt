package com.salaun.tristan.uiautomator.explorer

import com.salaun.tristan.uiautomator.adb.AdbService
import com.salaun.tristan.uiautomator.model.DumpParser
import com.salaun.tristan.uiautomator.model.UiNode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

class Explorer(
    private val adb: AdbService,
    private val serial: String?,
    private val config: ExplorationConfig,
    private val store: SessionStore,
) {

    interface Listener {
        fun onLog(msg: String)
        fun onProgress(progress: ExplorerProgress)
        fun onSessionUpdated(session: ExplorationSession)
    }

    private data class Task(val stateId: String, val path: List<ClickableRef>)
    private data class Snapshot(val png: ByteArray, val xml: String, val root: UiNode, val pkg: String, val fingerprint: String)

    suspend fun run(listener: Listener): ExplorationSession = coroutineScope {
        val session = ExplorationSession(
            id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()),
            targetPackage = config.targetPackage,
            startedAt = System.currentTimeMillis(),
            config = config,
        )
        var processed = 0
        var planned = 0
        val fingerprintToId = HashMap<String, String>()

        fun persist() {
            store.save(session)
            listener.onSessionUpdated(session)
        }

        fun emit(stateId: String?, action: String?) {
            listener.onProgress(
                ExplorerProgress(
                    discoveredStates = session.states.size,
                    processedActions = processed,
                    plannedActions = planned,
                    currentStateId = stateId,
                    currentActionLabel = action,
                )
            )
        }

        listener.onLog("Session : ${store.baseDir.absolutePath}")
        listener.onLog("Lancement de ${config.targetPackage}…")
        adb.launchApp(serial, config.targetPackage)
        delay(config.settleDelayMs + 500)

        val rootSnap = capture()
        if (rootSnap.pkg != config.targetPackage && config.targetPackage.isNotBlank()) {
            listener.onLog("⚠ Package courant : « ${rootSnap.pkg} » — exploration interrompue.")
            session.endedAt = System.currentTimeMillis()
            persist()
            return@coroutineScope session
        }

        val s0 = registerState(session, rootSnap, depth = 0, path = emptyList())
        fingerprintToId[s0.fingerprint] = s0.id
        planned += s0.clickables.size
        persist()
        listener.onLog("État initial : ${s0.id} (${s0.clickables.size} actions)")

        val queue = ArrayDeque<Task>()
        queue += Task(s0.id, emptyList())

        while (queue.isNotEmpty() && session.states.size < config.maxStates) {
            coroutineContext.ensureActive()
            val task = queue.removeFirst()
            val source = session.states.first { it.id == task.stateId }
            listener.onLog("▶ ${source.id} — ${source.clickables.size} actions, profondeur ${source.depth}")

            for (click in source.clickables) {
                coroutineContext.ensureActive()
                val already = session.transitions.any { t ->
                    t.from == source.id && t.action.bounds == click.bounds && t.action.resourceId == click.resourceId
                }
                if (already) { processed++; emit(source.id, click.label); continue }

                if (!reachState(source, task.path)) {
                    listener.onLog("  ✘ Impossible de rejoindre ${source.id}, action ignorée.")
                    processed++; emit(source.id, click.label); continue
                }

                listener.onLog("  • tap « ${click.label} » @(${click.tapX},${click.tapY})")
                adb.inputTap(serial, click.tapX, click.tapY)
                delay(config.settleDelayMs)

                val after = capture()
                if (after.pkg != config.targetPackage) {
                    listener.onLog("    → sortie vers ${after.pkg}, BACK")
                    session.transitions += TransitionEntry(source.id, null, click, leftApp = true)
                    runCatching { adb.pressBack(serial) }
                    delay(config.settleDelayMs)
                    processed++; emit(source.id, click.label); persist(); continue
                }

                val knownId = fingerprintToId[after.fingerprint]
                if (knownId != null) {
                    val loop = knownId == source.id
                    listener.onLog("    → état connu ${knownId}${if (loop) " (boucle)" else ""}")
                    session.transitions += TransitionEntry(source.id, knownId, click, loop = loop)
                } else {
                    val newDepth = source.depth + 1
                    val newState = registerState(session, after, depth = newDepth, path = task.path + click)
                    fingerprintToId[after.fingerprint] = newState.id
                    planned += newState.clickables.size
                    session.transitions += TransitionEntry(source.id, newState.id, click)
                    listener.onLog("    → nouvel état ${newState.id} (${newState.clickables.size} actions)")
                    if (newDepth < config.maxDepth && session.states.size < config.maxStates) {
                        queue += Task(newState.id, task.path + click)
                    }
                }
                processed++
                emit(source.id, click.label)
                persist()
            }
        }

        session.endedAt = System.currentTimeMillis()
        persist()
        listener.onLog("Exploration terminée : ${session.states.size} états, ${session.transitions.size} transitions.")
        session
    }

    private suspend fun capture(): Snapshot {
        val png = adb.screenshotPng(serial)
        val xml = adb.dumpUiXml(serial)
        val root = DumpParser.parse(xml) ?: error("Dump XML invalide")
        val pkg = StateOps.dominantPackage(root)
        val fp = StateOps.fingerprint(root)
        return Snapshot(png, xml, root, pkg, fp)
    }

    private fun registerState(
        session: ExplorationSession,
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
        return entry
    }

    private suspend fun reachState(source: StateEntry, path: List<ClickableRef>): Boolean {
        if (config.relaunchBetweenActions) {
            adb.launchApp(serial, config.targetPackage)
            delay(config.settleDelayMs + 300)
        }
        for (step in path) {
            adb.inputTap(serial, step.tapX, step.tapY)
            delay(config.settleDelayMs)
        }
        val current = runCatching { capture() }.getOrNull() ?: return false
        return current.fingerprint == source.fingerprint
    }
}
