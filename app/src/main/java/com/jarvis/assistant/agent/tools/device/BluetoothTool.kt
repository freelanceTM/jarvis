package com.jarvis.assistant.agent.tools.device

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bluetooth Tool v2.0
 * 
 * Ограничения Android 13+:
 * - Программное включение/выключение Bluetooth ЗАПРЕЩЕНО
 * - Можно только открыть настройки или Quick Settings
 * 
 * Функционал:
 * - Проверка статуса Bluetooth
 * - Открытие настроек Bluetooth (если нужно включить/выключить)
 * - Информирование пользователя о текущем состоянии
 */
@Singleton
class BluetoothTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "device.bluetooth"
    override val description: String = "Проверяет статус Bluetooth и открывает настройки для включения/выключения"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Действие: 'status' - проверить статус, 'enable' - включить, 'disable' - выключить, 'settings' - открыть настройки")
                put("enum", buildJsonArray { 
                    add("status")
                    add("enable")
                    add("disable")
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
                
                // Дополнительная информация о подключённых устройствах
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
            
            "enable" -> {
                if (isEnabled) {
                    ToolExecutionResult.success(
                        summary = "Bluetooth уже включён",
                        data = buildJsonObject {
                            put("enabled", true)
                            put("action", "enable")
                            put("already_enabled", true)
                        }
                    )
                } else {
                    // Android 13+ не позволяет включать Bluetooth программно
                    openBluetoothSettings()
                    ToolExecutionResult.success(
                        summary = "Открываю настройки Bluetooth. Пожалуйста, включите его вручную, сэр.",
                        actionRequiresUser = true,
                        data = buildJsonObject {
                            put("enabled", false)
                            put("action", "enable")
                            put("opened_settings", true)
                        }
                    )
                }
            }
            
            "disable" -> {
                if (!isEnabled) {
                    ToolExecutionResult.success(
                        summary = "Bluetooth уже выключен",
                        data = buildJsonObject {
                            put("enabled", false)
                            put("action", "disable")
                            put("already_disabled", true)
                        }
                    )
                } else {
                    openBluetoothSettings()
                    ToolExecutionResult.success(
                        summary = "Открываю настройки Bluetooth. Пожалуйста, выключите его вручную, сэр.",
                        actionRequiresUser = true,
                        data = buildJsonObject {
                            put("enabled", true)
                            put("action", "disable")
                            put("opened_settings", true)
                        }
                    )
                }
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
            // Пробуем открыть Quick Settings панель Bluetooth
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Fallback на обычные настройки
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) { }
        }
    }
}
