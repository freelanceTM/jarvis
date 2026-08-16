package com.jarvis.assistant.agent.tools.intelligence

import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.DeviceCapability
import com.jarvis.assistant.agent.capability.ToolCapabilityContract
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.location.LocationProvider
import com.jarvis.assistant.agent.location.LocationResult
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.weather.GeoPoint
import com.jarvis.assistant.agent.weather.WeatherProvider
import com.jarvis.assistant.agent.weather.WeatherResult
import com.jarvis.assistant.core.network.NetworkMonitor
import kotlinx.serialization.json.*
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Weather Tool v0.2.
 *
 * Ключевое отличие от прежнего поведения: **никакого захардкоженного города**.
 * Локация — это параметр инструмента:
 *
 *   weather()                 → погода по текущему местоположению
 *   weather(location=Берлин)  → погода по явно названному городу
 *
 * Поток: WeatherTool → LocationProvider → координаты → WeatherProvider → API.
 * Если местоположение недоступно и город не назван — честный отказ с указанием
 * причины, а не выдуманная погода.
 */
@Singleton
class WeatherTool @Inject constructor(
    private val locationProvider: LocationProvider,
    private val weatherProvider: WeatherProvider,
    private val networkMonitor: NetworkMonitor
) : CapabilityAwareTool {

    override val toolId: String = "intelligence.weather"
    override val description: String = "Сообщает погоду по текущему местоположению или по названному городу"
    override val category: ToolCategory = ToolCategory.INTELLIGENCE
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = false
    override val executionTimeoutMs: Long = 10_000L

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(DeviceCapability.READ_LOCATION),
        requiredPermissions = LocationProvider.LOCATION_PERMISSIONS,
        dangerLevel = DangerLevel.LOW
    )

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("location") {
                put("type", "string")
                put(
                    "description",
                    "Город или место. Если не указано — используется текущее местоположение устройства"
                )
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        if (!networkMonitor.isCurrentlyOnline()) {
            return ToolExecutionResult.failure(
                summary = "Нет подключения к интернету — прогноз погоды недоступен",
                error = "NETWORK_REQUIRED"
            )
        }

        val requestedLocation = arguments["location"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: arguments["city"]?.jsonPrimitive?.contentOrNull?.trim()

        val point = if (!requestedLocation.isNullOrEmpty() && !isCurrentLocationKeyword(requestedLocation)) {
            resolveNamedLocation(requestedLocation)
                ?: return ToolExecutionResult.failure(
                    summary = "Не удалось найти место «$requestedLocation» на карте",
                    error = "LOCATION_NOT_FOUND"
                )
        } else {
            when (val current = locationProvider.currentLocation()) {
                is LocationResult.Available -> GeoPoint(
                    latitude = current.latitude,
                    longitude = current.longitude,
                    displayName = current.cityName ?: "текущее местоположение"
                )
                is LocationResult.PermissionRequired -> return ToolExecutionResult.permissionRequired(
                    summary = "Чтобы сообщить погоду поблизости, нужен доступ к местоположению. " +
                        "Либо назовите город, например «погода в Берлине», сэр.",
                    permissions = current.permissions
                )
                is LocationResult.Unavailable -> return ToolExecutionResult.failure(
                    summary = "Текущее местоположение неизвестно: ${current.reason}. Назовите город, сэр.",
                    error = "LOCATION_UNAVAILABLE"
                )
            }
        }

        return when (val result = safeForecast(point)) {
            is WeatherResult.Success -> {
                val report = result.report
                val temp = report.temperatureC.roundToInt()
                val summary = buildString {
                    append("${report.locationName}: ${report.conditionText}, $temp°C")
                    report.apparentTemperatureC?.let { append(", ощущается как ${it.roundToInt()}°C") }
                    report.windSpeedKmh?.let { append(", ветер ${it.roundToInt()} км/ч") }
                }
                ToolExecutionResult.success(
                    summary = summary,
                    data = buildJsonObject {
                        put("location", report.locationName)
                        put("temperature_c", temp)
                        put("condition", report.conditionText)
                        report.apparentTemperatureC?.let { put("apparent_c", it.roundToInt()) }
                        report.windSpeedKmh?.let { put("wind_kmh", it.roundToInt()) }
                    }
                )
            }

            is WeatherResult.NetworkRequired -> ToolExecutionResult.failure(
                summary = "Сервис погоды недоступен: ${result.reason}",
                error = "NETWORK_REQUIRED"
            )

            is WeatherResult.ConfigurationRequired -> ToolExecutionResult.failure(
                summary = "Погодный провайдер не настроен: ${result.reason}",
                error = "CONFIGURATION_REQUIRED"
            )

            is WeatherResult.LocationNotFound -> ToolExecutionResult.failure(
                summary = "Не удалось определить место «${result.query}»",
                error = "LOCATION_NOT_FOUND"
            )

            is WeatherResult.Error -> ToolExecutionResult.failure(
                summary = "Не удалось получить погоду: ${result.reason}",
                error = "WEATHER_ERROR"
            )
        }
    }

    private suspend fun resolveNamedLocation(query: String): GeoPoint? = try {
        weatherProvider.geocode(query)
    } catch (_: IOException) {
        null
    }

    private suspend fun safeForecast(point: GeoPoint): WeatherResult = try {
        weatherProvider.forecast(point)
    } catch (e: IOException) {
        WeatherResult.NetworkRequired(e.localizedMessage ?: "сетевая ошибка")
    }

    private fun isCurrentLocationKeyword(value: String): Boolean {
        val v = value.lowercase()
        return v in setOf(
            "current_location", "current", "здесь", "тут", "рядом",
            "текущее местоположение", "мое местоположение", "моё местоположение"
        )
    }
}
