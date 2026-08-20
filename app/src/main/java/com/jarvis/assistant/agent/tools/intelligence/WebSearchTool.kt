package com.jarvis.assistant.agent.tools.intelligence

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.core.network.ResponseBodyTooLargeException
import com.jarvis.assistant.core.network.readUtf8Bounded
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Web Search Tool v2.0
 * 
 * Улучшенный поиск с несколькими источниками:
 * 1. DuckDuckGo Instant Answer API (быстрые факты)
 * 2. DuckDuckGo HTML fallback (если API пуст)
 * 3. Wikipedia API (для энциклопедических запросов)
 */
@Singleton
class WebSearchTool @Inject constructor(
    private val okHttpClient: OkHttpClient
) : JarvisTool {

    companion object {
        private const val MAX_RESPONSE_BYTES = 512L * 1024
        private const val PER_REQUEST_TIMEOUT_MILLIS = 3_500L
    }

    private val searchHttpClient = okHttpClient.newBuilder()
        .callTimeout(PER_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()

    override val toolId: String = "intelligence.web_search"
    override val description: String = "Поиск актуальной информации, фактов и новостей в интернете"
    override val category: ToolCategory = ToolCategory.INTELLIGENCE
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = false
    override val executionTimeoutMs: Long = 12000L
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
            // 1. Пробуем DuckDuckGo Instant Answer API
            val ddgResult = searchDuckDuckGo(query)
            if (ddgResult != null) {
                return ddgResult
            }

            // 2. Fallback: Wikipedia API (для энциклопедических запросов)
            val wikiResult = searchWikipedia(query)
            if (wikiResult != null) {
                return wikiResult
            }

            // 3. Если ничего не нашли
            ToolExecutionResult.success(
                summary = "По запросу \"$query\" точных данных не найдено. Попробуйте переформулировать вопрос.",
                data = buildJsonObject {
                    put("query", query)
                    put("found", false)
                }
            )
        } catch (e: ResponseBodyTooLargeException) {
            ToolExecutionResult.failure(
                summary = "Поисковый сервис вернул слишком большой ответ",
                error = "RESPONSE_TOO_LARGE"
            )
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

    private fun searchDuckDuckGo(query: String): ToolExecutionResult? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "JARVIS/2.0 (Android Voice Assistant)")
            .get()
            .build()

        val body = searchHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.readUtf8Bounded(MAX_RESPONSE_BYTES).orEmpty()
        }

        if (body.isEmpty()) return null

        val json = Json.parseToJsonElement(body).jsonObject

        // 1. AbstractText (краткий ответ)
        val abstractText = json["AbstractText"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        
        // 2. Answer (для калькулятора, конвертера и т.д.)
        val answer = json["Answer"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        
        // 3. Definition
        val definition = json["Definition"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

        // 4. RelatedTopics (если основной ответ пуст)
        val relatedTopics = json["RelatedTopics"]?.jsonArray
        val topicsText = relatedTopics
            ?.take(3)
            ?.mapNotNull { element ->
                if (element is JsonObject) {
                    element["Text"]?.jsonPrimitive?.contentOrNull?.trim()
                } else null
            }
            ?.filter { it.isNotBlank() }
            ?.joinToString(". ")
            .orEmpty()

        // Выбираем лучший результат
        val resultText = when {
            answer.isNotBlank() -> answer
            abstractText.isNotBlank() -> abstractText
            definition.isNotBlank() -> definition
            topicsText.isNotBlank() -> topicsText
            else -> return null // Ничего не нашли, пробуем следующий источник
        }

        // Ограничиваем длину для голосового ответа
        val trimmed = if (resultText.length > 500) {
            resultText.take(500) + "..."
        } else {
            resultText
        }

        val source = json["AbstractSource"]?.jsonPrimitive?.contentOrNull ?: "DuckDuckGo"

        return ToolExecutionResult.success(
            summary = trimmed,
            data = buildJsonObject {
                put("query", query)
                put("result", trimmed)
                put("source", source)
                put("found", true)
            }
        )
    }

    private fun searchWikipedia(query: String): ToolExecutionResult? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        // Wikipedia API: поиск + извлечение экстракта
        val url = "https://ru.wikipedia.org/api/rest_v1/page/summary/$encodedQuery"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "JARVIS/2.0 (Android Voice Assistant)")
            .get()
            .build()

        return try {
            val body = searchHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return searchWikipediaEn(query)
                response.body?.readUtf8Bounded(MAX_RESPONSE_BYTES).orEmpty()
            }

            if (body.isEmpty()) return searchWikipediaEn(query)

            val json = Json.parseToJsonElement(body).jsonObject
            
            // Проверяем, что это не disambiguation page
            val type = json["type"]?.jsonPrimitive?.contentOrNull
            if (type == "disambiguation") {
                return null
            }

            val extract = json["extract"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            
            if (extract.isBlank() || extract.length < 20) {
                return searchWikipediaEn(query)
            }

            val trimmed = if (extract.length > 500) {
                extract.take(500) + "..."
            } else {
                extract
            }

            ToolExecutionResult.success(
                summary = trimmed,
                data = buildJsonObject {
                    put("query", query)
                    put("result", trimmed)
                    put("source", "Wikipedia (RU)")
                    put("found", true)
                }
            )
        } catch (e: ResponseBodyTooLargeException) {
            throw e
        } catch (_: Exception) {
            searchWikipediaEn(query)
        }
    }

    private fun searchWikipediaEn(query: String): ToolExecutionResult? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encodedQuery"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "JARVIS/2.0 (Android Voice Assistant)")
            .get()
            .build()

        return try {
            val body = searchHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.readUtf8Bounded(MAX_RESPONSE_BYTES).orEmpty()
            }

            if (body.isEmpty()) return null

            val json = Json.parseToJsonElement(body).jsonObject
            
            val type = json["type"]?.jsonPrimitive?.contentOrNull
            if (type == "disambiguation") {
                return null
            }

            val extract = json["extract"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            
            if (extract.isBlank() || extract.length < 20) {
                return null
            }

            val trimmed = if (extract.length > 500) {
                extract.take(500) + "..."
            } else {
                extract
            }

            ToolExecutionResult.success(
                summary = trimmed,
                data = buildJsonObject {
                    put("query", query)
                    put("result", trimmed)
                    put("source", "Wikipedia (EN)")
                    put("found", true)
                }
            )
        } catch (e: ResponseBodyTooLargeException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
