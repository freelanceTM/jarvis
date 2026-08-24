package com.jarvis.assistant.agent.tools.system

import android.content.Context
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetBatteryTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "system.battery"
    override val description: String = "Получает точный процент заряда батареи и статус зарядки устройства"
    override val category: ToolCategory = ToolCategory.SYSTEM
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        if (level < 0 || scale <= 0) {
            return ToolExecutionResult.failure(
                "Не удалось получить состояние аккумулятора",
                "BATTERY_STATUS_UNAVAILABLE"
            )
        }
        val batteryPercent = (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100)

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val chargingText = if (isCharging) "подключено к зарядке" else "работает от аккумулятора"

        val summary = "Уровень заряда $batteryPercent%, $chargingText"
        val dataObj = buildJsonObject {
            put("percent", batteryPercent)
            put("is_charging", isCharging)
        }

        return ToolExecutionResult.success(summary = summary, data = dataObj)
    }
}
