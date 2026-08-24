package com.jarvis.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AndroidFlavorConfigurationTest {
    @Test
    fun `Android environments are explicit and production cannot be overridden`() {
        val root = repositoryRoot()
        val gradle = Files.readString(root.resolve("app/build.gradle.kts"))
        val constants = Files.readString(root.resolve(
            "app/src/main/java/com/jarvis/assistant/core/constants/AppConstants.kt"
        ))
        val mainNetwork = Files.readString(root.resolve(
            "app/src/main/res/xml/network_security_config.xml"
        ))
        val devNetwork = Files.readString(root.resolve(
            "app/src/dev/res/xml/network_security_config.xml"
        ))

        for (flavor in listOf("dev", "staging", "prod")) {
            assertTrue(gradle.contains("create(\"$flavor\")"))
        }
        assertTrue(gradle.contains("val productionApiUrl = \"https://api.jarvis.ai\""))
        assertFalse(gradle.contains("gradleProperty(\"JARVIS_PROD_API_BASE_URL\")"))
        assertTrue(constants.contains("BuildConfig.JARVIS_API_BASE_URL"))
        assertFalse(constants.contains("const val JARVIS_API_BASE_URL"))
        assertTrue(mainNetwork.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse(mainNetwork.contains("10.0.2.2"))
        assertTrue(devNetwork.contains("10.0.2.2"))
    }

    @Test
    fun `release uses R8 resource shrinking and endpoint artifact validation`() {
        val root = repositoryRoot()
        val gradle = Files.readString(root.resolve("app/build.gradle.kts"))
        val script = Files.readString(root.resolve("scripts/verify-android-flavor-apk.sh"))

        assertTrue(gradle.contains("isMinifyEnabled = true"))
        assertTrue(gradle.contains("isShrinkResources = true"))
        assertTrue(script.contains("staging-api.jarvis.ai"))
        assertTrue(script.contains("10.0.2.2:8080"))
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(5) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Repository root not found")
    }
}
