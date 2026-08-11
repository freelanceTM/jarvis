package com.jarvis.assistant.agent.tools.productivity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "productivity.clipboard"
    override val description: String = "Копирует текст в буфер обмена или читает скопированный текст"
    override val category: ToolCategory = ToolCategory.PRODUCTIVITY
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "copy (скопировать текст) или read (прочитать из буфера)")
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "Текст для копирования")
            }
        }
        put("required", buildJsonArray { add("action") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val action = arguments["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: "read"
        val text = arguments["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolExecutionResult.failure("Буфер обмена недоступен", "NO_CLIPBOARD")

        return try {
            if (action == "copy" || text.isNotEmpty()) {
                val clip = ClipData.newPlainText("JARVIS", text)
                cm.setPrimaryClip(clip)
                ToolExecutionResult.success("Текст скопирован в буфер обмена")
            } else {
                val item = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                if (item.isNotEmpty()) {
                    ToolExecutionResult.success("В буфере обмена: \"$item\"", data = buildJsonObject { put("clipboard_text", item) })
                } else {
                    ToolExecutionResult.success("Буфер обмена пуст")
                }
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure("Ошибка буфера обмена: ${e.localizedMessage}", "CLIPBOARD_ERROR")
        }
    }
}
