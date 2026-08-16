package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.jarvis.assistant.agent.capability.CapabilityStatus
import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.DeviceCapability
import com.jarvis.assistant.agent.capability.DeviceCapabilityRegistry
import com.jarvis.assistant.agent.capability.JarvisCapability
import com.jarvis.assistant.agent.capability.ToolCapabilityContract
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.tools.accessibility.JarvisAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Screenshot Tool v0.2 — честный API-level аудит.
 *
 * Реальные ограничения Android:
 *  - Приложение НЕ может сделать снимок экрана «молча». Любой путь требует либо
 *    привилегии AccessibilityService, либо явного согласия на MediaProjection.
 *  - [android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT]
 *    появился только в API 30 (Android 11). На API 29 такого действия нет.
 *  - MediaProjection требует Activity для показа системного диалога согласия,
 *    отдельного foreground-сервиса с типом mediaProjection и корректного
 *    освобождения VirtualDisplay/ImageReader после каждого захвата.
 *
 * Текущая реализация честно использует то, что действительно работает
 * (Accessibility, API 30+), и для остальных случаев возвращает структурированную
 * причину вместо silent failure или фиктивного успеха. MediaProjection-путь
 * объявлен в capability-контракте как требующий пользовательского согласия и
 * пока не выдаётся за рабочий: UI-хоста для consent-диалога в v0.2 нет.
 */
@Singleton
class ScreenshotTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilityRegistry
) : CapabilityAwareTool {

    override val toolId: String = "device.screenshot"
    override val description: String = "Делает системный снимок экрана (Android 11+ через службу специальных возможностей)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(DeviceCapability.TAKE_SCREENSHOT_ACCESSIBILITY),
        dangerLevel = DangerLevel.MEDIUM
    )
    override val capability: JarvisCapability = JarvisCapability.Screenshot

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        // 1. API-level: до Android 11 системного скриншота из приложения не существует.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ToolExecutionResult.unsupported(
                summary = "Снимок экрана недоступен: Android ${Build.VERSION.RELEASE} не даёт приложениям делать системные скриншоты. " +
                    "Воспользуйтесь аппаратной комбинацией кнопок, сэр.",
                reason = "SCREENSHOT_UNSUPPORTED_BELOW_API_30",
                data = buildJsonObject { put("sdk_int", Build.VERSION.SDK_INT) }
            )
        }

        // 2. Проверка capability до попытки выполнения.
        when (val status = capabilities.statusOf(DeviceCapability.TAKE_SCREENSHOT_ACCESSIBILITY)) {
            is CapabilityStatus.UserActionRequired -> {
                val opened = openAccessibilitySettings()
                return ToolExecutionResult.userActionRequired(
                    summary = if (opened) {
                        "Для снимков экрана нужно включить службу JARVIS в разделе «Специальные возможности». Открыл нужный экран, сэр."
                    } else {
                        "Для снимков экрана включите службу JARVIS в разделе «Специальные возможности», сэр."
                    },
                    reason = "ACCESSIBILITY_SERVICE_DISABLED",
                    data = buildJsonObject { put("opened_settings", opened) }
                )
            }
            is CapabilityStatus.Unsupported -> return ToolExecutionResult.unsupported(
                summary = status.reason,
                reason = "SCREENSHOT_UNSUPPORTED"
            )
            else -> Unit
        }

        // 3. Реальное выполнение. performGlobalAction возвращает false, если
        //    система отклонила действие — это НЕ успех.
        val taken = JarvisAccessibilityService.takeSystemScreenshot()
        return if (taken) {
            ToolExecutionResult.success(
                summary = "Снимок экрана сделан и сохранён в галерею",
                data = buildJsonObject {
                    put("method", "accessibility_global_action")
                    put("sdk_int", Build.VERSION.SDK_INT)
                }
            )
        } else {
            ToolExecutionResult.failure(
                summary = "Система отклонила запрос на снимок экрана. Возможно, текущее окно защищено флагом FLAG_SECURE",
                error = "SCREENSHOT_REJECTED_BY_SYSTEM"
            )
        }
    }

    private fun openAccessibilitySettings(): Boolean {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
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
