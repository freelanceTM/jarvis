package com.jarvis.assistant.agent.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт аудита #6 (HIGH): хеш-таблица сценариев вместо O(n) contains-цепочки.
 *
 * Поведение должно быть ИДЕНТИЧНО прежним if-условиям planForGoal:
 * - те же ключевые фразы матчат те же сценарии;
 * - приоритет = порядок исходных if (меньший номер выигрывает);
 * - комбинированные условия («утро»+«режим», «показ»+«режим»).
 */
class ScenarioMatcherTest {

    @Test
    fun `leaving home phrases match LEAVING_HOME`() {
        listOf("я ухожу", "выхожу из дома", "на выход", "ухожу на работу", "выхожу из офиса")
            .forEach { phrase ->
                assertEquals("'$phrase'", ScenarioId.LEAVING_HOME, ScenarioMatcher.match(phrase))
            }
    }

    @Test
    fun `coming home phrases match COMING_HOME`() {
        listOf("я пришел домой", "я дома", "вернулся домой", "пришёл домой", "дома уже")
            .forEach { phrase ->
                assertEquals("'$phrase'", ScenarioId.COMING_HOME, ScenarioMatcher.match(phrase))
            }
    }

    @Test
    fun `sleep phrases match SLEEP`() {
        listOf(
            "спокойной ночи", "ложусь спать", "включи ночной режим", "засыпаю",
            "пора спать", "подготовь телефон ко сну", "включи режим сна"
        )
            .forEach { phrase ->
                assertEquals("'$phrase'", ScenarioId.SLEEP, ScenarioMatcher.match(phrase))
            }
    }

    @Test
    fun `morning phrases match MORNING`() {
        listOf("доброе утро", "просыпаюсь", "утренний режим", "проснулся")
            .forEach { phrase ->
                assertEquals("'$phrase'", ScenarioId.MORNING, ScenarioMatcher.match(phrase))
            }
    }

    @Test
    fun `meeting phrases match MEETING`() {
        listOf("я на совещании", "на встрече", "митинг", "на собрании", "переговорная")
            .forEach { phrase ->
                assertEquals("'$phrase'", ScenarioId.MEETING, ScenarioMatcher.match(phrase))
            }
    }

    @Test
    fun `driving phrases match DRIVING`() {
        listOf("еду на машине", "в поездке", "за рулём", "за рулем", "в машине", "автомобиль")
            .forEach { phrase ->
                assertEquals("'$phrase'", ScenarioId.DRIVING, ScenarioMatcher.match(phrase))
            }
        assertNull(ScenarioMatcher.match("когда следующий матч сборной"))
        assertNull(ScenarioMatcher.match("проанализируй следующий договор"))
    }

    @Test
    fun `power saving phrases match POWER_SAVING`() {
        listOf("режим экономии", "батарея садится", "мало заряда", "экономь заряд", "сохрани заряд")
            .forEach { phrase ->
                assertEquals("'$phrase'", ScenarioId.POWER_SAVING, ScenarioMatcher.match(phrase))
            }
    }

    @Test
    fun `diagnostics phrases match DIAGNOSTICS`() {
        listOf(
            "статус системы", "статус телефона", "диагностика", "что с телефоном",
            "состояние системы", "системный отчёт", "проверь всё"
        ).forEach { phrase ->
            assertEquals("'$phrase'", ScenarioId.DIAGNOSTICS, ScenarioMatcher.match(phrase))
        }
    }

    @Test
    fun `generic report words do not trigger device diagnostics`() {
        listOf(
            "Сделай подробное резюме технического отчёта",
            "Проанализируй финансовую отчётность компании",
            "Оцени состояние европейского рынка",
            "Какой статус у нового законопроекта?"
        ).forEach { phrase ->
            assertNull("'$phrase'", ScenarioMatcher.match(phrase.lowercase()))
        }
    }

    @Test
    fun `presentation phrases match PRESENTATION`() {
        listOf("подготовь презентацию", "демо режим", "показ режим")
            .forEach { phrase ->
                assertEquals("'$phrase'", ScenarioId.PRESENTATION, ScenarioMatcher.match(phrase))
            }
    }

    @Test
    fun `reset phrases match RESET`() {
        listOf("отмени всё", "верни как было", "обычный режим", "стандартный режим", "сброс настроек")
            .forEach { phrase ->
                assertEquals("'$phrase'", ScenarioId.RESET, ScenarioMatcher.match(phrase))
            }
    }

    // ===========================================
    // Приоритет и комбинированные условия
    // ===========================================

    @Test
    fun `priority follows original if order - sleep beats morning`() {
        // «доброе утро» (MORNING, приоритет 4) + «хочу спать» (SLEEP, приоритет 3):
        // в оригинале if СНА шёл раньше УТРА — SLEEP выигрывает.
        assertEquals(ScenarioId.SLEEP, ScenarioMatcher.match("доброе утро, хочу спать"))
    }

    @Test
    fun `composite condition utro plus rezhim maps to MORNING`() {
        assertEquals(ScenarioId.MORNING, ScenarioMatcher.match("утро в режиме тишины"))
    }

    @Test
    fun `composite condition pokaz plus rezhim maps to PRESENTATION`() {
        assertEquals(ScenarioId.PRESENTATION, ScenarioMatcher.match("показ в режиме презентации"))
    }

    @Test
    fun `utro alone without rezhim does not match morning`() {
        // «утро» без «режим» — в оригинале не матчило сценарий 4.
        assertNull(ScenarioMatcher.match("светлое утро"))
    }

    @Test
    fun `unknown query returns null`() {
        listOf("объясни квантовую физику", "расскажи анекдот", "привет")
            .forEach { phrase ->
                assertNull("'$phrase'", ScenarioMatcher.match(phrase))
            }
    }

    @Test
    fun `keyword index is populated`() {
        assertTrue(ScenarioMatcher.keywordCount() >= 40)
    }
}
