package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bluetooth Tool v0.2 — честная работа в рамках Android restrictions.
 *
 * Что реально возможно:
 *  - читать состояние адаптера и список сопряжённых устройств (BLUETOOTH_CONNECT на API 31+);
 *  - открыть системный экран Bluetooth.
 *
 * Чего делать НЕЛЬЗЯ и мы не делаем:
 *  - BluetoothAdapter.enable()/disable() не работают для обычных приложений на
 *    Android 13+ (deprecated с API 33, возвращают false). Мы не имитируем успех
 *    и не пытаемся обходить ограничение через Accessibility-клики по шторке.
 *
 * Поэтому запрос на переключение возвращает USER_ACTION_REQUIRED и открывает
 * системный UI, где пользователь переключает Bluetooth сам.
 */
@Singleton
class BluetoothTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilityRegistry
) : CapabilityAwareTool {

    override val toolId: String = "device.bluetooth"
    override val description: String = "Проверяет статус Bluetooth и открывает системный экран Bluetooth для подключения устройств"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(
            DeviceCapability.READ_BLUETOOTH_STATE,
            DeviceCapability.OPEN_BLUETOOTH_SETTINGS
        ),
        requiredPermissions = capabilities.bluetoothReadPermissions(),
        dangerLevel = DangerLevel.LOW
    )
    override val capability: JarvisCapability = JarvisCapability.Bluetooth

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put(
                    "description",
                    "Действие: 'status' — проверить статус, 'settings' — открыть системный экран Bluetooth, " +
                        "'enable'/'disable'/'toggle' — запрос на переключение (на современных Android выполняется пользователем в системном UI)"
                )
                put("enum", buildJsonArray {
                    add("status")
                    add("enable")
                    add("disable")
                    add("toggle")
                    add("settings")
                })
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val action = arguments["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: "status"

        if (!capabilities.hasBluetoothHardware()) {
            return ToolExecutionResult.unsupported(
                summary = "На этом устройстве нет Bluetooth-адаптера",
                reason = "BLUETOOTH_NOT_SUPPORTED"
            )
        }

        return when (action) {
            "status" -> readStatus()
            "settings" -> {
                val enabled = try {
                    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                        ?.adapter?.isEnabled == true
                } catch (_: SecurityException) {
                    false
                }
                val statePhrase = if (enabled) "Bluetooth сейчас включён." else "Bluetooth сейчас выключен."
                openSettings("$statePhrase Открываю настройки Bluetooth.")
            }
            "enable", "disable", "toggle" -> requestToggle(action)
            else -> ToolExecutionResult.failure("Неизвестное действие: $action", "UNKNOWN_ACTION")
        }
    }

    private fun readStatus(): ToolExecutionResult {
        when (val status = capabilities.statusOf(DeviceCapability.READ_BLUETOOTH_STATE)) {
            is CapabilityStatus.PermissionRequired -> return ToolExecutionResult.permissionRequired(
                summary = "Нужно разрешение на доступ к Bluetooth, чтобы прочитать его состояние",
                permissions = status.permissions
            )
            is CapabilityStatus.Unsupported -> return ToolExecutionResult.unsupported(
                summary = status.reason,
                reason = "BLUETOOTH_NOT_SUPPORTED"
            )
            else -> Unit
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            ?: return ToolExecutionResult.unsupported(
                summary = "Bluetooth-адаптер недоступен",
                reason = "BLUETOOTH_NOT_SUPPORTED"
            )

        val isEnabled = adapter.isEnabled
        // bondedDevices требует BLUETOOTH_CONNECT; разрешение уже проверено выше,
        // но SecurityException всё равно обрабатываем явно, а не «проглатываем».
        val bondedCount = try {
            adapter.bondedDevices?.size
        } catch (e: SecurityException) {
            return ToolExecutionResult.permissionRequired(
                summary = "Система отклонила доступ к списку сопряжённых устройств",
                permissions = capabilities.bluetoothReadPermissions(),
                data = buildJsonObject { put("enabled", isEnabled) }
            )
        }

        val summary = buildString {
            append(if (isEnabled) "Bluetooth включён" else "Bluetooth выключен")
            if (isEnabled && bondedCount != null) {
                append(if (bondedCount > 0) ". Сопряжённых устройств: $bondedCount" else ". Нет сопряжённых устройств")
            }
        }

        return ToolExecutionResult.success(
            summary = summary,
            data = buildJsonObject {
                put("enabled", isEnabled)
                put("bonded_devices", bondedCount ?: 0)
                put("action", "status")
            }
        )
    }

    /**
     * Запрос на включение/выключение. На современных Android прямое переключение
     * недоступно приложениям — честно сообщаем и открываем системный UI.
     */
    private fun requestToggle(action: String): ToolExecutionResult {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val isEnabled = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }

        val wantsEnable = action == "enable" || (action == "toggle" && !isEnabled)
        if (wantsEnable == isEnabled && action != "toggle") {
            return ToolExecutionResult.success(
                summary = if (isEnabled) "Bluetooth уже включён, сэр." else "Bluetooth уже выключен, сэр.",
                data = buildJsonObject { put("enabled", isEnabled) }
            )
        }

        val toggleStatus = capabilities.statusOf(DeviceCapability.TOGGLE_BLUETOOTH_DIRECTLY)

        return when (toggleStatus) {
            is CapabilityStatus.PermissionRequired -> ToolExecutionResult.permissionRequired(
                summary = "Чтобы управлять Bluetooth, нужно разрешение на доступ к Bluetooth",
                permissions = toggleStatus.permissions,
                data = buildJsonObject { put("enabled", isEnabled) }
            )

            is CapabilityStatus.Unsupported -> ToolExecutionResult.unsupported(
                summary = toggleStatus.reason,
                reason = "BLUETOOTH_NOT_SUPPORTED"
            )

            // Android 13+ и общий случай: переключает пользователь в системном UI.
            else -> {
                val opened = openBluetoothSettingsIntent()
                // Честная формулировка: сообщаем текущее состояние и открываем
                // системный экран — без имитации переключения.
                val statePhrase = if (isEnabled) {
                    "Bluetooth сейчас включён."
                } else {
                    "Bluetooth сейчас выключен."
                }
                ToolExecutionResult.userActionRequired(
                    summary = if (opened) {
                        "$statePhrase Открываю настройки Bluetooth — переключите его там, сэр."
                    } else {
                        "$statePhrase Системный экран Bluetooth открыть не удалось — переключите Bluetooth вручную, сэр."
                    },
                    reason = "BLUETOOTH_TOGGLE_REQUIRES_USER",
                    data = buildJsonObject {
                        put("enabled", isEnabled)
                        put("requested_action", action)
                        put("opened_settings", opened)
                    }
                )
            }
        }
    }

    private fun openSettings(message: String): ToolExecutionResult {
        val opened = openBluetoothSettingsIntent()
        return if (opened) {
            ToolExecutionResult.userActionRequired(
                summary = message,
                reason = "OPENED_BLUETOOTH_SETTINGS",
                data = buildJsonObject {
                    val enabled = try {
                        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                            ?.adapter?.isEnabled == true
                    } catch (_: SecurityException) {
                        false
                    }
                    put("enabled", enabled)
                    put("opened_settings", true)
                }
            )
        } else {
            ToolExecutionResult.failure(
                summary = "Не удалось открыть системный экран Bluetooth",
                error = "SETTINGS_INTENT_UNRESOLVED"
            )
        }
    }

    /** @return true, если системный экран действительно был открыт. */
    private fun openBluetoothSettingsIntent(): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            return try {
                context.startActivity(intent)
                true
            } catch (_: android.content.ActivityNotFoundException) {
                false
            }
        }
        return false
    }
}
