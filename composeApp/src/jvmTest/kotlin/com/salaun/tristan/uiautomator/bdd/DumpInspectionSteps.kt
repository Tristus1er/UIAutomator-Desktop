package com.salaun.tristan.uiautomator.bdd

import com.salaun.tristan.uiautomator.explorer.ClickableRef
import com.salaun.tristan.uiautomator.explorer.StateOps
import com.salaun.tristan.uiautomator.model.DumpParser
import com.salaun.tristan.uiautomator.model.UiNode
import com.salaun.tristan.uiautomator.testutil.Fixtures
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Step definitions backing `ui_dump_inspection.feature`. */
class DumpInspectionSteps {

    private var root: UiNode? = null
    private var hit: UiNode? = null
    private var hitExecuted: Boolean = false
    private var actions: List<ClickableRef> = emptyList()

    @Given("the dump {string} is loaded")
    fun loadDump(name: String) {
        root = assertNotNull(DumpParser.parse(Fixtures.dump(name)), "dump $name did not parse")
    }

    @Then("the tree has {int} nodes")
    fun treeSize(expected: Int) {
        val r = assertNotNull(root)
        var count = 0
        r.walk { count++ }
        assertEquals(expected, count)
    }

    @Then("the root package is {string}")
    fun rootPackage(expected: String) {
        assertEquals(expected, assertNotNull(root).packageName)
    }

    @When("I pick the element under the point {int}, {int}")
    fun pick(x: Int, y: Int) {
        hit = assertNotNull(root).findSmallestAt(x, y)
        hitExecuted = true
    }

    @Then("the selected element has resource id {string}")
    fun selectedResourceId(expected: String) {
        val h = assertNotNull(hit, "no element was selected")
        assertEquals(expected, h.resourceId)
    }

    @Then("nothing is selected")
    fun nothingSelected() {
        assertTrue(hitExecuted, "a hit-test must have been requested first")
        assertNull(hit)
    }

    @When("I collect clickable actions for package {string} with a limit of {int}")
    fun collectActions(pkg: String, limit: Int) {
        actions = StateOps.collectClickables(assertNotNull(root), pkgFilter = pkg, max = limit)
    }

    @Then("{int} actions are available")
    fun actionCount(expected: Int) {
        assertEquals(expected, actions.size)
    }

    @Then("every action targets a node whose resource id ends with {string} or {string}")
    fun actionsTargetEither(a: String, b: String) {
        assertTrue(actions.isNotEmpty(), "expected at least one action to check")
        for (action in actions) {
            val id = action.resourceId
            assertTrue(
                id.endsWith("/$a") || id.endsWith("/$b") || id.endsWith(a) || id.endsWith(b),
                "unexpected action target: $id",
            )
        }
    }
}
