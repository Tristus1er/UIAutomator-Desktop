package com.salaun.tristan.uiautomator.adb

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertFalse

class AdbServiceVerifyPathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `verifyPath rejects a non-existing path`() {
        assertFalse(AdbService.verifyPath("/definitely/does/not/exist/adb"))
    }

    @Test
    fun `verifyPath rejects a regular file that is not an executable adb`() {
        val bogus = tmp.newFile("fake-adb")
        bogus.writeText("I am not adb")
        // Even if the OS lets us 'execute' the file, running it with `version`
        // exits non-zero so verifyPath should reject it.
        assertFalse(AdbService.verifyPath(bogus.absolutePath))
    }
}
