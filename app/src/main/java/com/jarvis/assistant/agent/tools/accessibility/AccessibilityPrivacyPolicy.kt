package com.jarvis.assistant.agent.tools.accessibility

/**
 * Privacy-конфигурация accessibility-границы (P1-2).
 *
 * Accessibility — самая привилегированная поверхность ассистента: без границы
 * он может читать ЛЮБОЙ экран (банки, пароли, 2FA) и вводить текст куда угодно.
 * Конфигурация задаёт политику по пакетам; сама логика решений — в
 * [AccessibilityPrivacyPolicy] (чистый класс, JVM-тестируемый).
 *
 * Хранение пользовательских переопределений — SharedPreferences
 * "jarvis_accessibility_privacy" (чтение должно быть синхронным из
 * AccessibilityService, который не участвует в Hilt-графе).
 *
 * @param mode          BLOCK_LIST (по умолчанию): всё разрешено, кроме
 *                      заблокированного; ALLOW_LIST: разрешено только
 *                      явно разрешённое (+ собственный пакет JARVIS).
 * @param blockedPackages пакеты, заблокированные пользователем вручную.
 * @param allowedPackages пакеты, разрешённые пользователем вручную
 *                        (перекрывают [AccessibilityPrivacyPolicy.DEFAULT_BLOCKED_PACKAGES]
 *                        и sensitive-эвристики, но НЕ перекрывают lock-screen).
 */
data class AccessibilityPrivacyConfig(
    val mode: Mode = Mode.BLOCK_LIST,
    val blockedPackages: Set<String> = emptySet(),
    val allowedPackages: Set<String> = emptySet()
) {
    enum class Mode { BLOCK_LIST, ALLOW_LIST }

    companion object {
        val DEFAULT = AccessibilityPrivacyConfig()
    }
}

/** Результат применения privacy-политики к пакету. */
sealed interface PolicyDecision {
    /** Доступ разрешён. */
    object Allowed : PolicyDecision

    /** Доступ запрещён: экран не читается, действия не выполняются. */
    data class Blocked(val reason: BlockedReason, val packageName: String?) : PolicyDecision
}

/**
 * Причины блокировки. Отражаются в результатах инструментов честно
 * (см. ScreenReaderTool / UiTypeTextTool / UiClickTool) и в audit-логе.
 */
enum class BlockedReason {
    /** Экран блокировки/системного UI — никогда не доступен. */
    SYSTEM_UI_LOCK_SCREEN,

    /** Пакет заблокирован чувствительной эвристикой (банк/пароль-менеджер/…). */
    SENSITIVE_CATEGORY,

    /** Пакет заблокирован пользователем явно. */
    USER_BLOCKED,

    /** Режим ALLOW_LIST: пакет отсутствует в allow-списке. */
    NOT_IN_ALLOW_LIST
}

/**
 * Чистая логика privacy-границы accessibility (без Android-зависимостей).
 *
 * Правила (в порядке приоритета):
 *  1. null/blank пакет → fail-closed (Blocked SYSTEM_UI_LOCK_SCREEN — «неизвестно»
 *     трактуем как максимально чувствительное);
 *  2. lock-screen/системный UI → заблокирован ВСЕГДА, пользовательским
 *     allow-листом не перекрывается (уведомления на локскрине содержат
 *     приватный контент);
 *  3. собственный пакет JARVIS → всегда разрешён (иначе ассистент не может
 *     работать со своим UI);
 *  4. ALLOW_LIST-режим: разрешено только явно разрешённое;
 *  5. BLOCK_LIST-режим: явный user-allow перекрывает и [sensitive-эвристики],
 *     и user-block; sensitive-эвристика и user-block запрещают.
 *
 * Решение детерминированное: одинаковый (config, package) → одинаковый ответ.
 */
