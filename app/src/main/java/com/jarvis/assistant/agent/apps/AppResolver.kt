package com.jarvis.assistant.agent.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Результат разрешения пользовательской фразы в конкретное приложение.
 */
sealed interface AppResolution {
    data class Resolved(
        val packageName: String,
        val label: String,
        val matchedBy: MatchKind
    ) : AppResolution

    /** Приложение известно по алиасу, но на устройстве не установлено. */
    data class NotInstalled(val query: String, val knownPackage: String?) : AppResolution

    /** Несколько правдоподобных кандидатов — нужно уточнение у пользователя. */
    data class Ambiguous(val query: String, val candidates: List<Resolved>) : AppResolution

    data class Unknown(val query: String) : AppResolution

    enum class MatchKind { EXACT_PACKAGE, EXACT_LABEL, ALIAS, FUZZY }
}

/**
 * Разрешение «как пользователь назвал приложение» → package name.
 *
 * Поток: normalize → exact package → exact label → alias → fuzzy.
 * Fuzzy — именно fallback: он срабатывает последним и только при достаточном
 * сходстве, чтобы «открой ватсап» не открывало случайное приложение.
 */
@Singleton
class AppResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Разговорные алиасы, которых нет в названиях приложений.
     * Это не «бесконечный список пакетов», а именно словарь произношений:
     * реальный package всегда проверяется через PackageManager.
     */
    private val aliases: Map<String, List<String>> = mapOf(
        "telegram" to listOf("telegram", "телеграм", "телеграмм", "телега", "телегу", "телеге", "тг", "tg"),
        "youtube" to listOf("youtube", "ютуб", "ютюб", "ю туб", "ют"),
        "whatsapp" to listOf("whatsapp", "ватсап", "вацап", "воцап", "вотсап", "вассап"),
        "chrome" to listOf("chrome", "хром", "браузер", "гугл хром"),
        "spotify" to listOf("spotify", "спотифай", "спотик"),
        "instagram" to listOf("instagram", "инстаграм", "инста", "инстаграмм"),
        "viber" to listOf("viber", "вайбер"),
        "maps" to listOf("maps", "карты", "гугл карты", "навигатор"),
        "calculator" to listOf("calculator", "калькулятор", "кальк"),
        "camera" to listOf("camera", "камера", "камеру", "фотоаппарат"),
        "gallery" to listOf("gallery", "галерея", "галерею", "фото", "фотки"),
        "settings" to listOf("settings", "настройки", "настройка")
    )

    /** Известные пакеты для алиасов. Проверяются на установленность. */
    private val knownPackages: Map<String, List<String>> = mapOf(
        "telegram" to listOf("org.telegram.messenger", "org.thunderdog.challegram", "org.telegram.plus"),
        "youtube" to listOf("com.google.android.youtube", "com.google.android.apps.youtube.music"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "chrome" to listOf("com.android.chrome"),
        "spotify" to listOf("com.spotify.music"),
        "instagram" to listOf("com.instagram.android"),
        "viber" to listOf("com.viber.voip"),
        "maps" to listOf("com.google.android.apps.maps", "ru.yandex.yandexmaps"),
        "calculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
        "gallery" to listOf("com.google.android.apps.photos", "com.android.gallery3d")
    )

    /**
     * Системные действия, у которых нет обычного launch intent.
     * Возвращаются отдельно, чтобы Tool открыл их через Intent(action).
     */
    val systemActions: Map<String, String> = mapOf(
        "settings" to android.provider.Settings.ACTION_SETTINGS,
        "camera" to android.provider.MediaStore.ACTION_IMAGE_CAPTURE
    )

    fun resolve(rawQuery: String): AppResolution {
        val query = normalize(rawQuery)
        if (query.isEmpty()) return AppResolution.Unknown(rawQuery)

        val installed = installedApps()

        // 1. Точное совпадение по имени пакета.
        installed.firstOrNull { it.packageName.equals(query, ignoreCase = true) }?.let {
            return AppResolution.Resolved(it.packageName, it.label, AppResolution.MatchKind.EXACT_PACKAGE)
        }

        // 2. Точное совпадение по видимому названию приложения.
        installed.firstOrNull { normalize(it.label) == query }?.let {
            return AppResolution.Resolved(it.packageName, it.label, AppResolution.MatchKind.EXACT_LABEL)
        }

        // 3. Алиас: разговорное название -> канонический ключ -> установленный пакет.
        val aliasKey = aliases.entries.firstOrNull { (_, spellings) ->
            spellings.any { it == query }
        }?.key

        if (aliasKey != null) {
            knownPackages[aliasKey]?.forEach { pkg ->
                installed.firstOrNull { it.packageName == pkg }?.let {
                    return AppResolution.Resolved(it.packageName, it.label, AppResolution.MatchKind.ALIAS)
                }
            }
            // Алиас известен, но подходящее приложение по названию тоже стоит поискать.
            installed.firstOrNull { normalize(it.label).startsWith(aliasKey) }?.let {
                return AppResolution.Resolved(it.packageName, it.label, AppResolution.MatchKind.ALIAS)
            }
            if (systemActions.containsKey(aliasKey)) {
                return AppResolution.Resolved(aliasKey, aliasKey, AppResolution.MatchKind.ALIAS)
            }
            return AppResolution.NotInstalled(rawQuery, knownPackages[aliasKey]?.firstOrNull())
        }

        // 4. Fuzzy — только как последний резерв и только при высоком сходстве.
        val fuzzyCandidates = installed
            .map { it to similarity(query, normalize(it.label)) }
            .filter { it.second >= FUZZY_THRESHOLD }
            .sortedByDescending { it.second }

        if (fuzzyCandidates.isEmpty()) return AppResolution.Unknown(rawQuery)

        val best = fuzzyCandidates.first()
        val runnerUp = fuzzyCandidates.getOrNull(1)

        // Если два кандидата почти неразличимы — не угадываем, а спрашиваем.
        if (runnerUp != null && best.second - runnerUp.second < AMBIGUITY_MARGIN) {
            return AppResolution.Ambiguous(
                query = rawQuery,
                candidates = fuzzyCandidates.take(3).map {
                    AppResolution.Resolved(it.first.packageName, it.first.label, AppResolution.MatchKind.FUZZY)
                }
            )
        }

        return AppResolution.Resolved(best.first.packageName, best.first.label, AppResolution.MatchKind.FUZZY)
    }

    data class InstalledApp(val packageName: String, val label: String)

    /** Приложения, которые реально можно запустить (есть launch intent). */
    fun installedApps(): List<InstalledApp> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(launcherIntent, 0).mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(pm)?.toString() ?: pkg
                InstalledApp(pkg, label)
            }.distinctBy { it.packageName }
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).let { true }
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val FUZZY_THRESHOLD = 0.72
        private const val AMBIGUITY_MARGIN = 0.06

        fun normalize(value: String): String = value
            .lowercase()
            .trim()
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}. ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** Нормализованное сходство по расстоянию Левенштейна: 0.0 .. 1.0 */
        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            // Подстрока даёт высокий, но не абсолютный балл ("ютуб" в "YouTube Music")
            if (b.contains(a) || a.contains(b)) {
                return 0.85 + 0.1 * (min(a.length, b.length).toDouble() / max(a.length, b.length))
            }
            val distance = levenshtein(a, b)
            return 1.0 - distance.toDouble() / max(a.length, b.length)
        }

        fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val tmp = prev; prev = curr; curr = tmp
            }
            return prev[b.length]
        }
    }
}
