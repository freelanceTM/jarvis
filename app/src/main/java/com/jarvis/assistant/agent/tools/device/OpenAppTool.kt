package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.agent.apps.AppResolution
import com.jarvis.assistant.agent.apps.AppResolver
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Open App Tool v0.2.
 *
 * Разрешение имени приложения вынесено в [AppResolver]:
 * normalize → exact → alias → fuzzy → package name.
 *
 * Честные состояния вместо прежнего «открыл магазин = success»:
 *  - приложение найдено и запущено       → SUCCESS
 *  - приложение известно, но не стоит    → FAILURE (APP_NOT_INSTALLED) + предложение установить
 *  - несколько похожих кандидатов        → FAILURE (AMBIGUOUS) с уточняющим вопросом
 *  - совпадений нет                      → FAILURE (APP_UNKNOWN)
 */
@Singleton
class OpenAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appResolver: AppResolver
) : JarvisTool {

    override val toolId: String = "device.open_app"
    override val description: String = "Открывает установленное приложение на телефоне по названию (Telegram, YouTube, WhatsApp, Камера, Chrome, Карты, Настройки)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("app_name") {
                put("type", "string")
                put("description", "Название приложения так, как его назвал пользователь: telegram, ютуб, whatsapp, камера, карты, настройки")
            }
        }
        put("required", buildJsonArray { add("app_name") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val rawName = arguments["app_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (rawName.isEmpty()) {
            return ToolExecutionResult.failure("Не указано название приложения", "MISSING_PARAM")
        }

        return when (val resolution = appResolver.resolve(rawName)) {
            is AppResolution.Resolved -> launch(resolution, rawName)

            is AppResolution.NotInstalled -> ToolExecutionResult.failure(
                summary = "Приложение «$rawName» не установлено на устройстве",
                error = "APP_NOT_INSTALLED"
            ).copy(
                data = buildJsonObject {
                    put("query", rawName)
                    resolution.knownPackage?.let { put("expected_package", it) }
                }
            )

            is AppResolution.Ambiguous -> ToolExecutionResult.failure(
                summary = "Нашёл несколько подходящих приложений: " +
                    resolution.candidates.joinToString(", ") { it.label } +
                    ". Какое именно открыть, сэр?",
                error = "AMBIGUOUS_APP"
            ).copy(
                data = buildJsonObject {
                    put("query", rawName)
                    put("candidates", buildJsonArray {
                        resolution.candidates.forEach { candidate ->
                            add(buildJsonObject {
                                put("label", candidate.label)
                                put("package", candidate.packageName)
                            })
                        }
                    })
                }
            )

            is AppResolution.Unknown -> ToolExecutionResult.failure(
                summary = "Не нашёл приложение «$rawName» среди установленных",
                error = "APP_UNKNOWN"
            )
        }
    }

    private fun launch(resolution: AppResolution.Resolved, rawName: String): ToolExecutionResult {
        // Системные экраны (настройки, камера) открываются через action, а не launch intent.
        appResolver.systemActions[resolution.packageName]?.let { action ->
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult.failure(
                    summary = "Системный экран «$rawName» недоступен на этом устройстве",
                    error = "SYSTEM_SCREEN_UNAVAILABLE"
                )
            }
            return try {
                context.startActivity(intent)
                ToolExecutionResult.success(
                    summary = "Открываю $rawName",
                    data = buildJsonObject { put("action", action) }
                )
            } catch (e: android.content.ActivityNotFoundException) {
                ToolExecutionResult.failure("Не удалось открыть $rawName", "ACTIVITY_NOT_FOUND")
            }
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(resolution.packageName)
            ?: return ToolExecutionResult.failure(
                summary = "У приложения «${resolution.label}» нет экрана запуска",
                error = "NO_LAUNCH_INTENT"
            )

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            ToolExecutionResult.success(
                summary = "Открываю ${resolution.label}",
                data = buildJsonObject {
                    put("package", resolution.packageName)
                    put("label", resolution.label)
                    put("matched_by", resolution.matchedBy.name)
                }
            )
        } catch (e: android.content.ActivityNotFoundException) {
            ToolExecutionResult.failure(
                summary = "Не удалось запустить ${resolution.label}",
                error = "ACTIVITY_NOT_FOUND"
            )
        } catch (e: SecurityException) {
            ToolExecutionResult.failure(
                summary = "Система запретила запуск ${resolution.label}",
                error = "LAUNCH_DENIED"
            )
        }
    }

    /** Предлагает установить приложение — вызывается явно, а не молча вместо запуска. */
    fun openStoreFor(query: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$query")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }
}
