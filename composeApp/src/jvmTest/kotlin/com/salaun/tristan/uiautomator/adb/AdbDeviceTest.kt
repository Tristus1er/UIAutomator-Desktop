package com.salaun.tristan.uiautomator.adb

import kotlin.test.Test
import kotlin.test.assertEquals

class AdbDeviceTest {

    @Test
    fun `displayName uses the device field when present`() {
        val d = AdbDevice(
            serial = "25ad977b",
            description = "product:OnePlus8Pro_EEA model:IN2023 device:OnePlus8Pro transport_id:2",
        )
        assertEquals("OnePlus8Pro", d.deviceName)
        assertEquals("OnePlus8Pro (25ad977b)", d.displayName)
    }

    @Test
    fun `displayName falls back to the serial when the device field is missing`() {
        val d = AdbDevice(serial = "emulator-5554", description = "")
        assertEquals("emulator-5554", d.deviceName)
        assertEquals("emulator-5554", d.displayName)
    }

    @Test
    fun `displayName stops at whitespace inside the device field`() {
        val d = AdbDevice(
            serial = "SERIAL",
            description = "device:foo transport_id:7",
        )
        assertEquals("foo", d.deviceName)
    }
}
