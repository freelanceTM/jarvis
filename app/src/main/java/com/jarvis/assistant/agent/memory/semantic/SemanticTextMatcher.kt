package com.jarvis.assistant.agent.memory.semantic

import com.jarvis.assistant.agent.discovery.SynonymDictionary
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Semantic Text Matcher — ЧЕСТНОЕ имя лексико-семантического матчера.
 *
 * Это НЕ ML-embedding модель. Здесь нет обученной нейросети
 * и никаких выученных представлений. Это ручной вектор:
 *
 *  1. фиксированные концептуальные подпространства (ручной словарь корней);
 *  2. проекция синонимов на те же координаты (SynonymDictionary);
 *  3. хеш-отпечаток слова + n-граммы (би-/триграммы) для устойчивости
 *     к русской морфологии и опечаткам;
 *  4. L2-нормализация для косинусного сходства.
 *
 * Такой вектор хорошо работает для Tool Discovery и грубого поиска по памяти,
 * но он не обладает свойствами настоящих embeddings (не переносит смысл на
 * незнакомые слова, не отражает контекст предложения).
 *
 * Настоящие embeddings подключаются отдельным слоем через
 * [com.jarvis.assistant.agent.memory.semantic.EmbeddingProvider]
 * (LocalEmbeddingProvider / RemoteEmbeddingProvider), не заменяя этот класс.
 *
 * Стоимость: < 0.5 мс, полностью офлайн, 0 МБ моделей.
 */
@Singleton
class SemanticTextMatcher @Inject constructor() {

    companion object {
        const val VECTOR_DIM = 128
        private const val CLUSTER_WEIGHT = 3.5f
        private const val WORD_WEIGHT = 1.2f
        private const val TRIGRAM_WEIGHT = 0.4f
        private const val BIGRAM_WEIGHT = 0.2f
    }

    // 30 семантических кластеров (концептов) с фиксированными подпространствами координат
    private val semanticClusters: Map<String, IntArray> = mapOf(
        "device_control" to intArrayOf(0, 1, 2),
        "light_flash" to intArrayOf(3, 4, 5),
        "sound_volume" to intArrayOf(6, 7, 8),
        "power_battery" to intArrayOf(9, 10, 11),
        "network_wifi_bt" to intArrayOf(12, 13, 14),
        "screen_display" to intArrayOf(15, 16, 17),
        "call_phone" to intArrayOf(18, 19, 20),
        "message_sms" to intArrayOf(21, 22, 23),
        "contact_person" to intArrayOf(24, 25, 26),
        "music_media" to intArrayOf(27, 28, 29),
        "play_start" to intArrayOf(30, 31),
        "pause_stop" to intArrayOf(32, 33),
        "next_skip" to intArrayOf(34, 35),
        "navigation_map" to intArrayOf(36, 37, 38),
        "location_place" to intArrayOf(39, 40),
        "time_clock" to intArrayOf(41, 42, 43),
        "alarm_timer" to intArrayOf(44, 45, 46),
        "calendar_event" to intArrayOf(47, 48),
        "memory_remember" to intArrayOf(49, 50, 51),
        "forget_delete" to intArrayOf(52, 53),
        "search_find" to intArrayOf(54, 55, 56),
        "turn_on_enable" to intArrayOf(57, 58),
        "turn_off_disable" to intArrayOf(59, 60),
        "app_launch" to intArrayOf(61, 62, 63)
    )

