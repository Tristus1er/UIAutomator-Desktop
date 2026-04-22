package com.salaun.tristan.uiautomator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.runtime.mutableStateSetOf
import com.salaun.tristan.uiautomator.adb.AdbDevice
import com.salaun.tristan.uiautomator.adb.AdbError
import com.salaun.tristan.uiautomator.adb.AdbService
import com.salaun.tristan.uiautomator.model.DumpParser
import com.salaun.tristan.uiautomator.model.UiNode
import com.salaun.tristan.uiautomator.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Main, Settings }

class AppState(
    val settings: AppSettings,
    val scope: CoroutineScope,
) {
    val adb: AdbService = AdbService(settings.adbPath.orEmpty())

    var screen: Screen by mutableStateOf(Screen.Main)
    var adbPath: String by mutableStateOf(settings.adbPath.orEmpty())
    var adbPathStatus: String by mutableStateOf("")

    val devices: SnapshotStateList<AdbDevice> = mutableStateListOf()
    var selectedSerial: String? by mutableStateOf(settings.lastDeviceSerial)

    var busy: Boolean by mutableStateOf(false)
    var statusMessage: String by mutableStateOf("")
    var errorMessage: String? by mutableStateOf(null)

    var screenshotPng: ByteArray? by mutableStateOf(null)
    var xmlText: String? by mutableStateOf(null)
    var rootNode: UiNode? by mutableStateOf(null)
    val expanded: SnapshotStateSet<UiNode> = mutableStateSetOf()
    var selectedNode: UiNode? by mutableStateOf(null)

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
            statusMessage = "Autodétection ADB…"
            val detected = withContext(Dispatchers.IO) { com.salaun.tristan.uiautomator.adb.AdbAutoDetect.detect() }
            if (detected == null) {
                adbPathStatus = "ADB introuvable automatiquement."
                statusMessage = ""
            } else {
                applyAdbPath(detected)
                statusMessage = "ADB détecté : $detected"
            }
        }
    }

    private fun refreshAdbStatus() {
        if (adbPath.isBlank()) {
            adbPathStatus = "Aucun chemin ADB configuré."
            return
        }
        scope.launch {
            adbPathStatus = "Vérification…"
            try {
                val v = adb.version()
                adbPathStatus = "OK — $v"
            } catch (e: Exception) {
                adbPathStatus = "Échec : ${e.message ?: e::class.simpleName}"
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

    fun capture() {
        if (adbPath.isBlank()) {
            errorMessage = "Configurez d'abord le chemin ADB dans les paramètres."
            return
        }
        val serial = selectedSerial
        if (serial == null && devices.isEmpty()) {
            errorMessage = "Aucun device détecté. Branchez un téléphone puis actualisez."
            return
        }
        scope.launch {
            busy = true
            errorMessage = null
            statusMessage = "Capture en cours…"
            try {
                val png = adb.screenshotPng(serial)
                statusMessage = "Dump XML en cours…"
                val xml = adb.dumpUiXml(serial)
                val tree = withContext(Dispatchers.Default) { DumpParser.parse(xml) }
                screenshotPng = png
                xmlText = xml
                rootNode = tree
                selectedNode = null
                expanded.clear()
                tree?.let { expanded += it }
                statusMessage = "Capture OK (${png.size / 1024} KB, ${xml.length} chars)"
            } catch (e: AdbError) {
                errorMessage = e.message
                statusMessage = ""
            } catch (e: Exception) {
                errorMessage = "Erreur : ${e.message ?: e::class.simpleName}"
                statusMessage = ""
            } finally {
                busy = false
            }
        }
    }

    fun toggle(node: UiNode) {
        if (node in expanded) expanded -= node else expanded += node
    }

    fun selectNode(node: UiNode?) {
        selectedNode = node
        if (node != null) {
            var p = node.parent
            while (p != null) { expanded += p; p = p.parent }
        }
    }
}
