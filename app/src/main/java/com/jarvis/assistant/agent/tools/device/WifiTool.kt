package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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
 * WiFi Tool v2.1 (Ear-First Quick Settings Support)
 * 
 * - Автономно переключает Wi-Fi через Accessibility Quick Settings Clicker без касания экрана
 * - Показывает информацию о подключении (SSID, уровень сигнала)
 * - Отказоустойчивый fallback на панель Settings.Panel.ACTION_WIFI
 */
@Singleton
class WifiTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "device.wifi"
    override val description: String = "Проверяет статус Wi-Fi, переключает состояние и показывает информацию о сети"
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
        
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        
        if (wifiManager == null) {
            return ToolExecutionResult.failure(
                summary = "Wi-Fi не поддерживается на этом устройстве",
                error = "WIFI_NOT_SUPPORTED"
            )
        }
        
        val isWifiEnabled = wifiManager.isWifiEnabled
        val connectionInfo = getWifiConnectionInfo(connectivityManager, wifiManager)
        
        return when (action) {
            "status" -> {
                val statusText = buildString {
                    if (isWifiEnabled) {
                        append("Wi-Fi включён")
                        if (connectionInfo.isConnected) {
                            append(". Подключён к сети")
                            if (connectionInfo.ssid.isNotBlank()) {
                                append(" \"${connectionInfo.ssid}\"")
                            }
                            if (connectionInfo.signalStrength.isNotBlank()) {
                                append(" (${connectionInfo.signalStrength})")
                            }
                        } else {
                            append(", но не подключён к сети")
                        }
                    } else {
                        append("Wi-Fi выключен")
                    }
                }
                
                ToolExecutionResult.success(
                    summary = statusText,
                    data = buildJsonObject {
                        put("wifi_enabled", isWifiEnabled)
                        put("connected", connectionInfo.isConnected)
                        put("ssid", connectionInfo.ssid)
                        put("signal_strength", connectionInfo.signalStrength)
                        put("action", "status")
                    }
                )
            }
            
            "enable", "disable", "toggle" -> {
                val wantsEnable = action == "enable" || (action == "toggle" && !isWifiEnabled)
                if (wantsEnable && isWifiEnabled) {
                    return ToolExecutionResult.success(
                        summary = "Wi-Fi уже включён" + if (connectionInfo.isConnected) ". Сеть: ${connectionInfo.ssid}" else "",
                        data = buildJsonObject { put("wifi_enabled", true) }
                    )
                }
                if (!wantsEnable && !isWifiEnabled) {
                    return ToolExecutionResult.success(
                        summary = "Wi-Fi уже выключен, сэр.",
                        data = buildJsonObject { put("wifi_enabled", false) }
                    )
                }

                // Способ 1 (Ear-First в кармане): Автономный клик по плитке Quick Settings через Accessibility
                if (JarvisAccessibilityService.isServiceRunning()) {
                    val clicked = JarvisAccessibilityService.toggleQuickSettingTile(listOf("Wi-Fi", "вайфай", "интернет", "сеть"))
                    if (clicked) {
                        val stateWord = if (wantsEnable) "включён" else "выключен"
                        return ToolExecutionResult.success("Wi-Fi $stateWord через шторку быстрых настроек, сэр.")
                    }
                }

                // Способ 2 (Fallback): Открытие системной панели
                openWifiSettings()
                ToolExecutionResult.success(
                    summary = "Открываю панель Wi-Fi для переключения, сэр.",
                    actionRequiresUser = true,
                    data = buildJsonObject {
                        put("wifi_enabled", isWifiEnabled)
                        put("opened_settings", true)
                    }
                )
            }
            
            "settings" -> {
                openWifiSettings()
                ToolExecutionResult.success(
                    summary = "Открываю настройки Wi-Fi",
                    actionRequiresUser = true,
                    data = buildJsonObject {
                        put("wifi_enabled", isWifiEnabled)
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
    
    private data class WifiConnectionInfo(
        val isConnected: Boolean,
        val ssid: String,
        val signalStrength: String
    )
    
    private fun getWifiConnectionInfo(
        connectivityManager: ConnectivityManager?,
        wifiManager: WifiManager
    ): WifiConnectionInfo {
        if (connectivityManager == null) {
            return WifiConnectionInfo(false, "", "")
        }
        
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        
        val isConnectedToWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        
        if (!isConnectedToWifi) {
            return WifiConnectionInfo(false, "", "")
        }
        
        return try {
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo?.ssid?.replace("\"", "") ?: ""
            val rssi = wifiInfo?.rssi ?: -100
            
            val signalStrength = when {
                rssi >= -50 -> "отличный сигнал"
                rssi >= -60 -> "хороший сигнал"
                rssi >= -70 -> "средний сигнал"
                else -> "слабый сигнал"
            }
            
            WifiConnectionInfo(
                isConnected = true,
                ssid = if (ssid == "<unknown ssid>") "" else ssid,
                signalStrength = signalStrength
            )
        } catch (_: Exception) {
            WifiConnectionInfo(true, "", "")
        }
    }
    
    private fun openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
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
