package com.jarvis.assistant.voice.service

import android.Manifest
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.presentation.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class VoiceServiceLifecycleInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun missingMicrophonePermissionDoesNotRequestServiceStart() {
        val denied = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                PackageManager.PERMISSION_DENIED

            override fun startForegroundService(service: Intent): ComponentName? =
                throw AssertionError("startForegroundService must not be called")

            override fun startService(service: Intent): ComponentName? =
                throw AssertionError("startService must not be called")
        }
        assertFalse(JarvisVoiceService.start(denied))
    }

    @Test
    fun foregroundStartAcquiresWakeLockAndStopReleasesResources() {
        assumeTrue(
            "grant RECORD_AUDIO before the real service lifecycle test",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("foreground service request was rejected", JarvisVoiceService.start(context))
            assertTrue(
                "voice service did not become active",
                eventually { shell("dumpsys activity services ${context.packageName}")
                    .contains(JarvisVoiceService::class.java.name) }
            )
            assertTrue(
                "partial wake lock not observed while service is active",
                eventually { shell("dumpsys power").contains("JARVIS:BackgroundVoiceWakeLock") }
            )

            JarvisVoiceService.stop(context)
            assertTrue(
                "voice service remained active after stop",
                eventually { !shell("dumpsys activity services ${context.packageName}")
                    .contains(JarvisVoiceService::class.java.name) }
            )
            assertFalse(
                "wake lock leaked after service destruction",
                shell("dumpsys power").contains("JARVIS:BackgroundVoiceWakeLock")
            )
        }
    }

    private fun eventually(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return true
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation
            .executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            .also { descriptor.close() }
    }
}
