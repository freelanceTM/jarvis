package com.jarvis.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SupplyChainConfigurationTest {
    @Test
    fun `all GitHub Actions are immutable and workflows are least privilege`() {
        val root = repositoryRoot()
        val workflows = Files.walk(root.resolve(".github/workflows")).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".yml") }
                .sorted()
                .toList()
        }
        assertTrue(workflows.isNotEmpty())

        val actionRef = Regex("""uses:\s*([^\s#]+)""")
        for (workflow in workflows) {
            val text = Files.readString(workflow)
            assertTrue("missing read-only permissions: $workflow", text.contains("permissions:\n  contents: read"))
            assertFalse("unsafe pull_request_target: $workflow", text.contains("pull_request_target:"))
            // Инвариант: workflow, выполняющийся на pull_request, не имеет доступа
            // к репозиторным секретам (форк-атака через изменённый workflow-код).
            // Dispatch/push-only workflow (например, release.yml) может использовать
            // secrets легитимно — проверяем запрет ТОЛЬКО для PR-триггеров.
            val onBlock = Regex("(?ms)^on:.*?(?=^[A-Za-z-]+:|\\z)").find(text)?.value.orEmpty()
            val runsOnPullRequest = onBlock.contains("pull_request")
            if (runsOnPullRequest) {
                assertFalse("PR workflow exposes repository secrets: $workflow", text.contains("secrets."))
            }
            for (match in actionRef.findAll(text)) {
                val value = match.groupValues[1]
                if (value.startsWith("./")) continue
                val ref = value.substringAfter('@', missingDelimiterValue = "")
                assertTrue("mutable action reference $value in $workflow", ref.matches(Regex("[0-9a-f]{40}")))
            }
        }
    }

    @Test
    fun `security workflow covers history dependencies image policy and SBOM`() {
        val root = repositoryRoot()
        val workflow = Files.readString(root.resolve(".github/workflows/security.yml"))
        val dependabot = Files.readString(root.resolve(".github/dependabot.yml"))

        assertTrue(workflow.contains("fetch-depth: 0"))
        assertTrue(workflow.contains("run-gitleaks-history.sh"))
        assertTrue(workflow.contains("dependency-review-action@"))
        assertTrue(workflow.contains("fail-on-severity: high"))
        assertTrue(workflow.contains("image-ref: jarvis-server:security"))
        assertTrue(workflow.contains("severity: HIGH,CRITICAL"))
        assertTrue(workflow.contains("exit-code: '1'"))
        assertTrue(workflow.contains("format: cyclonedx-json"))
        assertTrue(workflow.contains("jarvis-server-image.cdx.json"))
        assertTrue(dependabot.contains("package-ecosystem: gradle"))
        assertTrue(dependabot.contains("package-ecosystem: github-actions"))
        assertTrue(dependabot.contains("package-ecosystem: docker"))
    }

    @Test
    fun `wrapper and production images are immutable`() {
        val root = repositoryRoot()
        val wrapper = Files.readString(root.resolve("gradle/wrapper/gradle-wrapper.properties"))
        val dockerfile = Files.readString(root.resolve("deploy/server.Dockerfile"))
        val compose = Files.readString(root.resolve("deploy/docker-compose.production.yml"))

        assertTrue(wrapper.contains("distributionSha256Sum="))
        assertTrue(Files.exists(root.resolve("app/gradle.lockfile")))
        assertTrue(Files.exists(root.resolve("server/gradle.lockfile")))
        assertTrue(Files.exists(root.resolve("gradle/verification-metadata.xml")))
        for (line in dockerfile.lineSequence().filter { it.startsWith("FROM ") }) {
            assertTrue("unpinned Dockerfile base: $line", line.contains("@sha256:"))
        }
        for (line in compose.lineSequence().map(String::trim).filter { it.startsWith("image:") }) {
            assertTrue("unpinned Compose image: $line", line.contains("@sha256:"))
        }
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