    // Маппинг нормализованных корней на семантические кластеры
    private val wordToCluster: Map<String, String> = buildMap {
        // Устройство
        putAll(listOf("устройств", "телефон", "смартфон", "девайс", "device", "phone").associateWith { "device_control" })
        // Свет / фонарик / лампочка
        putAll(listOf("фонар", "фонарик", "свет", "посвет", "свети", "вспышк", "лампа", "лампочк", "подсвет", "torch", "flash", "light", "lamp").associateWith { "light_flash" })
        // Звук / громкость
        putAll(listOf("громк", "звук", "тише", "громче", "потише", "погромче", "децибел", "volume", "sound", "audio", "mute", "loud", "quiet").associateWith { "sound_volume" })
        // Батарея / заряд
        putAll(listOf("батаре", "заряд", "аккумулят", "аккум", "процент", "энерг", "battery", "charge", "power", "level").associateWith { "power_battery" })
        // Сеть / Wi-Fi / Bluetooth
        putAll(listOf("wifi", "вайфай", "блютуз", "bluetooth", "интернет", "сеть", "bt", "network", "wireless", "connection").associateWith { "network_wifi_bt" })
        // Экран / скриншот
        putAll(listOf("экран", "яркост", "дисплей", "скриншот", "снимок", "screen", "display", "brightness", "screenshot").associateWith { "screen_display" })
        // Звонки / набор
        putAll(listOf("звон", "позвон", "вызов", "набер", "набир", "call", "dial", "phone").associateWith { "call_phone" })
        // Сообщения / SMS
        putAll(listOf("сообщен", "смс", "sms", "напиш", "отправ", "message", "text", "send", "msg").associateWith { "message_sms" })
        // Контакты / Семья
        putAll(listOf("контакт", "номер", "абонент", "мама", "маме", "папа", "папе", "брат", "сестр", "друг", "contact", "contacts").associateWith { "contact_person" })
        // Музыка / плеер
        putAll(listOf("музык", "трек", "песн", "плеер", "мелод", "music", "song", "track", "audio", "player").associateWith { "music_media" })
        // Play
        putAll(listOf("воспроизвед", "запуст", "включ", "play", "start").associateWith { "play_start" })
        // Pause
        putAll(listOf("пауз", "останов", "стоп", "pause", "stop", "hold").associateWith { "pause_stop" })
        // Next
        putAll(listOf("следующ", "дальш", "перемот", "скип", "next", "skip", "forward").associateWith { "next_skip" })
        // Навигация / Карты
        putAll(listOf("навигац", "маршрут", "карт", "дорог", "путь", "адрес", "ехать", "map", "route", "navigate", "drive").associateWith { "navigation_map" })
        // Локация
        putAll(listOf("локац", "место", "где", "location", "place", "where").associateWith { "location_place" })
        // Время / Дата
        putAll(listOf("врем", "час", "минут", "дат", "сегодн", "time", "clock", "date", "hour", "today").associateWith { "time_clock" })
        // Будильник / Таймер
        putAll(listOf("будильник", "таймер", "напомин", "напомн", "разбуд", "alarm", "timer", "remind").associateWith { "alarm_timer" })
        // Календарь
        putAll(listOf("календар", "событ", "встреч", "расписан", "calendar", "event", "meeting", "schedule").associateWith { "calendar_event" })
        // Память
        putAll(listOf("запомн", "помн", "сохран", "факт", "знай", "remember", "save", "memory", "fact").associateWith { "memory_remember" })
        // Забыть / Стереть
        putAll(listOf("забуд", "удал", "сотр", "очист", "forget", "delete", "erase", "clear").associateWith { "forget_delete" })
        // Поиск / Веб
        putAll(listOf("найд", "поиск", "искать", "погугл", "гугл", "search", "find", "google").associateWith { "search_find" })
        // Включение
        putAll(listOf("включ", "вруби", "зажг", "активир", "enable", "turn on", "on").associateWith { "turn_on_enable" })
        // Выключение
        putAll(listOf("выключ", "выруби", "погаси", "отключ", "disable", "turn off", "off").associateWith { "turn_off_disable" })
        // Приложения
        putAll(listOf("приложен", "программ", "app", "откр", "открой", "запуст", "launch", "open").associateWith { "app_launch" })
    }

    /**
     * Строит нормализованный лексико-семантический вектор размерности 128.
     *
     * Пункт аудита #15: выделяет новый массив на каждый вызов — для горячих
     * циклов используйте [featurizeInto] с переиспользуемым буфером.
     */
    fun featurize(text: String): FloatArray =
        featurizeInto(text, FloatArray(VECTOR_DIM))

