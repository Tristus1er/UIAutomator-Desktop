package com.salaun.tristan.uiautomator.bdd

import com.salaun.tristan.uiautomator.explorer.ClickableRef
import com.salaun.tristan.uiautomator.explorer.ExplorationConfig
import com.salaun.tristan.uiautomator.explorer.ExplorationSession
import com.salaun.tristan.uiautomator.explorer.SerialBounds
import com.salaun.tristan.uiautomator.explorer.SessionStore
import com.salaun.tristan.uiautomator.explorer.StateEntry
import com.salaun.tristan.uiautomator.explorer.TransitionEntry
import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Step definitions backing `exploration_session_roundtrip.feature`. */
class SessionRoundtripSteps {

    private var built: ExplorationSession? = null
    private var reloaded: ExplorationSession? = null
    private var workDir: File? = null

    @Given("a session targeting {string} with {int} states and {int} transition(s)")
    fun buildSession(pkg: String, stateCount: Int, transitionCount: Int) {
        require(stateCount == 2 && transitionCount == 1) {
            "this step builds the canonical 2-state / 1-transition fixture only"
        }
        val action = ClickableRef(
            resourceId = "com.example.app:id/sign_in",
            className = "android.widget.Button",
            text = "Sign In",
            contentDesc = "",
            bounds = SerialBounds(100, 800, 980, 960),
            tapX = 540, tapY = 880,
        )
        val s0 = StateEntry(
            id = "S0",
            fingerprint = "fp-0",
            packageName = pkg,
            depth = 0,
            screenshotPath = "states/S0.png",
            xmlPath = "states/S0.xml",
            clickables = listOf(action),
            pathFromRoot = emptyList(),
        )
        val s1 = s0.copy(id = "S1", fingerprint = "fp-1", depth = 1, pathFromRoot = listOf(action))
        built = ExplorationSession(
            id = "roundtrip-1",
            targetPackage = pkg,
            startedAt = 1_700_000_000_000,
            config = ExplorationConfig(targetPackage = pkg),
            states = mutableListOf(s0, s1),
            transitions = mutableListOf(TransitionEntry(from = "S0", to = "S1", action = action)),
        )
    }

    @When("I save it to a temporary directory")
    fun save() {
        val dir = Files.createTempDirectory("uiautomator-session-").toFile()
        workDir = dir
        SessionStore(dir).save(assertNotNull(built))
    }

    @And("I read it back from disk")
    fun reload() {
        val dir = assertNotNull(workDir)
        reloaded = SessionStore.load(dir)
    }

    @Then("the reloaded session has {int} states and {int} transition(s)")
    fun compareCounts(stateCount: Int, transitionCount: Int) {
        val r = assertNotNull(reloaded, "session was not reloaded")
        assertEquals(stateCount, r.states.size)
        assertEquals(transitionCount, r.transitions.size)
    }

    @Then("the action label of the only transition is {string}")
    fun compareLabel(expected: String) {
        val r = assertNotNull(reloaded)
        assertEquals(expected, r.transitions.single().action.label)
    }

    @After
    fun cleanup() {
        workDir?.deleteRecursively()
    }
}
