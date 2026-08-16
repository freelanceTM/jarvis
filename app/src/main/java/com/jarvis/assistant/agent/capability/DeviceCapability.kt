package com.jarvis.assistant.agent.capability

/**
 * Capability Registry — единый честный контракт возможностей устройства.
 *
 * Правило проекта: инструмент НЕ имеет права возвращать SUCCESS, если Android
 * фактически не позволил выполнить действие. Вместо этого он обязан объявить,
 * что именно требуется (разрешение / действие пользователя), либо честно
 * сообщить, что возможность недоступна на данном API-level.
 */
enum class DeviceCapability(val id: String, val description: String) {
    READ_BLUETOOTH_STATE("read_bluetooth_state", "Чтение состояния Bluetooth-адаптера"),
    TOGGLE_BLUETOOTH_DIRECTLY("toggle_bluetooth_directly", "Программное включение/выключение Bluetooth"),
    OPEN_BLUETOOTH_SETTINGS("open_bluetooth_settings", "Открытие системного экрана Bluetooth"),

    READ_WIFI_STATE("read_wifi_state", "Чтение состояния Wi-Fi"),
    TOGGLE_WIFI_DIRECTLY("toggle_wifi_directly", "Программное включение/выключение Wi-Fi"),
    OPEN_WIFI_SETTINGS("open_wifi_settings", "Открытие системной панели Wi-Fi"),

    READ_BRIGHTNESS("read_brightness", "Чтение системной яркости"),
    WRITE_BRIGHTNESS("write_brightness", "Изменение системной яркости (WRITE_SETTINGS)"),
    OPEN_DISPLAY_SETTINGS("open_display_settings", "Открытие настроек экрана"),

    TAKE_SCREENSHOT_ACCESSIBILITY("take_screenshot_accessibility", "Системный скриншот через AccessibilityService (API 30+)"),
    TAKE_SCREENSHOT_MEDIA_PROJECTION("take_screenshot_media_projection", "Скриншот через MediaProjection с согласия пользователя"),

    SEND_SMS_DIRECTLY("send_sms_directly", "Прямая отправка SMS (SEND_SMS)"),
    OPEN_SMS_COMPOSER("open_sms_composer", "Открытие SMS-приложения с готовым текстом"),

    PLACE_CALL_DIRECTLY("place_call_directly", "Прямой вызов (CALL_PHONE)"),
    OPEN_DIALER("open_dialer", "Открытие номеронабирателя"),

    READ_CONTACTS("read_contacts", "Поиск номера по имени контакта"),
    READ_LOCATION("read_location", "Определение текущего местоположения"),
    CONTROL_DND("control_dnd", "Управление режимом «Не беспокоить»"),
    CONTROL_MEDIA("control_media", "Управление воспроизведением медиа"),
    CONTROL_FLASHLIGHT("control_flashlight", "Управление фонариком"),
    CONTROL_VOLUME("control_volume", "Управление громкостью")
}

/**
 * Уровень опасности инструмента. Отдельная ось от [DeviceCapability]:
 * capability отвечает на вопрос «могу ли я это технически»,
 * dangerLevel — «нужно ли спрашивать пользователя перед выполнением».
 */
enum class DangerLevel {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Результат проверки возможности выполнить действие на конкретном устройстве.
 */
sealed interface CapabilityStatus {
    /** Возможность доступна прямо сейчас. */
    data object Available : CapabilityStatus

    /** Требуется runtime-разрешение Android. */
    data class PermissionRequired(val permissions: List<String>) : CapabilityStatus

    /**
     * Android не даёт выполнить действие программно; нужен переход пользователя
     * в системный UI (Settings / special access / consent-диалог).
     */
    data class UserActionRequired(val reason: String) : CapabilityStatus

    /** Возможность недоступна на данном устройстве или API-level. */
    data class Unsupported(val reason: String) : CapabilityStatus

    val isAvailable: Boolean get() = this is Available
}

/**
 * Декларация требований инструмента. Позволяет агенту ответить на вопрос
 * «могу ли я выполнить это действие на данном устройстве» ДО вызова execute().
 */
data class ToolCapabilityContract(
    val capabilities: Set<DeviceCapability>,
    val requiredPermissions: List<String> = emptyList(),
    val dangerLevel: DangerLevel = DangerLevel.LOW,
    val confirmationRequired: Boolean = dangerLevel == DangerLevel.HIGH
)

/**
 * Минимальный контракт проверки возможностей.
 *
 * Выделен из [DeviceCapabilityRegistry], чтобы слои, принимающие решения
 * (например, ToolPermissionManager), не зависели от Android Context и
 * покрывались обычными unit-тестами.
 */
interface CapabilityChecker {
    fun statusOf(capability: DeviceCapability): CapabilityStatus
    fun missingPermissions(permissions: List<String>): List<String>
}
