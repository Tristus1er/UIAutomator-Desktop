package com.salaun.tristan.uiautomator.testutil

/** Shared test helpers: reads the fixtures bundled under `src/jvmTest/resources/fixtures`. */
object Fixtures {

    /** Loads the given fixture file from the test classpath and returns its text. */
    fun dump(name: String): String {
        val path = "/fixtures/$name"
        val stream = Fixtures::class.java.getResourceAsStream(path)
            ?: error("Fixture not found on classpath: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
