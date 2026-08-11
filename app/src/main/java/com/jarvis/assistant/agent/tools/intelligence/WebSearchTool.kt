package com.jarvis.assistant.agent.tools.intelligence

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSearchTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "web_search"
    override val description: String = "Выполняет поиск актуальной информации в интернете через браузер"
    override val risk: ToolRisk = ToolRisk.LOW

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Поисковый запрос")
            }
        }
        put("required", buildJsonArray { add("query") })
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) {
            return ToolResult.Error("Пустой поисковый запрос", "EMPTY_QUERY")
        }

        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Ищу в интернете: $query")
        } catch (e: Exception) {
            ToolResult.Error("Не удалось выполнить поиск: ${e.localizedMessage}", "SEARCH_ERROR")
        }
    }
}
