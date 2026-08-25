package com.jarvis.assistant.voice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-02 regression: extractQuery должен вернуть null, если после вырезания
 * wake-word осталась пустая строка (только имя ассистента). Старое поведение
 * возвращало raw-строку и провоцировало двойную обработку.
 */
class WakeWordExtractorTest {

    @Test
    fun `Джарвис возвращает null`() {
        assertNull(WakeWordExtractor.extractQuery("Джарвис"))
    }

    @Test
    fun `Джарвис с пробелами возвращает null`() {
        assertNull(WakeWordExtractor.extractQuery("  Джарвис  "))
        assertNull(WakeWordExtractor.extractQuery("jarvis"))
        assertNull(WakeWordExtractor.extractQuery("Жарвис"))
        assertNull(WakeWordExtractor.extractQuery("джей"))
    }

    @Test
    fun `Джарвис с запятой и запросом вырезает wake-word`() {
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("Джарвис, сколько времени"))
    }

    @Test
    fun `Джарвис стоп вырезает wake-word`() {
        assertEquals("стоп", WakeWordExtractor.extractQuery("Джарвис стоп"))
    }

    @Test
    fun `запрос с wake-word посреди строки — containsWakeWord не даёт ложных срабатываний`() {
        // CR-02: containsWakeWord должен смотреть на НАЧАЛО строки (с точностью
        // до пунктуации/пробелов), иначе фразы вроде «привет, ...» или
        // слова, содержащие подстроку «жар»/«джар», дадут false-positive.
        assertFalse(WakeWordExtractor.containsWakeWord("привет Джарвис сколько времени"))
        assertFalse(WakeWordExtractor.containsWakeWord("какая погода сегодня жарко"))
        // extractQuery при этом по-прежнему вырезает wake-word если он есть в начале.
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("привет Джарвис сколько времени"))
    }

    @Test
    fun `пунктуация после и перед wake-word обрезается`() {
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("Джарвис, сколько времени"))
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("jarvis! сколько времени"))
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("Джарвис — сколько времени"))
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("...Джарвис, сколько времени"))
    }

    @Test
    fun `пустая и blank строка возвращает null`() {
        assertNull(WakeWordExtractor.extractQuery(""))
        assertNull(WakeWordExtractor.extractQuery("   "))
        assertNull(WakeWordExtractor.extractQuery("   !!!,,,   "))
    }

    @Test
    fun `строка без wake-word возвращается как есть, с пробелами по бокам`() {
        assertEquals("какая погода", WakeWordExtractor.extractQuery("  какая погода  "))
    }

    @Test
    fun `containsWakeWord детектирует все стандартные варианты в начале строки`() {
        listOf("Джарвис", "jarvis", "Жарвис", "Дарвис", "Джей", "диджей", "джар",
               "  Джарвис", "...Джарвис", "Джарвис,", "эй джарвис").forEach {
            assertTrue("containsWakeWord должен распознать '$it'", WakeWordExtractor.containsWakeWord(it))
        }
        assertFalse(WakeWordExtractor.containsWakeWord("какая погода"))
        assertFalse(WakeWordExtractor.containsWakeWord("сегодня жарко"))
    }
}
