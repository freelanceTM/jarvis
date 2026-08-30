package com.jarvis.assistant.agent.tools.accessibility

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пользовательское управление accessibility privacy-границей (P1-2).
 *
 * Хранит режим (block-list/allow-list), пользовательские block/allow-списки
 * пакетов в SharedPreferences "jarvis_accessibility_privacy" — тот же файл,
 * который синхронно читает [JarvisAccessibilityService] (сервис вне Hilt-графа,
 * поэтому общий файл, а не DataStore-поток).
 *
 * Явные разрешения пользователя перекрывают sensitive-эвристики, но НЕ
 * перекрывают абсолютный запрет lock-screen/системного UI
 * (см. [AccessibilityPrivacyPolicy.NEVER_ACCESSIBLE_PACKAGES]).
 */
@Singleton
class AccessibilityPrivacyStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun current(ownPackage: String): AccessibilityPrivacyConfig = AccessibilityPrivacyConfig(
        mode = if (prefs.getBoolean(KEY_ALLOW_LIST_MODE, false)) {
            AccessibilityPrivacyConfig.Mode.ALLOW_LIST
        } else {
            AccessibilityPrivacyConfig.Mode.BLOCK_LIST
        },
        blockedPackages = readSet(KEY_BLOCKED),
        allowedPackages = readSet(KEY_ALLOWED) + ownPackage
    )

    fun isAllowListMode(): Boolean = prefs.getBoolean(KEY_ALLOW_LIST_MODE, false)

    /** true = режим allow-листа; false = режим block-листа (по умолчанию). */
    fun setAllowListMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_LIST_MODE, enabled).apply()
    }

    fun blockedPackages(): Set<String> = readSet(KEY_BLOCKED)

    fun blockedSensitiveHints(): List<String> =
        AccessibilityPrivacyPolicy.SENSITIVE_PACKAGE_HINTS

    /** Разрешённые пользователем пакеты (перекрывают sensitive-эвристику). */
    fun allowedPackages(): Set<String> = readSet(KEY_ALLOWED)

    fun allowPackage(packageName: String) {
        val normalized = packageName.trim().lowercase()
        if (normalized.isEmpty()) return
        if (normalized in AccessibilityPrivacyPolicy.NEVER_ACCESSIBLE_PACKAGES) {
            // Локскрин/системный UI не разрешается даже явно — защита от
            // социальной инженерии («разреши мне прочитать локскрин»).
            return
        }
        prefs.edit().putStringSet(KEY_ALLOWED, readSet(KEY_ALLOWED) + normalized).apply()
    }

    fun revokeAllowance(packageName: String) {
        val normalized = packageName.trim().lowercase()
        prefs.edit().putStringSet(KEY_ALLOWED, readSet(KEY_ALLOWED) - normalized).apply()
    }

    fun blockPackage(packageName: String) {
        val normalized = packageName.trim().lowercase()
        if (normalized.isEmpty()) return
        prefs.edit()
            .putStringSet(KEY_BLOCKED, readSet(KEY_BLOCKED) + normalized)
            .putStringSet(KEY_ALLOWED, readSet(KEY_ALLOWED) - normalized)
            .apply()
    }

    fun unblockPackage(packageName: String) {
        val normalized = packageName.trim().lowercase()
        prefs.edit().putStringSet(KEY_BLOCKED, readSet(KEY_BLOCKED) - normalized).apply()
    }

    private fun readSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet())?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
            ?: emptySet()

    companion object {
        const val PREFS_NAME = "jarvis_accessibility_privacy"
        private const val KEY_ALLOW_LIST_MODE = "mode"
        private const val KEY_BLOCKED = "blocked_packages"
        private const val KEY_ALLOWED = "allowed_packages"
    }
}
