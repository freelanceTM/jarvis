package com.jarvis.assistant.agent.tools.device

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.tools.verification.ExecutionVerification
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Режим «Не беспокоить» С ВЕРИФИКАЦИЕЙ результата (execute → verify → SUCCESS).
 *
 * [NotificationManager.setInterruptionFilter] молча не применяется, если
 * приложение часов/система переопределяют политику. Поэтому SUCCESS только
 * когда И возврат вызова, И read-back currentInterruptionFilter равны целевому
 * фильтру. Ветка без policy-доступа возвращает USER_ACTION_REQUIRED (это НЕ
 * успех: состояние устройства не изменилось).
 */
@Singleton
class DoNotDisturbTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "device.dnd"
    override val description: String = "Управляет режимом 'Не беспокоить' (DND / режим тишины)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("enabled") {
                put("type", "boolean")
                put("description", "true - включить режим 'Не беспокоить', false - выключить")
            }
        }
        put("required", buildJsonArray { add("enabled") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ToolExecutionResult.failure("Служба уведомлений недоступна", "NO_NOTIFICATION_SERVICE")

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm.isNotificationPolicyAccessGranted) {
                val targetFilter = if (enabled) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                }

                // ------------------------------------------------------ EXECUTE
                // setInterruptionFilter возвращает применённый фильтр
                // (INTERRUPTION_FILTER_UNKNOWN при отказе системы).
                val appliedFilter = nm.setInterruptionFilter(targetFilter)

                // ------------------------------------------------------ VERIFY
                val currentFilter = ExecutionVerification.pollFor(
                    read = { nm.currentInterruptionFilter },
                    satisfied = { it == targetFilter }
                )
                val data = buildJsonObject {
                    put("enabled", enabled)
                    put("target_filter", targetFilter)
                    put("applied_filter", appliedFilter)
                    put("current_filter", currentFilter)
                }
                if (ExecutionVerification.dndVerified(appliedFilter, currentFilter, targetFilter)) {
                    ToolExecutionResult.success(
                        if (enabled) "Режим 'Не беспокоить' включён" else "Режим 'Не беспокоить' выключен",
                        data = data
                    )
                } else {
                    ToolExecutionResult.failure(
                        "Не удалось переключить режим 'Не беспокоить' — система не подтвердила изменение",
                        "DND_VERIFY_FAILED",
                        data = data
                    )
                }
            } else {
                // Прямого доступа к политике нет: состояние НЕ изменено — это не
                // успех. Честный USER_ACTION_REQUIRED + открытие системного экрана.
                val opened = openPolicyAccessSettings()
                ToolExecutionResult.userActionRequired(
                    summary = if (opened) {
                        "Чтобы управлять режимом «Не беспокоить», выдайте JARVIS доступ к уведомлениям. Открыл нужный экран — включите доступ, сэр."
                    } else {
                        "Чтобы управлять режимом «Не беспокоить», выдайте JARVIS доступ к уведомлениям в настройках, сэр."
                    },
                    reason = "NOTIFICATION_POLICY_ACCESS_REQUIRED",
                    data = buildJsonObject {
                        put("enabled", enabled)
                        put("opened_settings", opened)
                    }
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось переключить DND: ${e.localizedMessage}", "DND_ERROR")
        }
    }

    /** @return true, если системный экран policy-доступа действительно открыт. */
    private fun openPolicyAccessSettings(): Boolean {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) == null) {
            return false
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }
}