    /**
     * Пункт аудита #15: заполняет ПЕРЕДАННЫЙ буфер (zero-аллокация при
     * переиспользовании) и возвращает его.
     *
     * ВАЖНО: результат валиден только пока буфер не переиспользован —
     * НЕ храните его между вызовами в цикле, если вызываете featurizeInto
     * повторно с тем же массивом.
     */
    fun featurizeInto(text: String, vector: FloatArray): FloatArray {
        vector.fill(0f)
        val normalized = text.lowercase().trim()
        if (normalized.isEmpty()) return vector

        val words = normalized.split(Regex("[\\s,?.!;:()\\[\\]{}\"']+")).filter { it.length >= 2 }
        val processedClusters = mutableSetOf<String>()

        for (word in words) {
            // 1. Поиск прямого семантического кластера
            var foundCluster: String? = null
            for ((stem, cluster) in wordToCluster) {
                if (word.startsWith(stem) || stem.startsWith(word.take(4))) {
                    foundCluster = cluster
                    break
                }
            }

            // 2. Поиск через SynonymDictionary
            if (foundCluster == null) {
                val synonyms = SynonymDictionary.getSynonyms(word)
                for (syn in synonyms) {
                    for ((stem, cluster) in wordToCluster) {
                        if (syn.startsWith(stem) || stem.startsWith(syn.take(4))) {
                            foundCluster = cluster
                            break
                        }
                    }
                    if (foundCluster != null) break
                }
            }

            // 3. Активация семантического подпространства координат
            if (foundCluster != null && foundCluster !in processedClusters) {
                processedClusters.add(foundCluster)
                val dims = semanticClusters[foundCluster] ?: continue
                for (dim in dims) {
                    if (dim < VECTOR_DIM) {
                        vector[dim] += CLUSTER_WEIGHT
                    }
                }
            }

            // 4. Лексический отпечаток слова (вторая половина вектора: 64-127)
            val wordHash = word.hashCode() and 0x7FFFFFFF
            val lexDim = 64 + (wordHash % 64)
            vector[lexDim] += WORD_WEIGHT

            // 5. N-граммы (триграммы и биграммы) для устойчивости к морфологии и опечаткам
            if (word.length >= 3) {
                for (i in 0 until word.length - 2) {
                    val trigram = word.substring(i, i + 3)
                    val triDim = 64 + ((trigram.hashCode() and 0x7FFFFFFF) % 64)
                    vector[triDim] += TRIGRAM_WEIGHT
                }
            }
            if (word.length >= 2) {
                for (i in 0 until word.length - 1) {
                    val bigram = word.substring(i, i + 2)
                    val biDim = 64 + ((bigram.hashCode() and 0x7FFFFFFF) % 64)
                    vector[biDim] += BIGRAM_WEIGHT
                }
            }
        }

        // 6. L2 Нормализация
        return normalizeL2(vector)
    }

    private fun normalizeL2(vector: FloatArray): FloatArray {
        var sumSquares = 0.0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares)
        if (norm > 0.0001f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }

    /**
     * Вычисляет косинусное сходство (Cosine Similarity) от 0.0 до 1.0
     */
    fun computeCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size || vecA.isEmpty()) return 0.0f

        var dotProduct = 0.0f
        for (i in vecA.indices) {
            dotProduct += vecA[i] * vecB[i]
        }

        return dotProduct.coerceIn(0.0f, 1.0f)
    }

    fun serializeVector(vector: FloatArray): String {
        return vector.joinToString(",") { "%.4f".format(it) }
    }

    fun deserializeVector(vectorStr: String): FloatArray {
        if (vectorStr.isBlank()) return FloatArray(VECTOR_DIM)
        return try {
            val parts = vectorStr.split(",")
            FloatArray(parts.size) { i -> parts[i].toFloat() }
        } catch (_: Exception) {
            FloatArray(VECTOR_DIM)
        }
    }
}
