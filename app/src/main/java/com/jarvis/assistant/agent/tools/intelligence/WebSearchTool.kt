package com.jarvis.assistant.agent.tools.intelligence

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSearchTool @Inject constructor(
    private val okHttpClient: OkHttpClient
) : JarvisTool {

    override val toolId: String = "intelligence.web_search"
    override val description: String = "Поиск актуальной информации, фактов и новостей в интернете"
    override val category: ToolCategory = ToolCategory.INTELLIGENCE
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = false
    override val executionTimeoutMs: Long = 10000L
    override val requiresForeground: Boolean = false

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

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolExecutionResult.failure(
                summary = "Не указан поисковый запрос",
                error = "MISSING_QUERY"
            )

        if (query.isEmpty()) {
            return ToolExecutionResult.failure(
                summary = "Пустой поисковый запрос",
                error = "EMPTY_QUERY"
            )
        }

        return try {
            // DuckDuckGo Instant Answer API (бесплатный, без ключа)
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "JARVIS/1.0")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()

            if (response.isSuccessful && body.isNotEmpty()) {
                val json = Json.parseToJsonElement(body).jsonObject

                // 1. Попробовать AbstractText (краткий ответ)
                val abstractText = json["AbstractText"]?.jsonPrimitive?.contentOrNull.orEmpty()

                // 2. Если пусто — собрать из RelatedTopics
                val relatedTopics = json["RelatedTopics"]?.jsonArray
                val topicsText = relatedTopics
                    ?.take(3)
                    ?.mapNotNull { 
                        if (it is JsonObject) {
                            it["Text"]?.jsonPrimitive?.contentOrNull
                        } else null
                    }
                    ?.joinToString(". ")
                    .orEmpty()

                // 3. Answer (для калькулятора, фактов и т.д.)
                val answer = json["Answer"]?.jsonPrimitive?.contentOrNull.orEmpty()

                val resultText = when {
                    abstractText.isNotBlank() -> abstractText
                    answer.isNotBlank() -> answer
                    topicsText.isNotBlank() -> topicsText
                    else -> "По запросу \"$query\" актуальных данных не найдено."
                }

                // Обрезать до 500 символов для голосового ответа
                val trimmed = if (resultText.length > 500) {
                    resultText.take(500) + "..."
                } else {
                    resultText
                }

                ToolExecutionResult.success(
                    summary = trimmed,
                    data = buildJsonObject {
                        put("query", query)
                        put("result", trimmed)
                        put("source", json["AbstractSource"]?.jsonPrimitive?.contentOrNull ?: "DuckDuckGo")
                    }
                )
            } else {
                ToolExecutionResult.failure(
                    summary = "Поиск временно недоступен",
                    error = "HTTP_${response.code}"
                )
            }
        } catch (e: IOException) {
            ToolExecutionResult.failure(
                summary = "Нет подключения к интернету для поиска",
                error = "NO_INTERNET"
            )
        } catch (e: Exception) {
            ToolExecutionResult.failure(
                summary = "Ошибка поиска: ${e.localizedMessage ?: e.javaClass.simpleName}",
                error = e.javaClass.simpleName
            )
        }
    }
}
