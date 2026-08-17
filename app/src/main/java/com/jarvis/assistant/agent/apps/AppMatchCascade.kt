package com.jarvis.assistant.agent.apps

import com.jarvis.assistant.agent.memory.semantic.SemanticTextMatcher
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

    enum class MatchKind {
        EXACT_PACKAGE,
        EXACT_LABEL,
        NORMALIZED,
        ALIAS,
        FUZZY,
        SEMANTIC
    }
}

/**
 * Чистый каскад разрешения «фраза → приложение» (без Android).
 *
 *   exact match      → точное имя пакета / точное название приложения
 *   normalized match → «компактные» нормализованные формы («You Tube» → «youtube»)
 *   alias match      → разговорные алиасы + ласкательные префиксы («ютубчик»)
 *   fuzzy match      → нечёткое сходство (Левенштейн/подстрока, порог 0.72)
 *   semantic match   → лексико-семантическое сходство (SemanticTextMatcher)
 *
 * ВАЖНО: каскад работает ТОЛЬКО по списку установленных приложений —
 * несуществующее приложение никогда не «открывается».
 */
class AppMatchCascade(
    private val semanticMatcher: SemanticTextMatcher
) {

    /**
     * Разговорные алиасы, которых нет в названиях приложений.
     * Это не «бесконечный список пакетов», а именно словарь произношений:
     * реальный package всегда проверяется через PackageManager.
     */
    val aliases: Map<String, List<String>> = mapOf(
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
        "settings" to listOf("settings", "настройки", "настройка"),
        "calendar" to listOf("calendar", "календарь", "календарю")
    )

    /** Известные пакеты для алиасов. Проверяются на установленность. */
    val knownPackages: Map<String, List<String>> = mapOf(
        "telegram" to listOf("org.telegram.messenger", "org.thunderdog.challegram", "org.telegram.plus"),
        "youtube" to listOf("com.google.android.youtube", "com.google.android.apps.youtube.music"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "chrome" to listOf("com.android.chrome"),
        "spotify" to listOf("com.spotify.music"),
        "instagram" to listOf("com.instagram.android"),
        "viber" to listOf("com.viber.voip"),
        "maps" to listOf("com.google.android.apps.maps", "ru.yandex.yandexmaps"),
        "calculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
        "gallery" to listOf("com.google.android.apps.photos", "com.android.gallery3d"),
        "calendar" to listOf("com.google.android.calendar", "com.android.calendar")
    )

    /**
     * Системные действия, у которых нет обычного launch intent.
     * Возвращаются отдельно, чтобы Tool открыл их через Intent(action).
     */
    val systemActions: Map<String, String> = mapOf(
        "settings" to android.provider.Settings.ACTION_SETTINGS,
        "camera" to android.provider.MediaStore.ACTION_IMAGE_CAPTURE
    )

    fun resolve(installed: List<InstalledApp>, rawQuery: String): AppResolution {
        val query = normalize(rawQuery)
        if (query.isEmpty()) return AppResolution.Unknown(rawQuery)

        // ------------------------------------------------------------------ 1. EXACT
        // Точное совпадение по имени пакета.
        installed.firstOrNull { it.packageName.equals(query, ignoreCase = true) }?.let {
            return AppResolution.Resolved(it.packageName, it.label, AppResolution.MatchKind.EXACT_PACKAGE)
        }

        // Точное совпадение по видимому названию приложения.
        installed.firstOrNull { it.label.equals(rawQuery.trim(), ignoreCase = true) }?.let {
            return AppResolution.Resolved(it.packageName, it.label, AppResolution.MatchKind.EXACT_LABEL)
        }

        // ------------------------------------------------------------------ 2. NORMALIZED
        // «Компактные» нормализованные формы (без пробелов/пунктуации):
        // «You Tube» → «youtube», «Телеграм!» → «телеграм».
        val compactQuery = compact(query)
        installed.firstOrNull { compact(it.label) == compactQuery }?.let {
            return AppResolution.Resolved(it.packageName, it.label, AppResolution.MatchKind.NORMALIZED)
        }

        // ------------------------------------------------------------------ 3. ALIAS
        // Точное совпадение алиаса или ласкательная форма-префикс («ютубчик»).
        val aliasKey = aliases.entries.firstOrNull { (_, spellings) ->
            spellings.any { it == query } || spellings.any { alias ->
                query.startsWith(alias) && query.length - alias.length in 1..3
            }
        }?.key

        if (aliasKey != null) {
            knownPackages[aliasKey]?.forEach { pkg ->
                installed.firstOrNull { it.packageName == pkg }?.let {
                    return AppResolution.Resolved(it.packageName, it.label, AppResolution.MatchKind.ALIAS)
                }
            }
            installed.firstOrNull { compact(it.label) == compact(aliasKey) }?.let {
                return AppResolution.Resolved(it.packageName, it.label, AppResolution.MatchKind.ALIAS)
            }
            if (systemActions.containsKey(aliasKey)) {
                return AppResolution.Resolved(aliasKey, aliasKey, AppResolution.MatchKind.ALIAS)
            }
            return AppResolution.NotInstalled(rawQuery, knownPackages[aliasKey]?.firstOrNull())
        }

        // ------------------------------------------------------------------ 4. FUZZY
        val fuzzyCandidates = installed
            .map { it to similarity(query, normalize(it.label)) }
            .filter { it.second >= FUZZY_THRESHOLD }
            .sortedByDescending { it.second }

        if (fuzzyCandidates.isEmpty()) return resolveSemantic(installed, query, rawQuery)

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

    /**
     * ------------------------------------------------------------------ 5. SEMANTIC
     * Последний резерв: лексико-семантическое сходство запроса с названиями
     * установленных приложений (SemanticTextMatcher). Не сработало — Unknown.
     */
    private fun resolveSemantic(
        installed: List<InstalledApp>,
        query: String,
        rawQuery: String
    ): AppResolution {
        val candidates = installed
            .map { app ->
                app to semanticMatcher.computeCosineSimilarity(
                    semanticMatcher.featurize(query),
                    semanticMatcher.featurize(normalize(app.label))
                )
            }
            .filter { it.second >= SEMANTIC_THRESHOLD }
            .sortedByDescending { it.second }

        val best = candidates.firstOrNull() ?: return AppResolution.Unknown(rawQuery)
        return AppResolution.Resolved(best.first.packageName, best.first.label, AppResolution.MatchKind.SEMANTIC)
    }

    companion object {
        private const val FUZZY_THRESHOLD = 0.72
        private const val AMBIGUITY_MARGIN = 0.06
        private const val SEMANTIC_THRESHOLD = 0.5f

        fun normalize(value: String): String = value
            .lowercase()
            .trim()
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}. ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** «Компактная» форма: только буквы/цифры/точки, без пробелов. */
        fun compact(value: String): String =
            normalize(value).filter { it.isLetterOrDigit() || it == '.' }

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

/** Установленное приложение (пакет + видимое название). */
data class InstalledApp(val packageName: String, val label: String)
