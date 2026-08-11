package com.jarvis.assistant.agent.tools.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetNetworkStatusTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "get_network_status"
    override val description: String = "Проверяет статус подключения к интернету и тип сети (Wi-Fi, Мобильный интернет)"
    override val risk: ToolRisk = ToolRisk.SAFE

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)

        val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (!isConnected) {
            return ToolResult.Success("Интернет-соединение отсутствует. Телефон офлайн.")
        }

        val type = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Мобильная сеть (4G/5G)"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "Bluetooth модем"
            else -> "Сетевое подключение"
        }

        return ToolResult.Success("Подключено к интернету через $type")
    }
}
