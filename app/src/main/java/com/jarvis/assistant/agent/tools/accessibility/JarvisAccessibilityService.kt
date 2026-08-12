package com.jarvis.assistant.agent.tools.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * JarvisAccessibilityService
 * Позволяет JARVIS взаимодействовать с экранными элементами, делать системные скриншоты
 * и считывать UI-контекст без касания экрана.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"

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

        /**
         * Извлекает видимый текстовый контент текущего экрана
         */
        fun extractScreenText(): String {
            val rootNode = instance?.rootInActiveWindow ?: return ""
            val sb = StringBuilder()
            traverseNode(rootNode, sb)
            return sb.toString().trim()
        }

        private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder) {
            if (node == null) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                sb.append(text).append(" ")
            } else if (!desc.isNullOrEmpty()) {
                sb.append(desc).append(" ")
            }
            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), sb)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "JarvisAccessibilityService connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = AccessibilityEvent.eventTypeToString(event.eventType)
        val pkg = event.packageName?.toString() ?: ""
        Log.d(TAG, "onAccessibilityEvent: type=$eventType, package=$pkg")
    }

    override fun onInterrupt() {
        Log.d(TAG, "JarvisAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "JarvisAccessibilityService destroyed")
        instance = null
    }
}
