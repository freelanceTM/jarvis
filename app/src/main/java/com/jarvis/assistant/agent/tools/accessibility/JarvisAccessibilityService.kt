package com.jarvis.assistant.agent.tools.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * JarvisAccessibilityService
 *
 * Позволяет JARVIS взаимодействовать с экранными элементами, делать системные скриншоты,
 * автономно переключать плитки быстрых настроек (Quick Settings), считывать UI-контекст и нажимать кнопки без касания экрана.
 *
 * ## Privacy boundary (P1-2)
 *
 * Accessibility без границы читает ЛЮБОЙ экран — включая банки, пароли и 2FA.
 * Все операции (чтение/ввод/клики/скролл) проходят через
 * [AccessibilityPrivacyPolicy]:
 *
 * ```text
 * Allowed apps      → доступны
 * Sensitive apps    → никогда не читаются / не управляются (явный allow перекрывает)
 * Lock screen / GMS → никогда не доступны (не перекрывается)
 * Password fields   → никогда не читаются и не заполняются
 * ```
 *
 * Audit-лог фиксирует ТОЛЬКО пакет + действие + решение — без содержимого
 * экрана и без вводимого текста (длины допустимы).
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        private const val MAX_SCREEN_TEXT_CHARS = 20_000
        private const val MAX_SCREEN_NODES = 2_000

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

        // =====================================================================
        // Privacy policy (P1-2)
        // =====================================================================

        /**
         * Privacy-конфигурация читается из того же SharedPreferences-файла,
         * который пишет [AccessibilityPrivacyStore] (синхронное чтение:
         * AccessibilityService не участвует в Hilt-графе и не может
         * подписываться на DataStore-потоки).
         */
        private fun privacyPolicy(service: JarvisAccessibilityService): AccessibilityPrivacyPolicy {
            val ownPackage = service.packageName ?: ""
            val store = AccessibilityPrivacyStore(service.applicationContext ?: service)
            return AccessibilityPrivacyPolicy(
                configProvider = { store.current(ownPackage) },
                ownPackage = ownPackage
            )
        }

        /**
         * Решение политики для пакета, владеющего активным окном.
         * Пакет определяем по rootInActiveWindow (а не по событию onAccessibilityEvent —
         * события приходят от всех окон, включая системные оверлеи).
         */
        private fun activeWindowPolicyDecision(): Pair<PolicyDecision, AccessibilityNodeInfo?> {
            val service = instance ?: return PolicyDecision.Blocked(
                BlockedReason.SYSTEM_UI_LOCK_SCREEN, null
            ) to null
            val root = service.rootInActiveWindow ?: run {
                return PolicyDecision.Blocked(BlockedReason.SYSTEM_UI_LOCK_SCREEN, null) to null
            }
            val pkg = try {
                root.packageName?.toString()
            } catch (_: Exception) {
                null
            }
            return privacyPolicy(service).decidePackage(pkg) to root
        }

        private fun auditLog(action: String, decision: PolicyDecision) {
            when (decision) {
                is PolicyDecision.Blocked ->
                    // В лог идёт только имя пакета и причина; НЕ содержимое экрана.
                    Log.i(TAG, "privacy block action=$action reason=${decision.reason} pkg=${decision.packageName}")
                is PolicyDecision.Allowed ->
                    Log.d(TAG, "privacy allow action=$action")
            }
        }

        /**
         * Считывает текстовый контент экрана с учётом privacy-границы.
         * Парольные поля (isPassword) пропускаются на любом уровне дерева —
         * независимо от доверия к приложению.
         */
        fun getScreenContent(): AccessibilityReadResult {
            val (decision, rootNode) = activeWindowPolicyDecision()
            if (decision is PolicyDecision.Blocked) {
                auditLog("read_screen", decision)
                return AccessibilityReadResult.PrivacyBlocked(decision)
            }
            rootNode ?: return AccessibilityReadResult.Unavailable

            val sb = StringBuilder()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(rootNode)
            var visited = 0
            var passwordFieldsSkipped = 0
            var sanitizedValues = 0

            while (queue.isNotEmpty() && visited < MAX_SCREEN_NODES && sb.length < MAX_SCREEN_TEXT_CHARS) {
                val node = queue.removeFirst()
                visited++

                // Парольные поля не читаем и их текст не трогаем;
                // детей обходим — соседние элементы могут быть не чувствительны.
                val isPassword = try {
                    node.isPassword
                } catch (_: Exception) {
                    true // fail-closed: не смогли прочитать флаг — считаем паролем
                }
                if (isPassword) {
                    passwordFieldsSkipped++
                } else {
                    val value = node.text?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    if (value != null) {
                        // Слой 3 (контентный): OTP-коды/картоподобные номера,
                        // всплывшие в обычном приложении, маскируются ДО выхода
                        // из capture — в LLM они не попадают в любом виде.
                        val (safeValue, maskedCount) = ScreenTextSanitizer.sanitize(value)
                        sanitizedValues += maskedCount
                        val remaining = MAX_SCREEN_TEXT_CHARS - sb.length
                        sb.append(safeValue.take(remaining)).append(". ")
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::addLast)
                }
            }

            val fullText = sb.toString().take(MAX_SCREEN_TEXT_CHARS).trim()
            return if (fullText.isNotBlank()) {
                AccessibilityReadResult.Content(
                    text = fullText,
                    passwordFieldsSkipped = passwordFieldsSkipped,
                    sanitizedValues = sanitizedValues
                )
            } else {
                AccessibilityReadResult.Empty(passwordFieldsSkipped, sanitizedValues)
            }
        }

        /**
         * Находит кнопку/элемент по тексту и выполняет нажатие (ACTION_CLICK).
         */
        fun clickByText(targetText: String): AccessibilityActionResult {
            val (decision, rootNode) = activeWindowPolicyDecision()
            if (decision is PolicyDecision.Blocked) {
                auditLog("ui_click", decision)
                return AccessibilityActionResult.PrivacyBlocked(decision)
            }
            rootNode ?: return AccessibilityActionResult.Unavailable

            val cleanTarget = targetText.lowercase().trim()
            val nodeToClick = findClickableNodeByText(rootNode, cleanTarget)
            return if (nodeToClick != null) {
                val success = nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "clickByText result=$success targetChars=${targetText.length}")
                if (success) AccessibilityActionResult.Performed else AccessibilityActionResult.Failed
            } else {
                Log.w(TAG, "clickByText element not found targetChars=${targetText.length}")
                AccessibilityActionResult.NotFound
            }
        }

        /**
         * Вводит текст в сфокусированное редактируемое поле (ACTION_SET_TEXT).
         *
         * Парольные поля НЕ заполняются никогда: пароль, продиктованный голосом,
         * остался бы в истории распознавания и логах STT. Возвращаем честный
         * «нужно действие пользователя».
         */
        fun typeText(text: String): AccessibilityActionResult {
            if (text.isEmpty()) return AccessibilityActionResult.Failed

            val (decision, rootNode) = activeWindowPolicyDecision()
            if (decision is PolicyDecision.Blocked) {
                auditLog("type_text", decision)
                return AccessibilityActionResult.PrivacyBlocked(decision)
            }
            rootNode ?: return AccessibilityActionResult.Unavailable

            val editable = findEditableNode(rootNode)
                ?: run {
                    Log.w(TAG, "typeText: no editable field found on screen")
                    return AccessibilityActionResult.NotFound
                }

            val targetIsPassword = try {
                editable.isPassword
            } catch (_: Exception) {
                true // fail-closed
            }
            if (targetIsPassword) {
                auditLog("type_text", PolicyDecision.Blocked(BlockedReason.SENSITIVE_CATEGORY, null))
                return AccessibilityActionResult.PasswordFieldBlocked
            }

            val setTextArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
            // Не пишем введённый текст в logcat: поле может содержать пароль,
            // токен или персональные данные.
            Log.d(TAG, "typeText into editable field result: $success, chars=${text.length}")
            return if (success) AccessibilityActionResult.Performed else AccessibilityActionResult.Failed
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
         * Прокрутка экрана (Scroll Down/Up) с учётом privacy-границы.
         */
        fun scrollDown(): AccessibilityActionResult = scrollWith(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

        fun scrollUp(): AccessibilityActionResult = scrollWith(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

        private fun scrollWith(scrollAction: Int): AccessibilityActionResult {
            val (decision, rootNode) = activeWindowPolicyDecision()
            if (decision is PolicyDecision.Blocked) {
                auditLog("scroll", decision)
                return AccessibilityActionResult.PrivacyBlocked(decision)
            }
            rootNode ?: return AccessibilityActionResult.Unavailable
            val scrollableNode = findScrollableNode(rootNode)
            return when {
                scrollableNode == null -> AccessibilityActionResult.NotFound
                scrollableNode.performAction(scrollAction) -> AccessibilityActionResult.Performed
                else -> AccessibilityActionResult.Failed
            }
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
        // События НЕ читают содержимое: только тип и пакет для диагностики.
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

/** Результат чтения экрана с учётом privacy-границы. */
sealed interface AccessibilityReadResult {
    /** Экран прочитан. */
    data class Content(
        val text: String,
        val passwordFieldsSkipped: Int,
        /** Значений, замаскированных контентным санитайзером (OTP/карты). */
        val sanitizedValues: Int = 0
    ) : AccessibilityReadResult

    /** Экран пуст (или только парольные поля, которые мы не читаем). */
    data class Empty(
        val passwordFieldsSkipped: Int,
        /** Значений, замаскированных контентным санитайзером. */
        val sanitizedValues: Int = 0
    ) : AccessibilityReadResult

    /** Чтение запрещено privacy-политикой. Контент НЕ извлекается. */
    data class PrivacyBlocked(val decision: PolicyDecision.Blocked) : AccessibilityReadResult

    /** Активного окна нет (экран заблокирован / сервис без окна). */
    object Unavailable : AccessibilityReadResult
}

/** Результат действия (клик/ввод/скролл) с учётом privacy-границы. */
sealed interface AccessibilityActionResult {
    /** Действие выполнено. */
    object Performed : AccessibilityActionResult

    /** Целевой элемент не найден. */
    object NotFound : AccessibilityActionResult

    /** Действие не выполнено (системная ошибка performAction). */
    object Failed : AccessibilityActionResult

    /** Активного окна нет. */
    object Unavailable : AccessibilityActionResult

    /** Действие запрещено privacy-политикой. НЕ выполняется. */
    data class PrivacyBlocked(val decision: PolicyDecision.Blocked) : AccessibilityActionResult

    /** Целевое поле — парольное: голосовой ввод туда запрещён по дизайну. */
    object PasswordFieldBlocked : AccessibilityActionResult
}
