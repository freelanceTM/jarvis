package com.jarvis.assistant.agent.tools

import com.jarvis.assistant.agent.location.LocationProvider
import com.jarvis.assistant.agent.location.LocationResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.tools.intelligence.WeatherTool
import com.jarvis.assistant.agent.weather.GeoPoint
import com.jarvis.assistant.agent.weather.WeatherProvider
import com.jarvis.assistant.agent.weather.WeatherReport
import com.jarvis.assistant.agent.weather.WeatherResult
import com.jarvis.assistant.core.network.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * Тесты WeatherTool.
 *
 * Главное требование: НИКАКОГО захардкоженного города. Локация — параметр:
 *   weather()                → текущее местоположение
 *   weather(location=Берлин) → явный город
 * Если местоположения нет и город не назван — честный отказ, а не фейковая погода.
 */
class WeatherToolTest {

    private class FakeNetworkMonitor(private val online: Boolean = true) : NetworkMonitor {
        override val isOnline: Flow<Boolean> = flowOf(online)
        override fun isCurrentlyOnline(): Boolean = online
    }

    private class FakeWeatherProvider(
        private val geocodeResult: GeoPoint? = GeoPoint(52.52, 13.40, "Берлин"),
        private val forecastResult: (GeoPoint) -> WeatherResult = { point ->
            WeatherResult.Success(
                WeatherReport(point.displayName, 21.0, 19.0, 10.0, "ясно")
            )
        }
    ) : WeatherProvider {
        var geocodedQuery: String? = null
            private set
        var forecastPoint: GeoPoint? = null
            private set

        override suspend fun geocode(query: String): GeoPoint? {
            geocodedQuery = query
            return geocodeResult
        }

        override suspend fun forecast(point: GeoPoint): WeatherResult {
            forecastPoint = point
            return forecastResult(point)
        }
    }

    private class FakeLocationProvider(
        private val result: LocationResult
    ) : LocationProvider {
        override suspend fun currentLocation(): LocationResult = result
    }

    private fun tool(
        location: LocationResult = LocationResult.Available(55.75, 37.62, "Москва"),
        provider: WeatherProvider = FakeWeatherProvider(),
        online: Boolean = true
    ) = WeatherTool(FakeLocationProvider(location), provider, FakeNetworkMonitor(online))

    @Test
    fun `named location is geocoded and used`() = runBlocking {
        val provider = FakeWeatherProvider()
        val result = tool(provider = provider).execute(buildJsonObject { put("location", "Берлин") })

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals("Берлин", provider.geocodedQuery)
        assertTrue(result.summary.contains("Берлин"))
        assertTrue(result.summary.contains("21"))
    }

    @Test
    fun `no location parameter uses current device location`() = runBlocking {
        val provider = FakeWeatherProvider()
        val result = tool(provider = provider).execute(JsonObject(emptyMap()))

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertNull("Current location must not be geocoded by name", provider.geocodedQuery)
        assertEquals(55.75, provider.forecastPoint?.latitude ?: 0.0, 0.001)
        assertTrue(result.summary.contains("Москва"))
    }

    @Test
    fun `current location keywords use device location`() = runBlocking {
        val provider = FakeWeatherProvider()
        tool(provider = provider).execute(buildJsonObject { put("location", "здесь") })
        assertNull(provider.geocodedQuery)
    }

    @Test
    fun `missing location permission returns PERMISSION_REQUIRED not fake weather`() = runBlocking {
        val result = tool(
            location = LocationResult.PermissionRequired(listOf("android.permission.ACCESS_FINE_LOCATION"))
        ).execute(JsonObject(emptyMap()))

        assertEquals(ToolExecutionStatus.PERMISSION_REQUIRED, result.status)
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), result.missingPermissions)
    }

    @Test
    fun `unavailable location asks for a city instead of inventing one`() = runBlocking {
        val result = tool(location = LocationResult.Unavailable("GPS выключен"))
            .execute(JsonObject(emptyMap()))

        assertNotEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals("LOCATION_UNAVAILABLE", result.error)
        assertTrue(result.summary.contains("город", ignoreCase = true))
    }

    @Test
    fun `offline returns NETWORK_REQUIRED`() = runBlocking {
        val result = tool(online = false).execute(JsonObject(emptyMap()))

        assertNotEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals("NETWORK_REQUIRED", result.error)
    }

    @Test
    fun `unknown city is reported as not found`() = runBlocking {
        val provider = FakeWeatherProvider(geocodeResult = null)
        val result = tool(provider = provider).execute(buildJsonObject { put("location", "Нетакогогорода") })

        assertEquals("LOCATION_NOT_FOUND", result.error)
    }

    @Test
    fun `provider network failure is surfaced honestly`() = runBlocking {
        val provider = FakeWeatherProvider(
            forecastResult = { WeatherResult.NetworkRequired("таймаут") }
        )
        val result = tool(provider = provider).execute(JsonObject(emptyMap()))

        assertNotEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals("NETWORK_REQUIRED", result.error)
    }

    @Test
    fun `no hardcoded city appears in tool metadata`() {
        val t = tool()
        assertFalse(t.description.contains("Ашхабад"))
        assertFalse(t.description.contains("Ashgabat"))
        assertTrue(t.parametersSchema.toString().contains("location"))
    }
}
