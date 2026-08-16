package com.jarvis.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.DeviceCapability
import com.jarvis.assistant.agent.capability.DeviceCapabilityRegistry
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
 * Wi-Fi Tool v0.2 — честная работа в рамках Android restrictions.
 *
 * [WifiManager.setWifiEnabled] возвращает false для приложений с targetSdk >= 29
 * (Android 10+). Приложение не может включить или выключить Wi-Fi самостоятельно.
 * Единственный легальный путь — системная панель [Settings.Panel.ACTION_INTERNET_CONNECTIVITY]
 * / [Settings.Panel.ACTION_WIFI], где переключение делает пользователь.
 *
 * Поэтому:
 *  - 'status'  → реальные данные о состоянии и подключении (SUCCESS);
 *  - 'toggle'  → USER_ACTION_REQUIRED + открытие системной панели.
 */
@Singleton
class WifiTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilityRegistry
) : CapabilityAwareTool {

    override val toolId: String = "device.wifi"
    override val description: String = "Проверяет статус Wi-Fi и подключённую сеть, открывает системную панель Wi-Fi"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(
            DeviceCapability.READ_WIFI_STATE,
            DeviceCapability.OPEN_WIFI_SETTINGS
        ),
        dangerLevel = DangerLevel.LOW
    )

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put(
                    "description",
                    "Действие: 'status' — статус и текущая сеть, 'settings' — открыть панель Wi-Fi, " +
                        "'enable'/'disable'/'toggle' — запрос на переключение (выполняется пользователем в системной панели)"
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

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ToolExecutionResult.unsupported(
                summary = "Wi-Fi не поддерживается на этом устройстве",
                reason = "WIFI_NOT_SUPPORTED"
            )

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isWifiEnabled = wifiManager.isWifiEnabled
        val connection = getWifiConnectionInfo(connectivityManager, wifiManager)

        return when (action) {
            "status" -> {
                val summary = buildString {
                    if (isWifiEnabled) {
                        append("Wi-Fi включён")
                        if (connection.isConnected) {
                            append(". Подключён к сети")
                            if (connection.ssid.isNotBlank()) append(" \"${connection.ssid}\"")
                            if (connection.signalStrength.isNotBlank()) append(" (${connection.signalStrength})")
                        } else {
                            append(", но не подключён к сети")
                        }
                    } else {
                        append("Wi-Fi выключен")
                    }
                }
                ToolExecutionResult.success(
                    summary = summary,
                    data = buildJsonObject {
                        put("wifi_enabled", isWifiEnabled)
                        put("connected", connection.isConnected)
                        put("ssid", connection.ssid)
                        put("signal_strength", connection.signalStrength)
                        put("action", "status")
                    }
                )
            }

            "settings" -> {
                val opened = openWifiPanel()
                if (opened) {
                    ToolExecutionResult.userActionRequired(
                        summary = "Открываю системную панель Wi-Fi",
                        reason = "OPENED_WIFI_PANEL",
                        data = buildJsonObject {
                            put("wifi_enabled", isWifiEnabled)
                            put("opened_settings", true)
                        }
                    )
                } else {
                    ToolExecutionResult.failure("Не удалось открыть панель Wi-Fi", "SETTINGS_INTENT_UNRESOLVED")
                }
            }

            "enable", "disable", "toggle" -> {
                val wantsEnable = action == "enable" || (action == "toggle" && !isWifiEnabled)
                if (action != "toggle" && wantsEnable == isWifiEnabled) {
                    return ToolExecutionResult.success(
                        summary = if (isWifiEnabled) "Wi-Fi уже включён, сэр." else "Wi-Fi уже выключен, сэр.",
                        data = buildJsonObject { put("wifi_enabled", isWifiEnabled) }
                    )
                }

                val target = if (wantsEnable) "включить" else "выключить"
                val opened = openWifiPanel()
                ToolExecutionResult.userActionRequired(
                    summary = if (opened) {
                        "Начиная с Android 10 приложения не могут $target Wi-Fi самостоятельно. Открыл системную панель — переключите Wi-Fi, сэр."
                    } else {
                        "Начиная с Android 10 приложения не могут $target Wi-Fi самостоятельно, и панель открыть не удалось. Переключите Wi-Fi вручную, сэр."
                    },
                    reason = "WIFI_TOGGLE_REQUIRES_USER",
                    data = buildJsonObject {
                        put("wifi_enabled", isWifiEnabled)
                        put("requested_action", action)
                        put("opened_settings", opened)
                    }
                )
            }

            else -> ToolExecutionResult.failure("Неизвестное действие: $action", "UNKNOWN_ACTION")
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
        if (connectivityManager == null) return WifiConnectionInfo(false, "", "")

        val capabilitiesInfo = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val isConnectedToWifi = capabilitiesInfo?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!isConnectedToWifi) return WifiConnectionInfo(false, "", "")

        // SSID доступен только при наличии разрешения на локацию (Android 8.1+).
        // Без него система возвращает "<unknown ssid>" — показываем честно как пустое имя.
        val hasLocation = capabilities.statusOf(DeviceCapability.READ_LOCATION).isAvailable

        val rssi = capabilitiesInfo.let {
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo?.rssi ?: -100
        }
        val signalStrength = when {
            rssi >= -50 -> "отличный сигнал"
            rssi >= -60 -> "хороший сигнал"
            rssi >= -70 -> "средний сигнал"
            else -> "слабый сигнал"
        }

        @Suppress("DEPRECATION")
        val rawSsid = if (hasLocation) wifiManager.connectionInfo?.ssid.orEmpty() else ""
        val ssid = rawSsid.replace("\"", "").let { if (it == "<unknown ssid>") "" else it }

        return WifiConnectionInfo(isConnected = true, ssid = ssid, signalStrength = signalStrength)
    }

    /** @return true, если системная панель/экран Wi-Fi действительно открыты. */
    private fun openWifiPanel(): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Intent(Settings.Panel.ACTION_WIFI))
            }
            add(Intent(Settings.ACTION_WIFI_SETTINGS))
            add(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
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
