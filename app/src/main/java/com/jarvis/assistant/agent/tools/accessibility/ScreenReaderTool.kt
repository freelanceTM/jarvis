package com.jarvis.assistant.agent.tools.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.DeviceCapability
import com.jarvis.assistant.agent.capability.JarvisCapability
import com.jarvis.assistant.agent.capability.ToolCapabilityContract
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenReaderTool @Inject constructor(
    @ApplicationContext private val context: Context
) : CapabilityAwareTool {

    override val toolId: String = "accessibility.screen_reader"
    override val description: String = "Считывает текстовое содержимое текущего экрана (требует включения Accessibility Service)"
    override val category: ToolCategory = ToolCategory.SYSTEM
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true
    override val executionTimeoutMs: Long = 3000L

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(DeviceCapability.USE_ACCESSIBILITY_SERVICE),
        dangerLevel = DangerLevel.LOW
    )
    override val capability: JarvisCapability = JarvisCapability.Accessibility

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        // КРИТИЧНО: Проверяем, включён ли Accessibility Service
        if (!JarvisAccessibilityService.isServiceRunning()) {
            // Открываем настройки Accessibility для пользователя
            val opened = try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }

            // Честный статус: действие НЕ выполнено, нужен пользователь в системном UI.
            return ToolExecutionResult.userActionRequired(
                summary = if (opened) {
                    "Для чтения экрана необходимо включить JARVIS Accessibility Service. Открыл настройки специальных возможностей."
                } else {
                    "Для чтения экрана необходимо включить JARVIS Accessibility Service в настройках."
                },
                reason = "ACCESSIBILITY_SERVICE_DISABLED",
                data = buildJsonObject { put("opened_settings", opened) }
            )
        }

        return try {
            when (val readResult = JarvisAccessibilityService.getScreenContent()) {
                is AccessibilityReadResult.PrivacyBlocked -> {
                    // Честный отказ: контент НЕ извлечён и НЕ уйдёт в AI.
                    ToolExecutionResult.failure(
                        summary = privacyBlockedSummary(readResult.decision),
                        error = "SCREEN_BLOCKED_BY_PRIVACY_POLICY",
                        data = buildJsonObject {
                            put("blocked_reason", readResult.decision.reason.name)
                            readResult.decision.packageName?.let { put("package", it) }
                        }
                    )
                }

                is AccessibilityReadResult.Content -> {
                    // Ограничиваем длину для голосового ответа
                    val screenContent = readResult.text
                    val truncated = if (screenContent.length > 800) {
                        screenContent.take(800) + "..."
                    } else {
                        screenContent
                    }

                    ToolExecutionResult.success(
                        summary = truncated,
                        data = buildJsonObject {
                            put("full_content", screenContent)
                            put("truncated", screenContent.length > 800)
                            if (readResult.passwordFieldsSkipped > 0) {
                                put("password_fields_skipped", readResult.passwordFieldsSkipped)
                            }
                            if (readResult.sanitizedValues > 0) {
                                put("sanitized_values", readResult.sanitizedValues)
                            }
                        }
                    )
                }

                is AccessibilityReadResult.Empty ->
                    ToolExecutionResult.success(
                        summary = buildString {
                            append("На текущем экране нет текстового содержимого")
                            if (readResult.passwordFieldsSkipped > 0) {
                                append(" (пропущено парольных полей: ${readResult.passwordFieldsSkipped})")
                            } else {
                                append(" или экран заблокирован")
                            }
                            append(".")
                        },
                        data = buildJsonObject {
                            put("full_content", "")
                            put("is_empty", true)
                            if (readResult.passwordFieldsSkipped > 0) {
                                put("password_fields_skipped", readResult.passwordFieldsSkipped)
                            }
                        }
                    )

                AccessibilityReadResult.Unavailable ->
                    ToolExecutionResult.success(
                        summary = "Экран недоступен или заблокирован.",
                        data = buildJsonObject { put("full_content", ""); put("is_empty", true) }
                    )
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure(
                summary = "Ошибка при чтении экрана: ${e.localizedMessage}",
                error = "SCREEN_READ_ERROR"
            )
        }
    }

    private fun privacyBlockedSummary(decision: PolicyDecision.Blocked): String {
        val app = decision.packageName?.let { " (приложение: $it)" } ?: ""
        return when (decision.reason) {
            BlockedReason.SYSTEM_UI_LOCK_SCREEN ->
                "Системный экран (локскрин/настройки/платёжные сервисы)$app защищён privacy-политикой — чтение запрещено."
            BlockedReason.SENSITIVE_CATEGORY ->
                "Приложение$app выглядит как чувствительное (банк/кошелёк/пароль-менеджер) и защищено privacy-политикой. Его можно разрешить явно в настройках приватности."
            BlockedReason.USER_BLOCKED ->
                "Приложение$app заблокировано в privacy-настройках — чтение запрещено."
            BlockedReason.NOT_IN_ALLOW_LIST ->
                "Включён режим allow-листа: приложение$app не входит в список разрешённых — чтение запрещено."
        }
    }
}
