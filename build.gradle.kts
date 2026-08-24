plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    // :server — JVM-модуль (Этап 3). Версия совпадает с kotlin.android.
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

allprojects {
    dependencyLocking {
        // Every resolved direct/transitive dependency must be reviewable in VCS.
        lockAllConfigurations()
    }
}

tasks.register("phase3Coverage") {
    group = "verification"
    description = "Runs Android JVM and server tests and generates JaCoCo reports."
    dependsOn(
        ":app:jacocoDevDebugCoverageVerification",
        ":server:jacocoTestReport",
        ":server:jacocoTestCoverageVerification"
    )
}

tasks.register("phase3StaticAnalysis") {
    group = "verification"
    description = "Runs Detekt for Android and server Kotlin sources."
    dependsOn(":app:detekt", ":server:detekt")
}

tasks.register("phase3Quality") {
    group = "verification"
    description = "Runs static analysis, coverage reports and Android lint."
    dependsOn("phase3StaticAnalysis", "phase3Coverage", ":app:lintDevDebug")
}
