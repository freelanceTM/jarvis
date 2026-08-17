package com.jarvis.assistant.agent.tools.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * JarvisAccessibilityService
 * Позволяет JARVIS взаимодействовать с экранными элементами, делать системные скриншоты,
 * автономно переключать плитки быстрых настроек (Quick Settings), считывать UI-контекст и нажимать кнопки без касания экрана.
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
         * Автономное переключение плитки в шторке быстрых настроек (Quick Settings) без касания экрана:
         * 1. Раскрывает шторку быстрых настроек
         * 2. Ищет плитку по ключевым словам ("Wi-Fi", "Bluetooth", "Фонарик")
         * 3. Нажимает ACTION_CLICK
         * 4. Закрывает шторку обратно (GLOBAL_ACTION_BACK)
         */
        suspend fun toggleQuickSettingTile(tileKeywords: List<String>): Boolean {
            val service = instance ?: return false

            // 1. Открываем Quick Settings
            service.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            delay(250)

            val rootNode = service.rootInActiveWindow
            var clicked = false

            if (rootNode != null) {
                for (kw in tileKeywords) {
                    val target = kw.lowercase().trim()
                    val node = findClickableNodeByText(rootNode, target)
                    if (node != null) {
                        clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "Quick setting tile '$kw' clicked: $clicked")
                        break
                    }
                }
            }

            // 2. Закрываем шторку обратно
            delay(150)
            service.performGlobalAction(GLOBAL_ACTION_BACK)
            return clicked
        }

        /**
         * Считывает весь текстовый контент со всех AccessibilityNodeInfo текущего экрана
         */
        fun getScreenContent(): String {
            val rootNode = instance?.rootInActiveWindow ?: return "Экран недоступен или заблокирован."
            val sb = StringBuilder()
            traverseNode(rootNode, sb)
            val fullText = sb.toString().trim()
            return if (fullText.isNotBlank()) fullText else "На экране нет текстового содержимого."
        }

        /**
         * Находит кнопку/элемент по тексту и выполняет нажатие (ACTION_CLICK)
         */
        fun clickByText(targetText: String): Boolean {
            val rootNode = instance?.rootInActiveWindow ?: return false
            val cleanTarget = targetText.lowercase().trim()

            val nodeToClick = findClickableNodeByText(rootNode, cleanTarget)
            if (nodeToClick != null) {
                val success = nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "clickByText '$targetText' result: $success")
                return success
            }

            Log.w(TAG, "clickByText '$targetText': element not found")
            return false
        }

        /**
         * Вводит текст в сфокусированное редактируемое поле (ACTION_SET_TEXT).
         *
         * Используется в цепочке «открой приложение → найди поле поиска →
         * введи запрос → проверь результат». Если ни одно редактируемое поле
         * не сфокусировано — ищем первое EditText-поле на экране и ставим
         * фокус через ACTION_FOCUS перед вводом.
         */
        fun typeText(text: String): Boolean {
            val rootNode = instance?.rootInActiveWindow ?: return false
            if (text.isEmpty()) return false

            val editable = findEditableNode(rootNode)
                ?: run {
                    Log.w(TAG, "typeText: no editable field found on screen")
                    return false
                }

            val setTextArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
            Log.d(TAG, "typeText '$text' into editable field result: $success")
            return success
        }

        private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null

            // Сначала сфокусированное поле ввода.
            val focused = node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused?.isEditable == true) return focused

            if (node.isEditable) return node

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                val found = findEditableNode(child)
                if (found != null) return found
            }
            return null
        }

        /**
         * Прокрутка экрана вниз (Scroll Down)
         */
        fun scrollDown(): Boolean {
            val rootNode = instance?.rootInActiveWindow ?: return false
            val scrollableNode = findScrollableNode(rootNode)
            return scrollableNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
        }

        /**
         * Прокрутка экрана вверх (Scroll Up)
         */
        fun scrollUp(): Boolean {
            val rootNode = instance?.rootInActiveWindow ?: return false
            val scrollableNode = findScrollableNode(rootNode)
            return scrollableNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
        }

        private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder) {
            if (node == null) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                sb.append(text).append(". ")
            } else if (!desc.isNullOrEmpty()) {
                sb.append(desc).append(". ")
            }
            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), sb)
            }
        }

        private fun findClickableNodeByText(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
            if (node == null) return null

            val text = node.text?.toString()?.lowercase()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()

            if (text.contains(target) || desc.contains(target) || (target.contains(text) && text.length >= 3)) {
                if (node.isClickable) return node

                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) return parent
                    parent = parent.parent
                }
                return node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                val found = findClickableNodeByText(child, target)
                if (found != null) return found
            }

            return null
        }

        private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node

            for (i in 0 until node.childCount) {
                val found = findScrollableNode(node.getChild(i))
                if (found != null) return found
            }
            return null
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
