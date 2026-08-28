package com.jarvis.assistant.agent.location

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import com.jarvis.assistant.agent.capability.DeviceCapability
import com.jarvis.assistant.agent.capability.DeviceCapabilityRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Текущее местоположение пользователя.
 *
 * Никаких зашитых городов: если местоположение неизвестно, это отдельное
 * состояние, а не «подставим Ашхабад».
 */
sealed interface LocationResult {
    data class Available(
        val latitude: Double,
        val longitude: Double,
        val cityName: String?
    ) : LocationResult

    data class PermissionRequired(val permissions: List<String>) : LocationResult

    /** Разрешение есть, но свежих координат нет (GPS выключен / нет фиксации). */
    data class Unavailable(val reason: String) : LocationResult
}

/**
 * Контракт получения местоположения. Выделен, чтобы WeatherTool и агент
 * зависели от абстракции, а не от Android LocationManager напрямую.
 */
interface LocationProvider {
    suspend fun currentLocation(): LocationResult

    companion object {
        val LOCATION_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}

/**
 * Реализация на стандартном Android LocationManager.
 *
 * Сознательно не тянем Google Play Services (play-services-location): это
 * дополнительная зависимость ~1 МБ и привязка к GMS, а для определения города
 * под погоду достаточно последней известной координаты от системных провайдеров.
 */
@Singleton
class SystemLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilityRegistry
) : LocationProvider {

    override suspend fun currentLocation(): LocationResult = withContext(Dispatchers.IO) {
        val status = capabilities.statusOf(DeviceCapability.READ_LOCATION)
        if (status is com.jarvis.assistant.agent.capability.CapabilityStatus.PermissionRequired) {
            return@withContext LocationResult.PermissionRequired(status.permissions)
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext LocationResult.Unavailable("Служба геолокации недоступна")

        val best = lastKnownLocation(manager)
            ?: return@withContext LocationResult.Unavailable(
                "Нет актуальных координат — включите геолокацию или откройте карты для получения фиксации"
            )

        LocationResult.Available(
            latitude = best.latitude,
            longitude = best.longitude,
            cityName = reverseGeocodeCity(best)
        )
    }

    private fun lastKnownLocation(manager: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (provider in providers) {
            val location = try {
                if (!manager.isProviderEnabled(provider)) continue
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: continue

            if (best == null || location.time > best.time) best = location
        }
        return best
    }

    private fun reverseGeocodeCity(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
            addresses?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        } catch (_: java.io.IOException) {
            // Сетевой геокодер недоступен офлайн — это нормально, вернём null,
            // погода будет запрошена по координатам.
            null
        }
    }
}
