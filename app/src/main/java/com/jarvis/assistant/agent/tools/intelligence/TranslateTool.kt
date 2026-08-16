package com.jarvis.assistant.agent.tools.intelligence

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.translator.LiveTranslatorEngine
import com.jarvis.assistant.agent.translator.TranslationResult
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslateTool @Inject constructor(
    private val translatorEngine: LiveTranslatorEngine
) : JarvisTool {

    override val toolId: String = "intelligence.translate"
    override val description: String = "Мгновенный синхронный перевод речи и текста (русский, английский, туркменский, турецкий и др.)"
    override val category: ToolCategory = ToolCategory.INTELLIGENCE
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = false
    override val executionTimeoutMs: Long = 6000L

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Текст или фраза для перевода")
            }
            putJsonObject("target_lang") {
                put("type", "string")
                put("description", "Язык перевода: ru, en, tk, tr, de, zh, ar")
            }
            putJsonObject("source_lang") {
                put("type", "string")
                put("description", "Исходный язык: auto, ru, en, tk, tr")
            }
        }
        put("required", buildJsonArray { add("text") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val text = arguments["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) {
            return ToolExecutionResult.failure("Не указан текст для перевода", "MISSING_TEXT")
        }

        val targetLang = arguments["target_lang"]?.jsonPrimitive?.contentOrNull ?: "ru"
        val sourceLang = arguments["source_lang"]?.jsonPrimitive?.contentOrNull ?: "auto"

        return when (val result = translatorEngine.translateStructured(text, sourceLang, targetLang)) {
            is TranslationResult.Success -> ToolExecutionResult.success(
                summary = result.translatedText,
                data = buildJsonObject {
                    put("original", text)
                    put("translation", result.translatedText)
                    put("source_lang", result.sourceLang)
                    put("target_lang", result.targetLang)
                    put("provider", result.providerId)
                }
            )

            is TranslationResult.Unsupported -> ToolExecutionResult.unsupported(
                summary = translatorEngine.describeFailure(result),
                reason = "TRANSLATION_UNSUPPORTED"
            )

            is TranslationResult.NetworkRequired -> ToolExecutionResult.failure(
                summary = translatorEngine.describeFailure(result),
                error = "NETWORK_REQUIRED"
            )

            is TranslationResult.ModelUnavailable -> ToolExecutionResult.failure(
                summary = translatorEngine.describeFailure(result),
                error = "MODEL_UNAVAILABLE"
            )

            is TranslationResult.Error -> ToolExecutionResult.failure(
                summary = translatorEngine.describeFailure(result),
                error = "TRANSLATION_FAILED"
            )
        }
    }
}
