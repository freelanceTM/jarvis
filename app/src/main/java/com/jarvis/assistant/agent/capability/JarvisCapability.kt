package com.jarvis.assistant.agent.capability

/**
 * Android Capability Layer — иерархический контракт возможностей устройства.
 *
 * Каждая группа (например, `device.bluetooth`) — это набор листовых проверок
 * [DeviceCapability], которые реально выполняет [DeviceCapabilityRegistry]
 * (API-level, hardware, permission model). Группа отвечает на вопрос
 * «может ли JARVIS вообще что-то сделать в этом домене на данном устройстве»,
 * а лист — «может ли JARVIS выполнить конкретное действие».
 *
 * ```
 * JarvisCapability
 *  ├── device.bluetooth     → status, toggle (только до Android 12), настройки
 *  ├── device.wifi          → status, toggle (USER_ACTION_REQUIRED), панель
 *  ├── device.brightness    → read, write (WRITE_SETTINGS), настройки экрана
 *  ├── device.screenshot    → AccessibilityService (API 30+), MediaProjection
 *  ├── device.apps          → запуск установленных приложений
 *  ├── communication.sms    → прямая отправка, композер
 *  ├── communication.call   → прямой вызов, номеронабиратель
 *  ├── media                → управление воспроизведением
 *  ├── accessibility        → служба специальных возможностей JARVIS
 *  └── location             → координаты устройства (LocationProvider)
 * ```
 *
 * Слой расширяемый: новые домены добавляются новым объектом-группой и
 * листовыми проверками в [DeviceCapability] + [DeviceCapabilityRegistry].
 */
sealed interface JarvisCapability {

    /** Стабильный идентификатор в dot-нотации (совпадает с namespace tool-ов). */
    val id: String

    /** Человекочитаемое описание группы. */
    val description: String

    /** Листовые возможности, из которых состоит группа. */
    val leaves: Set<DeviceCapability>

    data object Bluetooth : JarvisCapability {
        override val id: String = "device.bluetooth"
        override val description: String = "Чтение состояния, переключение (до Android 12) и системный экран Bluetooth"
        override val leaves: Set<DeviceCapability> = setOf(
            DeviceCapability.READ_BLUETOOTH_STATE,
            DeviceCapability.TOGGLE_BLUETOOTH_DIRECTLY,
            DeviceCapability.OPEN_BLUETOOTH_SETTINGS
        )
    }

    data object Wifi : JarvisCapability {
        override val id: String = "device.wifi"
        override val description: String = "Чтение состояния Wi-Fi и открытие системной панели"
        override val leaves: Set<DeviceCapability> = setOf(
            DeviceCapability.READ_WIFI_STATE,
            DeviceCapability.TOGGLE_WIFI_DIRECTLY,
            DeviceCapability.OPEN_WIFI_SETTINGS
        )
    }

    data object Brightness : JarvisCapability {
        override val id: String = "device.brightness"
        override val description: String = "Чтение и изменение системной яркости (WRITE_SETTINGS)"
        override val leaves: Set<DeviceCapability> = setOf(
            DeviceCapability.READ_BRIGHTNESS,
            DeviceCapability.WRITE_BRIGHTNESS,
            DeviceCapability.OPEN_DISPLAY_SETTINGS
        )
    }

    data object Screenshot : JarvisCapability {
        override val id: String = "device.screenshot"
        override val description: String = "Скриншот через AccessibilityService (API 30+) или MediaProjection"
        override val leaves: Set<DeviceCapability> = setOf(
            DeviceCapability.TAKE_SCREENSHOT_ACCESSIBILITY,
            DeviceCapability.TAKE_SCREENSHOT_MEDIA_PROJECTION
        )
    }

    data object Apps : JarvisCapability {
        override val id: String = "device.apps"
        override val description: String = "Запуск установленных приложений по названию"
        override val leaves: Set<DeviceCapability> = setOf(
            DeviceCapability.OPEN_APP
        )
    }

    data object Sms : JarvisCapability {
        override val id: String = "communication.sms"
        override val description: String = "Отправка SMS (SEND_SMS) и открытие композера"
        override val leaves: Set<DeviceCapability> = setOf(
            DeviceCapability.SEND_SMS_DIRECTLY,
            DeviceCapability.OPEN_SMS_COMPOSER
        )
    }

    data object Call : JarvisCapability {
        override val id: String = "communication.call"
        override val description: String = "Прямой вызов (CALL_PHONE) и открытие номеронабирателя"
        override val leaves: Set<DeviceCapability> = setOf(
            DeviceCapability.PLACE_CALL_DIRECTLY,
            DeviceCapability.OPEN_DIALER
        )
    }

    data object Media : JarvisCapability {
        override val id: String = "media"
        override val description: String = "Управление воспроизведением музыки и видео"
        override val leaves: Set<DeviceCapability> = setOf(
            DeviceCapability.CONTROL_MEDIA
        )
    }

    data object Accessibility : JarvisCapability {
        override val id: String = "accessibility"
        override val description: String = "Служба специальных возможностей JARVIS (чтение экрана, UI-клики)"
        override val leaves: Set<DeviceCapability> = setOf(
            DeviceCapability.USE_ACCESSIBILITY_SERVICE
        )
    }

    data object Location : JarvisCapability {
        override val id: String = "location"
        override val description: String = "Определение текущего местоположения устройства"
        override val leaves: Set<DeviceCapability> = setOf(
            DeviceCapability.READ_LOCATION
        )
    }

    companion object {
        /** Все группы слоя в фиксированном порядке (порядок документации). */
        val all: List<JarvisCapability> = listOf(
            Bluetooth, Wifi, Brightness, Screenshot, Apps,
            Sms, Call, Media, Accessibility, Location
        )

        /** Разрешение группы по dot-идентификатору. */
        fun byId(id: String): JarvisCapability? = all.firstOrNull { it.id == id }
    }
}

/**
 * Агрегирует статусы листовых проверок в статус группы [JarvisCapability].
 *
 * Семантика «лучший доступный путь»:
 *  1. есть хотя бы один рабочий путь        → Available;
 *  2. иначе — блокер, который можно устранить:
 *     PERMISSION_REQUIRED (разрешения объединяются) → USER_ACTION_REQUIRED → UNSUPPORTED.
 *
 * Используется [DeviceCapabilityRegistry] и тестовым [FakeCapabilityRegistry],
 * чтобы поведение слоя в тестах совпадало с продакшеном.
 */
fun aggregateCapabilityStatus(statuses: List<CapabilityStatus>): CapabilityStatus = when {
    statuses.isEmpty() -> CapabilityStatus.Unsupported("Группа не содержит реализованных возможностей")
    else -> statuses.firstOrNull { it is CapabilityStatus.Available }
        ?: aggregateMissingPermissions(statuses)
        ?: statuses.firstOrNull { it is CapabilityStatus.UserActionRequired }
        ?: statuses.first()
}

private fun aggregateMissingPermissions(statuses: List<CapabilityStatus>): CapabilityStatus.PermissionRequired? {
    val permissions = statuses
        .filterIsInstance<CapabilityStatus.PermissionRequired>()
        .flatMap { it.permissions }
        .distinct()
    return if (permissions.isNotEmpty()) {
        CapabilityStatus.PermissionRequired(permissions)
    } else {
        null
    }
}
