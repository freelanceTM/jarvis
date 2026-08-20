pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "JARVIS"
include(":app")

// Этап 3 — JARVIS API (server-side AI orchestration).
// Android-сборка (:app) от этого модуля не зависит и собирается независимо.
include(":server")
