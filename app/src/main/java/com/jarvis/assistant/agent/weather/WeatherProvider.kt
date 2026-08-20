package com.jarvis.assistant.agent.weather

import com.jarvis.assistant.core.network.readUtf8Bounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Точка на карте, для которой запрашивается погода.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val displayName: String
)

data class WeatherReport(
    val locationName: String,
    val temperatureC: Double,
    val apparentTemperatureC: Double?,
    val windSpeedKmh: Double?,
    val conditionText: String
)

/**
 * Результат запроса погоды. Никаких выдуманных значений: если данных нет,
 * это явное состояние, которое инструмент передаёт пользователю как есть.
 */
sealed interface WeatherResult {
    data class Success(val report: WeatherReport) : WeatherResult
    data class LocationNotFound(val query: String) : WeatherResult
    data class NetworkRequired(val reason: String) : WeatherResult
    data class ConfigurationRequired(val reason: String) : WeatherResult
    data class Error(val reason: String) : WeatherResult
}

/**
 * Абстракция поставщика погоды. Позволяет заменить бэкенд, не трогая Tool.
 */
interface WeatherProvider {
    suspend fun geocode(query: String): GeoPoint?
    suspend fun forecast(point: GeoPoint): WeatherResult
}

/**
 * Реализация на Open-Meteo.
 *
 * Выбран сознательно: публичный API без ключа и без регистрации, поэтому в
 * репозитории не появляется секрет, а пользователю не нужна конфигурация.
 * Работает поверх уже имеющегося в проекте OkHttpClient — новых зависимостей нет.
 */
@Singleton
class OpenMeteoWeatherProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) : WeatherProvider {

    companion object {
        private const val MAX_RESPONSE_BYTES = 512L * 1024
        private const val PER_REQUEST_TIMEOUT_SECONDS = 4L
    }

    private val weatherHttpClient = okHttpClient.newBuilder()
        .callTimeout(PER_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun geocode(query: String): GeoPoint? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=ru&format=json"
        val body = executeGet(url) ?: return@withContext null

        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
        val first = results?.firstOrNull()?.jsonObject ?: return@withContext null

        val lat = first["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@withContext null
        val lon = first["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@withContext null
        val name = first["name"]?.jsonPrimitive?.content ?: query

        GeoPoint(lat, lon, name)
    }

    override suspend fun forecast(point: GeoPoint): WeatherResult = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${point.latitude}&longitude=${point.longitude}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"

        val body = try {
            executeGet(url) ?: return@withContext WeatherResult.NetworkRequired(
                "Сервис погоды не ответил"
            )
        } catch (e: IOException) {
            return@withContext WeatherResult.NetworkRequired(
                e.localizedMessage ?: "Нет соединения с сервисом погоды"
            )
        }

        val current = json.parseToJsonElement(body).jsonObject["current"]?.jsonObject
            ?: return@withContext WeatherResult.Error("Некорректный ответ сервиса погоды")

        val temperature = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull
            ?: return@withContext WeatherResult.Error("В ответе нет температуры")

        WeatherResult.Success(
            WeatherReport(
                locationName = point.displayName,
                temperatureC = temperature,
                apparentTemperatureC = current["apparent_temperature"]?.jsonPrimitive?.doubleOrNull,
                windSpeedKmh = current["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull,
                conditionText = describeWeatherCode(current["weather_code"]?.jsonPrimitive?.intOrNull)
            )
        )
    }

    /** @throws IOException при сетевой ошибке — вызывающий обязан обработать. */
    private fun executeGet(url: String): String? {
        val request = Request.Builder().url(url).get().build()
        weatherHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body
                ?.readUtf8Bounded(MAX_RESPONSE_BYTES)
                ?.takeIf { it.isNotBlank() }
        }
    }

    /** WMO Weather interpretation codes, используемые Open-Meteo. */
    private fun describeWeatherCode(code: Int?): String = when (code) {
        0 -> "ясно"
        1 -> "преимущественно ясно"
        2 -> "переменная облачность"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51, 53, 55 -> "морось"
        56, 57 -> "ледяная морось"
        61, 63, 65 -> "дождь"
        66, 67 -> "ледяной дождь"
        71, 73, 75 -> "снег"
        77 -> "снежная крупа"
        80, 81, 82 -> "ливень"
        85, 86 -> "снегопад"
        95 -> "гроза"
        96, 99 -> "гроза с градом"
        else -> "погодные условия неизвестны"
    }
}
