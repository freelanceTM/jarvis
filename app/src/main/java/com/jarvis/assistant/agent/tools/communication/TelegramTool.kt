package com.jarvis.assistant.agent.tools.communication

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.agent.capability.DangerLevel
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

/**
 * Telegram Tool — ветка Communication → Telegram.
 *
 * Честные возможности v0.2:
 *  - «открой Telegram» → SUCCESS (запуск приложения);
 *  - «отправь в Telegram <текст>» → открыть системный share-диалог Telegram
 *    с готовым текстом → USER_ACTION_REQUIRED (пользователь выбирает чат и
 *    подтверждает отправку). Прямая программная отправка без Telegram Bot API
 *    невозможна — мы это НЕ имитируем.
 */
@Singleton
class TelegramTool @Inject constructor(
    @ApplicationContext private val context: Context
) : CapabilityAwareTool {

    override val toolId: String = "communication.telegram"
    override val description: String = "Открывает Telegram и готовит сообщение для отправки (выбор чата и отправка — пользователем)"
    override val category: ToolCategory = ToolCategory.COMMUNICATION
    override val riskLevel: ToolRisk = ToolRisk.CONFIRMATION_REQUIRED
    override val isOffline: Boolean = true
    override val mayDiscloseUserContentExternally: Boolean = true
    override val requiresForeground: Boolean = true

    override val capabilityContract = ToolCapabilityContract(
        capabilities = emptySet(),
        dangerLevel = DangerLevel.LOW
    )
    override val capability: JarvisCapability = JarvisCapability.Apps

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Действие: 'open' — открыть Telegram, 'send' — подготовить сообщение для отправки")
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "Текст сообщения (для action=send)")
            }
        }
        put("required", buildJsonArray { add("action") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val action = arguments["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: "open"

        return when (action) {
            "open" -> openTelegram()

            "send" -> {
                val text = arguments["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) {
                    return ToolExecutionResult.failure(
                        summary = "Не указан текст сообщения для Telegram",
                        error = "MISSING_TEXT"
                    )
                }
                prepareSend(text)
            }

            else -> ToolExecutionResult.failure(
                summary = "Неизвестное действие: $action",
                error = "UNKNOWN_ACTION"
            )
        }
    }

    private fun openTelegram(): ToolExecutionResult {
        val packageName = "org.telegram.messenger"
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                ToolExecutionResult.failure(
                    summary = "Telegram не установлен на этом устройстве, сэр.",
                    error = "TELEGRAM_NOT_INSTALLED"
                )
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ToolExecutionResult.success(
                    summary = "Открываю Telegram, сэр.",
                    data = buildJsonObject { put("action", "open") }
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure(
                summary = "Не удалось открыть Telegram: ${e.localizedMessage}",
                error = "TELEGRAM_OPEN_ERROR"
            )
        }
    }

    /**
     * Готовит сообщение через системный share-диалог Telegram.
     * Отправка НЕ происходит автоматически — это USER_ACTION_REQUIRED.
     */
    private fun prepareSend(text: String): ToolExecutionResult {
        return try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                `package` = "org.telegram.messenger"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (shareIntent.resolveActivity(context.packageManager) == null) {
                ToolExecutionResult.failure(
                    summary = "Telegram не установлен или не поддерживает отправку текста, сэр.",
                    error = "TELEGRAM_SHARE_UNAVAILABLE"
                )
            } else {
                context.startActivity(shareIntent)
                ToolExecutionResult.userActionRequired(
                    summary = "Открыл окно отправки в Telegram с вашим текстом. Выберите чат и отправьте, сэр.",
                    reason = "TELEGRAM_SEND_REQUIRES_USER",
                    data = buildJsonObject {
                        put("action", "send")
                        put("text_preview", text.take(50))
                    }
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure(
                summary = "Не удалось открыть отправку в Telegram: ${e.localizedMessage}",
                error = "TELEGRAM_SEND_ERROR"
            )
        }
    }
}
