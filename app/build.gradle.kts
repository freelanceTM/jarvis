plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Экспорт схем Room в app/schemas — обязательно для миграций и MigrationTestHelper
// (см. JarvisMigrations.kt, пункт аудита #7).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Этап 2: MediaPipe поставляет нативные библиотеки для 4 ABI
        // (arm64-v8a 26 МБ, armeabi-v7a 19 МБ, x86 32 МБ, x86_64 29 МБ).
        // Исключаем x86 — 32-битных x86-устройств и эмуляторов практически
        // не осталось; x86_64 оставляем ради эмулятора на CI/разработке.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    testOptions {
        unitTests {
            // android.util.Log и прочие android.jar-заглушки возвращают значения
            // по умолчанию вместо выброса "not mocked" в чистых JVM-тестах.
            isReturnDefaultValues = true
        }
    }

    // Пункт аудита #12: lint блокирует НОВЫЕ warnings/errors.
    // Текущие 86 pre-existing warnings зафиксированы в lint-baseline.xml —
    // они не ломают сборку, но любой НОВЫЙ warning теперь фейлит lint.
    lint {
        warningsAsErrors = true
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Этап 2 — Local AI: MediaPipe LLM Inference (on-device Gemma 3 1B).
    // Native libs увеличивают APK: ~26 МБ (arm64-v8a). Сама модель (~529 МБ)
    // в APK НЕ входит — см. docs/LOCAL_AI.md.
    implementation(libs.mediapipe.tasks.genai)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)

    // Инструментальные тесты (androidTest): миграции Room, критичные потоки.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
