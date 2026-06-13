import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.uiTest)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.kotlinx.serializationJson)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlin.reflect)
            implementation(libs.junit)
            implementation(libs.cucumber.java)
            implementation(libs.cucumber.junit)
        }
    }
}


compose.desktop {
    application {
        mainClass = "com.salaun.tristan.uiautomator.MainKt"

        // The release build runs ProGuard (shrink + optimize). Without keep
        // rules it strips the reflectively-reached kotlinx.serialization
        // serializers and the coroutines Swing main dispatcher, producing an
        // installer that builds fine but crashes at runtime. Obfuscation stays
        // off: it adds renaming risk for no real benefit on a desktop app, and
        // a non-obfuscated stack trace is worth far more when diagnosing a
        // field report. See compose-desktop.pro for the rationale.
        buildTypes.release.proguard {
            obfuscate.set(false)
            optimize.set(true)
            configurationFiles.from(project.file("compose-desktop.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "UIAutomator-Desktop"
            // Version follows the Git tag in CI (passed as -PappVersion=1.2.3,
            // the "v" prefix stripped by the workflow). jpackage requires a
            // strict MAJOR.MINOR.PATCH with no leading zeros / suffix, so the
            // default stays a plain triple for local builds.
            packageVersion = (project.findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"
        }
    }
}
