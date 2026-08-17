package com.jarvis.assistant.agent.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Быстрые режимы синхронного переводчика (v0.2):
 * AUTO / RU→TM / TM→RU / EN→RU / RU→EN.
 */
class InterpreterPresetTest {

    @Test
    fun `presets cover the required modes`() {
        assertEquals(
            listOf("auto", "ru_tm", "tm_ru", "en_ru", "ru_en"),
            InterpreterPreset.all.map { it.id }
        )
    }

    @Test
    fun `quick presets map to the required language pairs`() {
        assertEquals("auto" to "ru", InterpreterPreset.AUTO.sourceCode to InterpreterPreset.AUTO.targetCode)
        assertEquals("ru" to "tk", InterpreterPreset.RU_TM.sourceCode to InterpreterPreset.RU_TM.targetCode)
        assertEquals("tk" to "ru", InterpreterPreset.TM_RU.sourceCode to InterpreterPreset.TM_RU.targetCode)
        assertEquals("en" to "ru", InterpreterPreset.EN_RU.sourceCode to InterpreterPreset.EN_RU.targetCode)
        assertEquals("ru" to "en", InterpreterPreset.RU_EN.sourceCode to InterpreterPreset.RU_EN.targetCode)
    }

    @Test
    fun `custom manual pair is excluded from quick presets`() {
        assertTrue(InterpreterPreset.all.none { it.id == "custom" })
        assertTrue(InterpreterPreset.allIncludingCustom.any { it.id == "custom" })
    }

    @Test
    fun `byId resolves presets and unknown ids return null`() {
        assertEquals(InterpreterPreset.RU_TM, InterpreterPreset.byId("ru_tm"))
        assertEquals(InterpreterPreset.CUSTOM, InterpreterPreset.byId("custom"))
        assertNull(InterpreterPreset.byId("fr_de"))
        assertNull(InterpreterPreset.byId(""))
    }

    @Test
    fun `every quick preset target is a supported translation language`() {
        InterpreterPreset.all.forEach { preset ->
            assertTrue(
                "Target '${preset.targetCode}' должен быть в TranslationLanguages.SUPPORTED",
                TranslationLanguages.isSupported(preset.targetCode)
            )
        }
    }
}
