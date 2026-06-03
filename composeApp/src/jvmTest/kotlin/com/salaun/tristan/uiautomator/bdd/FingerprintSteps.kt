package com.salaun.tristan.uiautomator.bdd

import com.salaun.tristan.uiautomator.explorer.StateOps
import com.salaun.tristan.uiautomator.model.DumpParser
import com.salaun.tristan.uiautomator.testutil.Fixtures
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** Step definitions backing `state_identification.feature`. */
class FingerprintSteps {

    private val fingerprints = HashMap<String, String>()

    @Given("the dump {string} is loaded as {string}")
    fun load(dumpName: String, alias: String) {
        val tree = assertNotNull(DumpParser.parse(Fixtures.dump(dumpName)), "$dumpName did not parse")
        fingerprints[alias] = StateOps.fingerprint(tree)
    }

    @Then("{string} and {string} share the same fingerprint")
    fun same(a: String, b: String) {
        val fa = assertNotNull(fingerprints[a], "no fingerprint for $a")
        val fb = assertNotNull(fingerprints[b], "no fingerprint for $b")
        assertEquals(fa, fb)
    }

    @Then("{string} and {string} have different fingerprints")
    fun different(a: String, b: String) {
        val fa = assertNotNull(fingerprints[a], "no fingerprint for $a")
        val fb = assertNotNull(fingerprints[b], "no fingerprint for $b")
        assertNotEquals(fa, fb)
    }
}
