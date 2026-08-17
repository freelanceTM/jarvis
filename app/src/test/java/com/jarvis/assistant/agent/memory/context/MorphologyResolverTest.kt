package com.jarvis.assistant.agent.memory.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты честного NLP-слоя анафоры v0.2:
 * морфология делегируется отдельному модулю, а не 500 regex.
 */
class MorphologyResolverTest {

    private val morphology = BasicMorphologyResolver()

    @Test
    fun `family terms dative forms`() {
        assertEquals("маме", morphology.dative("мама"))
        assertEquals("папе", morphology.dative("папа"))
        assertEquals("брату", morphology.dative("брат"))
        assertEquals("сестре", morphology.dative("сестра"))
        assertEquals("другу", morphology.dative("друг"))
    }

    @Test
    fun `female names dative rule`() {
        assertEquals("Анне", morphology.dative("Анна"))
        assertEquals("Ольге", morphology.dative("Ольга"))
        assertEquals("Наташе", morphology.dative("Наташа"))
    }

    @Test
    fun `iya exception maps to ii`() {
        assertEquals("Марии", morphology.dative("Мария"))
        assertEquals("Софии", morphology.dative("София"))
    }

    @Test
    fun `unknown words are returned unchanged honestly`() {
        assertEquals("Телеграм", morphology.dative("Телеграм"))
        assertEquals("ufc", morphology.dative("ufc"))
    }

    @Test
    fun `reverse nominative for family terms`() {
        assertEquals("мама", morphology.nominative("маме"))
        assertEquals("папа", morphology.nominative("папе"))
    }

    @Test
    fun `entity model maps slots to typed entities`() {
        assertTrue(ContextSlot.CONTACT.toEntity("мама") is PersonEntity)
        assertTrue(ContextSlot.APP.toEntity("Telegram") is ApplicationEntity)
        assertTrue(ContextSlot.LOCATION.toEntity("Берлин") is LocationEntity)
        assertTrue(ContextSlot.FILE.toEntity("документ") is ObjectEntity)
        assertTrue(ContextSlot.CONVERSATION.toEntity("Чат с Иваном") is ConversationEntity)

        val person = ContextSlot.CONTACT.toEntity("мама") as PersonEntity
        assertTrue(person.isContact)
        assertEquals(EntityType.PERSON, person.type)
    }

    @Test
    fun `entity model covers all five required types`() {
        val types = listOf(
            PersonEntity("Иван").type,
            ApplicationEntity("Telegram").type,
            LocationEntity("Берлин").type,
            ObjectEntity("файл").type,
            ConversationEntity("Чат", "Telegram").type
        )
        assertEquals(
            setOf(
                EntityType.PERSON,
                EntityType.APPLICATION,
                EntityType.LOCATION,
                EntityType.OBJECT,
                EntityType.CONVERSATION
            ),
            types.toSet()
        )
    }
}