class AccessibilityPrivacyPolicy(
    private val configProvider: () -> AccessibilityPrivacyConfig,
    private val ownPackage: String
) {
    fun decidePackage(rawPackageName: String?): PolicyDecision {
        val config = configProvider()
        val packageName = rawPackageName?.trim()?.lowercase().orEmpty()

        // 1. Fail-closed: неизвестный пакет трактуем как системный UI.
        if (packageName.isBlank()) {
            return PolicyDecision.Blocked(BlockedReason.SYSTEM_UI_LOCK_SCREEN, rawPackageName)
        }

        // 2. Lock-screen / системный UI — абсолютный запрет.
        if (packageName in NEVER_ACCESSIBLE_PACKAGES) {
            return PolicyDecision.Blocked(BlockedReason.SYSTEM_UI_LOCK_SCREEN, packageName)
        }

        // 3. Собственный UI ассистента.
        if (packageName == ownPackage.lowercase()) {
            return PolicyDecision.Allowed
        }

        val userAllowed = packageName in config.allowedPackages.map { it.trim().lowercase() }.toSet()
        if (config.mode == AccessibilityPrivacyConfig.Mode.ALLOW_LIST && !userAllowed) {
            return PolicyDecision.Blocked(BlockedReason.NOT_IN_ALLOW_LIST, packageName)
        }
        if (userAllowed) {
            // Явное пользовательское разрешение перекрывает эвристики и user-block
            // (но не пункты 1–2 выше).
            return PolicyDecision.Allowed
        }

        // 5а. Явный user-block.
        if (packageName in config.blockedPackages.map { it.trim().lowercase() }.toSet()) {
            return PolicyDecision.Blocked(BlockedReason.USER_BLOCKED, packageName)
        }

        // 5б. Sensitive-эвристика по имени пакета (банки, кошельки, пароль-менеджеры,
        // аутентификаторы). Имя пакета — слабый сигнал, поэтому это НЕ приговор:
        // пользователь может разрешить пакет явно.
        if (SENSITIVE_PACKAGE_HINTS.any { hint -> packageName.contains(hint) }) {
            return PolicyDecision.Blocked(BlockedReason.SENSITIVE_CATEGORY, packageName)
        }

        return PolicyDecision.Allowed
    }

    companion object {
        /**
         * Пакеты, которые НЕ доступны accessibility-инструментам ни при какой
         * пользовательской конфигурации:
         *  - systemui — lock screen, шторка уведомлений (приватный контент
         *    уведомлений, элементы управления устройством);
         *  - Settings — хранит доступы (Wi-Fi пароли, аккаунты);
         *  - Play Store / GMS — платёжные и аутентификационные диалоги Google.
         */
        val NEVER_ACCESSIBLE_PACKAGES: Set<String> = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.settings",
            "com.android.systemui.settings",
            "com.android.vending",
            "com.google.android.gms"
        )

        /**
         * Подстроки имени пакета, с высокой вероятностью указывающие на
         * чувствительное приложение. Управляется пользователем через
         * allow-лист (явное разрешение перекрывает эвристику).
         */
        val SENSITIVE_PACKAGE_HINTS: List<String> = listOf(
            // Универсальные слова.
            "bank", "wallet", "authenticator", "password",
            "2fa", "twofactor", "otp", "keystore", "vpn",
            // Пароль-менеджеры.
            "lastpass", "dashlane", "bitwarden", "1password", "aegis", "authy",
            // Финтех: слова, которых нет в «bank», но это банки/платёжки
            // (реальные сценарии; false positive лечится user allow-листом).
            "paypal", "coinbase", "binance", "revolut", "venmo", "cashapp",
            "cash.app", "alipay", "paisa", "spay", "steam",
            // Банки с брендовыми пакетами без слова «bank».
            // V03-STAB: «chase» добавлен по регресс-тесту
            // AccessibilityPrivacyPolicyTest (`com.chase.sig.android`) —
            // Chase — крупнейший банк США, пакет без слова «bank»; без хинта
            // full-screen capture банковского экрана уходил бы в LLM.
            "chase", "tinkoff", "sber", "privat24", "monobank", "yoomoney", "qiwi"
        )
    }
}
