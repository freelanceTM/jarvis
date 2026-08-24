package com.jarvis.assistant.agent.tools.communication

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "communication.share"
    override val description: String = "Делится текстом, ссылкой или сообщением через системное меню Share в мессенджеры и соцсети"
    override val category: ToolCategory = ToolCategory.COMMUNICATION
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val mayDiscloseUserContentExternally: Boolean = true
    override val requiresForeground: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Текст или ссылка для отправки")
            }
        }
        put("required", buildJsonArray { add("text") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val text = arguments["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) {
            return ToolExecutionResult.failure("Пустой текст для отправки", "MISSING_TEXT")
        }

        return try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "Поделиться через JARVIS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(shareIntent)
            ToolExecutionResult.success("Открываю меню 'Поделиться'", actionRequiresUser = true)
        } catch (e: Exception) {
            ToolExecutionResult.failure("Ошибка отправки: ${e.localizedMessage}", "SHARE_ERROR")
        }
    }
}
