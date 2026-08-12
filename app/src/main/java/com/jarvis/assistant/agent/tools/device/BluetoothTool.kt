package com.jarvis.assistant.agent.tools.device

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.tools.accessibility.JarvisAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bluetooth Tool v2.1 (Ear-First Quick Settings Support)
 * 
 * - Автономно переключает Bluetooth через Accessibility Quick Settings Clicker без касания экрана
 * - Проверяет статус и сопряжённые устройства
 * - Отказоустойчивый fallback на настройки Bluetooth
 */
@Singleton
class BluetoothTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "device.bluetooth"
    override val description: String = "Проверяет статус Bluetooth, переключает состояние и открывает настройки сопряжения"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Действие: 'status' - проверить статус, 'enable' - включить, 'disable' - выключить, 'toggle' - переключить, 'settings' - открыть настройки")
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
        val action = arguments["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "status"
        
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter
        
        if (bluetoothAdapter == null) {
            return ToolExecutionResult.failure(
                summary = "Bluetooth не поддерживается на этом устройстве",
                error = "BLUETOOTH_NOT_SUPPORTED"
            )
        }
        
        val isEnabled = bluetoothAdapter.isEnabled
        
        return when (action) {
            "status" -> {
                val statusText = if (isEnabled) {
                    "Bluetooth включён"
                } else {
                    "Bluetooth выключен"
                }
                
                val connectedInfo = if (isEnabled) {
                    try {
                        val bondedDevices = bluetoothAdapter.bondedDevices
                        if (bondedDevices.isNotEmpty()) {
                            ". Сопряжённых устройств: ${bondedDevices.size}"
                        } else {
                            ". Нет сопряжённых устройств"
                        }
                    } catch (_: SecurityException) {
                        ""
                    }
                } else ""
                
                ToolExecutionResult.success(
                    summary = "$statusText$connectedInfo",
                    data = buildJsonObject {
                        put("enabled", isEnabled)
                        put("action", "status")
                    }
                )
            }
            
            "enable", "disable", "toggle" -> {
                val wantsEnable = action == "enable" || (action == "toggle" && !isEnabled)
                if (wantsEnable && isEnabled) {
                    return ToolExecutionResult.success(
                        summary = "Bluetooth уже включён, сэр.",
                        data = buildJsonObject { put("enabled", true) }
                    )
                }
                if (!wantsEnable && !isEnabled) {
                    return ToolExecutionResult.success(
                        summary = "Bluetooth уже выключен, сэр.",
                        data = buildJsonObject { put("enabled", false) }
                    )
                }

                // Способ 1 (Ear-First в кармане): Автономный клик по плитке Quick Settings через Accessibility
                if (JarvisAccessibilityService.isServiceRunning()) {
                    val clicked = JarvisAccessibilityService.toggleQuickSettingTile(listOf("Bluetooth", "блютуз"))
                    if (clicked) {
                        val stateWord = if (wantsEnable) "включён" else "выключен"
                        return ToolExecutionResult.success("Bluetooth $stateWord через шторку быстрых настроек, сэр.")
                    }
                }

                // Способ 2 (Fallback): Открытие настроек Bluetooth
                openBluetoothSettings()
                ToolExecutionResult.success(
                    summary = "Открываю настройки Bluetooth для переключения, сэр.",
                    actionRequiresUser = true,
                    data = buildJsonObject {
                        put("enabled", isEnabled)
                        put("action", action)
                        put("opened_settings", true)
                    }
                )
            }
            
            "settings" -> {
                openBluetoothSettings()
                ToolExecutionResult.success(
                    summary = "Открываю настройки Bluetooth",
                    actionRequiresUser = true,
                    data = buildJsonObject {
                        put("enabled", isEnabled)
                        put("action", "settings")
                        put("opened_settings", true)
                    }
                )
            }
            
            else -> {
                ToolExecutionResult.failure(
                    summary = "Неизвестное действие: $action",
                    error = "UNKNOWN_ACTION"
                )
            }
        }
    }
    
    private fun openBluetoothSettings() {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) { }
        }
    }
}
