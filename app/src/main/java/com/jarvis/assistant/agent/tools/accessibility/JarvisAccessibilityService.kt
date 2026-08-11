package com.jarvis.assistant.agent.tools.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null

        fun performGlobalAction(action: Int): Boolean {
            return instance?.performGlobalAction(action) ?: false
        }

        fun takeSystemScreenshot(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                instance?.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) ?: false
            } else {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    override fun onInterrupt() { }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
