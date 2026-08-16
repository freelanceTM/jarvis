package com.jarvis.assistant.agent.capability

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.assistant.agent.tools.accessibility.JarvisAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единая точка правды о том, что JARVIS реально может сделать на этом устройстве.
 *
 * Здесь собраны все ограничения Android по API-level и permission model, чтобы
 * инструменты не дублировали проверки и не «додумывали» возможности:
 *
 *  - Bluetooth: [android.bluetooth.BluetoothAdapter.enable] помечен deprecated с API 33
 *    и не работает для обычных приложений начиная с Android 13. Программное
 *    переключение мы объявляем доступным только до API 32 и только при наличии
 *    BLUETOOTH_CONNECT / BLUETOOTH_ADMIN.
 *  - Wi-Fi: [WifiManager.setWifiEnabled] возвращает false для приложений с
 *    targetSdk >= 29 (Android 10). Программное переключение недоступно всегда,
 *    остаётся Settings.Panel.
 *  - Яркость: требуется специальное разрешение WRITE_SETTINGS, проверяется
 *    через [Settings.System.canWrite].
 *  - Скриншот: GLOBAL_ACTION_TAKE_SCREENSHOT доступен только с API 30 и только
 *    активной AccessibilityService; альтернатива — MediaProjection с явным
 *    согласием пользователя.
 */
@Singleton
class DeviceCapabilityRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) : CapabilityChecker {

    fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun missingPermissions(permissions: List<String>): List<String> =
        permissions.filterNot { isPermissionGranted(it) }

    // ------------------------------------------------------------------ Bluetooth

    private val bluetoothAdapter
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun hasBluetoothHardware(): Boolean = bluetoothAdapter != null

