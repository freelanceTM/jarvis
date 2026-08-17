package com.jarvis.assistant.agent.memory.context

/**
 * Морфологический модуль русского языка (NLP-слой анафоры).
 *
 * Полноценную морфологию русского языка мы сознательно НЕ изобретаем
 * набором regex-правил (это путь к 500 хрупким паттернам). Вместо этого
 * зафиксирован контракт [MorphologyResolver], который в будущем реализует
 * отдельный NLP-модуль (например, pymorphy2-подобный движок / библиотека
 * русской морфологии).
 *
 * В v0.2 работает честная минимальная реализация [BasicMorphologyResolver]:
 * словарь семейных терминов + простое правило для имён — она покрывает
 * типовые сценарии («мама» → «маме»), а всё, что не распознано, возвращается
 * без изменений (никакой «угаданной» морфологии).
 */
interface MorphologyResolver {

    /**
     * Именительный падеж → дательный («мама» → «маме», «Иван» → «Ивану»).
     * Используется при подстановке адресата вместо «ей/ему».
     */
    fun dative(nominative: String): String

    /**
     * Косвенная форма → именительная («маме» → «мама», «папе» → «папа»).
     * Полезно для поиска контакта по имени в исходной форме.
     */
    fun nominative(oblique: String): String
}

/**
 * Минимальная честная реализация морфологии v0.2.
 *
 * Словарь семейных/близких терминов + правило имён на -а/-я → -е
 * (с исключением -ия → -ии). Не распознанное возвращается как есть.
 */
class BasicMorphologyResolver : MorphologyResolver {

    // именительная → дательная для типовых обращений
    private val dativeOverrides = mapOf(
        "мама" to "маме", "папа" to "папе", "бабушка" to "бабушке",
        "дедушка" to "дедушке", "брат" to "брату", "сестра" to "сестре",
        "сын" to "сыну", "дочь" to "дочери", "жена" to "жене",
        "муж" to "мужу", "друг" to "другу", "подруга" to "подруге",
        "дядя" to "дяде", "тётя" to "тёте", "тетя" to "тете",
        "коллега" to "коллеге", "начальник" to "начальнику", "врач" to "врачу"
    )

    // дательная → именительная (обратный словарь)
    private val nominativeOverrides = dativeOverrides.entries
        .associate { (nom, dat) -> dat to nom }

    override fun dative(nominative: String): String {
        val n = nominative.trim()
        if (n.isEmpty()) return n

        val lower = n.lowercase()
        dativeOverrides[lower]?.let { mapped ->
            // сохраняем регистр первой буквы исходного слова
            return if (n[0].isUpperCase()) mapped.replaceFirstChar { it.uppercase() } else mapped
        }

        // Простое правило для имён: Анна→Анне, Ольга→Ольге, Наташа→Наташе.
        // Исключение: -ия → -ии (Мария→Марии, София→Софии).
        return when {
            lower.endsWith("ия") && lower.length > 3 -> n.dropLast(1) + "и"
            lower.endsWith("а") && lower.length > 2 -> n.dropLast(1) + "е"
            lower.endsWith("я") && lower.length > 2 -> n.dropLast(1) + "е"
            else -> n
        }
    }

    override fun nominative(oblique: String): String {
        val o = oblique.trim()
        if (o.isEmpty()) return o

        val lower = o.lowercase()
        nominativeOverrides[lower]?.let { mapped ->
            return if (o[0].isUpperCase()) mapped.replaceFirstChar { it.uppercase() } else mapped
        }

        // Обратное простое правило: «маме»→«мама» — только для явных
        // косвенных финалей -е/-и.
        return when {
            lower.endsWith("ии") && lower.length > 3 -> o.dropLast(2) + "я"
            lower.endsWith("е") && lower.length > 3 -> o.dropLast(1) + "а"
            else -> o
        }
    }
}
