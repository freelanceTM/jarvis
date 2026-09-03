package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.DeviceCapability
import com.jarvis.assistant.agent.capability.DeviceCapabilityRegistry
import com.jarvis.assistant.agent.capability.JarvisCapability
import com.jarvis.assistant.agent.capability.ToolCapabilityContract
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.tools.verification.ExecutionVerification
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Set Brightness Tool v0.2 — реальное изменение яркости вместо «открою настройки».
 *
 * Системная яркость пишется в [Settings.System.SCREEN_BRIGHTNESS] и требует
 * специального разрешения WRITE_SETTINGS, которое проверяется через
 * [Settings.System.canWrite]. Обычный runtime-запрос здесь не работает —
 * пользователя нужно отправить в ACTION_MANAGE_WRITE_SETTINGS.
 *
 * Контракт:
 *  - разрешение есть  → яркость реально меняется, возвращается SUCCESS;
 *  - разрешения нет   → USER_ACTION_REQUIRED + открытие нужного системного экрана;
 *  - без параметра    → чтение текущей яркости (SUCCESS).
 */
@Singleton
class SetBrightnessTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilityRegistry
) : CapabilityAwareTool {

    override val toolId: String = "device.brightness"
    override val description: String = "Читает и изменяет яркость экрана (0-100%). Требует разрешения на изменение системных настроек"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(
            DeviceCapability.READ_BRIGHTNESS,
            DeviceCapability.WRITE_BRIGHTNESS,
            DeviceCapability.OPEN_DISPLAY_SETTINGS
        ),
        dangerLevel = DangerLevel.LOW
    )
    override val capability: JarvisCapability = JarvisCapability.Brightness

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("percent") {
                put("type", "number")
                put("description", "Целевая яркость в процентах (0-100). Если не указана и delta не указан — вернуть текущую яркость")
            }
            putJsonObject("delta") {
                put("type", "number")
                put("description", "Относительное изменение яркости в процентах (-100..100): текущая яркость + delta. Пример: 'увеличь на 20' → delta=20")
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val requested = arguments["percent"]?.jsonPrimitive?.intOrNull
            ?: arguments["level"]?.jsonPrimitive?.intOrNull
        val delta = arguments["delta"]?.jsonPrimitive?.intOrNull

        if (requested == null && delta == null) {
            return readCurrentBrightness()
        }

        // Абсолютное значение или относительное смещение от текущей яркости.
        // N-01: не используем !! — ранний выход при null (предохраняет от NPE
        // в случае, если вызов пришёл без обоих аргументов из стороннего path).
        val targetPercent = if (delta != null) {
            val current = currentBrightnessPercent()
            if (current < 0) {
                return ToolExecutionResult.failure(
                    summary = "Не удалось прочитать текущую яркость для расчёта изменения",
                    error = "BRIGHTNESS_READ_FAILED"
                )
            }
            (current + delta).coerceIn(0, 100)
        } else {
            val safeRequested = requested
                ?: return ToolExecutionResult.failure(
                    summary = "Не указана яркость для установки",
                    error = "BRIGHTNESS_VALUE_REQUIRED"
                )
            safeRequested.coerceIn(0, 100)
        }

        // Алгоритм: canWrite() → YES: изменить яркость / NO: системное разрешение.
        if (!capabilities.canWriteSystemSettings()) {
            val opened = openWriteSettingsScreen()
            return ToolExecutionResult.userActionRequired(
                summary = if (opened) {
                    "Чтобы менять яркость, JARVIS нужно разрешение «Изменение системных настроек». Открыл нужный экран — включите доступ, сэр."
                } else {
                    "Чтобы менять яркость, нужно вручную выдать разрешение «Изменение системных настроек» в настройках приложения, сэр."
                },
                reason = "WRITE_SETTINGS_PERMISSION_REQUIRED",
                data = buildJsonObject {
                    put("requested_percent", targetPercent)
                    put("opened_settings", opened)
                    put("permission", Settings.ACTION_MANAGE_WRITE_SETTINGS)
                }
            )
        }

        // Сохраняем старое значение (и режим автояркости) для rollback.
        val previousPercent = currentBrightnessPercent()
        val previousMode = currentBrightnessMode()

        return try {
            // Автояркость перетирает ручное значение — отключаем её перед записью.
            val modeWritten = Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            // putInt возвращает false при молчаливом отказе системы — это НЕ успех.
            if (!modeWritten) {
                return ToolExecutionResult.failure(
                    summary = "Система отклонила отключение автояркости — яркость могла не измениться",
                    error = "BRIGHTNESS_MODE_WRITE_REJECTED"
                )
            }
            val expectedRaw = percentToRaw(targetPercent)
            val valueWritten = Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                expectedRaw
            )
            if (!valueWritten) {
                return ToolExecutionResult.failure(
                    summary = "Система отклонила изменение яркости",
                    error = "BRIGHTNESS_WRITE_REJECTED"
                )
            }

            // Draft: запись принята системой, но НЕ подтверждена read-back'ом.
            // Финальный вердикт — в verify() (молчаливый откат системой/вендором
            // там станет честным FAILURE).
            ToolExecutionResult.success(
                summary = "Применяю яркость $targetPercent%",
                data = buildJsonObject {
                    put("target_percent", targetPercent)
                    put("previous_percent", previousPercent)
                    put("previous_mode", previousMode)
                },
                rollbackData = buildJsonObject {
                    put("previous_percent", previousPercent)
                    put("previous_mode", previousMode)
                }
            )
        } catch (e: SecurityException) {
            ToolExecutionResult.userActionRequired(
                summary = "Система отклонила изменение яркости: нет разрешения на запись системных настроек",
                reason = "WRITE_SETTINGS_DENIED",
                data = buildJsonObject { put("requested_percent", targetPercent) }
            )
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult.failure(
                summary = "Не удалось изменить яркость: ${e.localizedMessage}",
                error = "BRIGHTNESS_WRITE_FAILED"
            )
        }
    }

    // ------------------------------------------------------------ verify

    override suspend fun verify(arguments: JsonObject, draft: ToolExecutionResult): ToolExecutionResult {
        val targetPercent = draft.data?.get("target_percent")?.jsonPrimitive?.intOrNull
            ?: return ToolExecutionResult.failure(
                "Не удалось подтвердить установку яркости: нет целевого значения",
                "BRIGHTNESS_VERIFY_FAILED"
            )
        val expectedRaw = percentToRaw(targetPercent)

        // ---------------------------------------------------------- VERIFY
        // Read-back: SUCCESS только если система подтвердила записанное
        // значение. Молчаливый откат яркости системой/производителем —
        // честный FAILURE, а не «готово».
        val verifiedRaw = ExecutionVerification.pollFor(
            read = {
                try {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                } catch (_: Settings.SettingNotFoundException) {
                    null
                }
            },
            satisfied = { it == expectedRaw }
        )
        if (!ExecutionVerification.brightnessVerified(verifiedRaw, expectedRaw)) {
            return ToolExecutionResult.failure(
                summary = "Не удалось подтвердить установку яркости" +
                    (verifiedRaw?.let { raw -> " — фактический уровень ${rawToPercent(raw)}%" } ?: ""),
                error = "BRIGHTNESS_VERIFY_FAILED",
                data = buildJsonObject {
                    put("requested_percent", targetPercent)
                    put("actual_raw", verifiedRaw)
                }
            )
        }

        return ToolExecutionResult.success(
            summary = "Яркость экрана установлена на $targetPercent%",
            data = draft.data,
            rollbackData = draft.rollbackData
        )
    }

    override suspend fun rollback(arguments: JsonObject, rollbackData: JsonObject?): Boolean {
        val previous = rollbackData?.get("previous_percent")?.jsonPrimitive?.intOrNull ?: return false
        if (!capabilities.canWriteSystemSettings()) return false
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, percentToRaw(previous))
            // Если до нашего вмешательства автояркость была включена — возвращаем её.
            val previousMode = rollbackData.get("previous_mode")?.jsonPrimitive?.intOrNull
            if (previousMode != null) {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, previousMode)
            }
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun readCurrentBrightness(): ToolExecutionResult {
        val percent = currentBrightnessPercent()
        return if (percent >= 0) {
            ToolExecutionResult.success(
                summary = "Текущая яркость экрана: $percent%",
                data = buildJsonObject {
                    put("percent", percent)
                    put("can_change", capabilities.canWriteSystemSettings())
                }
            )
        } else {
            ToolExecutionResult.failure("Не удалось прочитать текущую яркость", "BRIGHTNESS_READ_FAILED")
        }
    }

    private fun currentBrightnessPercent(): Int = try {
        rawToPercent(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    } catch (_: Settings.SettingNotFoundException) {
        -1
    }

    /** Чистое преобразование raw → percent (та же формула, что в read-back верификации). */
    private fun rawToPercent(raw: Int): Int =
        (raw * 100f / MAX_RAW_BRIGHTNESS).roundToInt().coerceIn(0, 100)

    private fun currentBrightnessMode(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
    } catch (_: Settings.SettingNotFoundException) {
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    }

    private fun percentToRaw(percent: Int): Int =
        (percent / 100f * MAX_RAW_BRIGHTNESS).roundToInt().coerceIn(MIN_RAW_BRIGHTNESS, MAX_RAW_BRIGHTNESS)

    private fun openWriteSettingsScreen(): Boolean {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            return try {
                context.startActivity(intent)
                true
            } catch (_: android.content.ActivityNotFoundException) {
                false
            }
        }
        val fallback = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try {
            context.startActivity(fallback)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }

    private companion object {
        /**
         * Android хранит яркость как 0..255 на подавляющем большинстве устройств.
         * Значение ниже MIN_RAW делает экран нечитаемым, поэтому ограничиваем снизу.
         */
        const val MAX_RAW_BRIGHTNESS = 255
        const val MIN_RAW_BRIGHTNESS = 1
    }
}
