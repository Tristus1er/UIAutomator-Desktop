package com.salaun.tristan.uiautomator.bdd

import io.cucumber.junit.Cucumber
import io.cucumber.junit.CucumberOptions
import org.junit.runner.RunWith

/**
 * JUnit 4 entry point that executes every `.feature` file shipped under
 * `src/jvmTest/resources/features` and binds the scenarios to the step
 * definitions declared in this package.
 */
@RunWith(Cucumber::class)
@CucumberOptions(
    features = ["classpath:features"],
    glue = ["com.salaun.tristan.uiautomator.bdd"],
    plugin = ["pretty", "summary"],
    monochrome = true,
)
class RunCucumberTest