    /** Разрешения, нужные для чтения состояния и списка сопряжённых устройств. */
    fun bluetoothReadPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }

    override fun statusOf(capability: DeviceCapability): CapabilityStatus = when (capability) {
        DeviceCapability.READ_BLUETOOTH_STATE -> when {
            !hasBluetoothHardware() -> CapabilityStatus.Unsupported("Bluetooth-адаптер отсутствует на устройстве")
            missingPermissions(bluetoothReadPermissions()).isNotEmpty() ->
                CapabilityStatus.PermissionRequired(missingPermissions(bluetoothReadPermissions()))
            else -> CapabilityStatus.Available
        }

        DeviceCapability.TOGGLE_BLUETOOTH_DIRECTLY -> when {
            !hasBluetoothHardware() -> CapabilityStatus.Unsupported("Bluetooth-адаптер отсутствует на устройстве")
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> CapabilityStatus.UserActionRequired(
                "Android 13+ запрещает приложениям включать Bluetooth программно (BluetoothAdapter.enable удалён для сторонних приложений)"
            )
            missingPermissions(bluetoothTogglePermissions()).isNotEmpty() ->
                CapabilityStatus.PermissionRequired(missingPermissions(bluetoothTogglePermissions()))
            else -> CapabilityStatus.Available
        }

        DeviceCapability.OPEN_BLUETOOTH_SETTINGS ->
            if (canResolve(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) CapabilityStatus.Available
            else CapabilityStatus.Unsupported("Системный экран Bluetooth недоступен")

        // ------------------------------------------------------------------ Wi-Fi

        DeviceCapability.READ_WIFI_STATE ->
            if (context.getSystemService(Context.WIFI_SERVICE) is WifiManager) CapabilityStatus.Available
            else CapabilityStatus.Unsupported("Wi-Fi не поддерживается на этом устройстве")

        DeviceCapability.TOGGLE_WIFI_DIRECTLY -> CapabilityStatus.UserActionRequired(
            "Начиная с Android 10 WifiManager.setWifiEnabled недоступен приложениям — требуется системная панель Wi-Fi"
        )

        DeviceCapability.OPEN_WIFI_SETTINGS -> CapabilityStatus.Available

        // ------------------------------------------------------------------ Яркость

        DeviceCapability.READ_BRIGHTNESS -> CapabilityStatus.Available

        DeviceCapability.WRITE_BRIGHTNESS ->
            if (canWriteSystemSettings()) CapabilityStatus.Available
            else CapabilityStatus.UserActionRequired(
                "Нужно специальное разрешение «Изменение системных настроек» (WRITE_SETTINGS)"
            )

        DeviceCapability.OPEN_DISPLAY_SETTINGS -> CapabilityStatus.Available

        // ------------------------------------------------------------------ Скриншот

        DeviceCapability.TAKE_SCREENSHOT_ACCESSIBILITY -> when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> CapabilityStatus.Unsupported(
                "Системный скриншот через AccessibilityService доступен только с Android 11"
            )
            !JarvisAccessibilityService.isServiceRunning() -> CapabilityStatus.UserActionRequired(
                "Служба специальных возможностей JARVIS не включена"
            )
            else -> CapabilityStatus.Available
        }

        DeviceCapability.TAKE_SCREENSHOT_MEDIA_PROJECTION -> CapabilityStatus.UserActionRequired(
            "MediaProjection требует явного согласия пользователя в системном диалоге"
        )

        // ------------------------------------------------------------------ Связь

        DeviceCapability.SEND_SMS_DIRECTLY -> when {
            !hasTelephony() -> CapabilityStatus.Unsupported("Устройство не поддерживает отправку SMS")
            !isPermissionGranted(Manifest.permission.SEND_SMS) ->
                CapabilityStatus.PermissionRequired(listOf(Manifest.permission.SEND_SMS))
            else -> CapabilityStatus.Available
        }

        DeviceCapability.OPEN_SMS_COMPOSER -> CapabilityStatus.Available

        DeviceCapability.PLACE_CALL_DIRECTLY -> when {
            !hasTelephony() -> CapabilityStatus.Unsupported("Устройство не поддерживает телефонные вызовы")
            !isPermissionGranted(Manifest.permission.CALL_PHONE) ->
                CapabilityStatus.PermissionRequired(listOf(Manifest.permission.CALL_PHONE))
            else -> CapabilityStatus.Available
        }

        DeviceCapability.OPEN_DIALER ->
            if (hasTelephony()) CapabilityStatus.Available
            else CapabilityStatus.Unsupported("Номеронабиратель недоступен на этом устройстве")

        DeviceCapability.READ_CONTACTS ->
            if (isPermissionGranted(Manifest.permission.READ_CONTACTS)) CapabilityStatus.Available
            else CapabilityStatus.PermissionRequired(listOf(Manifest.permission.READ_CONTACTS))

        DeviceCapability.READ_LOCATION -> {
            val missing = missingPermissions(
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            // Достаточно одного из двух разрешений
            if (missing.size == 2) CapabilityStatus.PermissionRequired(missing) else CapabilityStatus.Available
        }

        DeviceCapability.CONTROL_DND ->
            if (isNotificationPolicyAccessGranted()) CapabilityStatus.Available
            else CapabilityStatus.UserActionRequired("Нужен доступ к политике уведомлений (Do Not Disturb access)")

        DeviceCapability.CONTROL_MEDIA -> CapabilityStatus.Available

        DeviceCapability.CONTROL_FLASHLIGHT ->
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) CapabilityStatus.Available
            else CapabilityStatus.Unsupported("На устройстве нет вспышки")

        DeviceCapability.CONTROL_VOLUME -> CapabilityStatus.Available
    }

    fun bluetoothTogglePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH_ADMIN)
        }

    fun canWriteSystemSettings(): Boolean = Settings.System.canWrite(context)

    private fun isNotificationPolicyAccessGranted(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        return nm?.isNotificationPolicyAccessGranted == true
    }

    private fun hasTelephony(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    private fun canResolve(intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    /**
     * Снимок возможностей устройства — используется агентом при планировании,
     * чтобы не строить план из заведомо невыполнимых шагов.
     */
    fun snapshot(): Map<DeviceCapability, CapabilityStatus> =
        DeviceCapability.entries.associateWith { statusOf(it) }
}
