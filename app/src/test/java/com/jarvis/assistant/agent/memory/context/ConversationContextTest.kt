package com.jarvis.assistant.agent.memory.context

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Тесты структурированного контекста диалога.
 *
 * Ключевой сценарий из требований:
 *   «Открой Telegram» → lastApp = Telegram, lastContact = null
 *   «Напиши ему»      → JARVIS обязан спросить «Кому именно?», а не угадывать.
 */
class ConversationContextTest {

    private lateinit var resolver: ReferenceResolver

    @Before
    fun setUp() {
        resolver = ReferenceResolver()
    }

    @Test
    fun `write to him without any context asks for clarification`() {
        val context = ConversationContext()

        val resolution = resolver.resolve("напиши ему", context)

        assertTrue("Expected clarification, got $resolution", resolution is ReferenceResolution.NeedsClarification)
        assertEquals("Кому именно, сэр?", (resolution as ReferenceResolution.NeedsClarification).question)
    }

    @Test
    fun `write to him after opening telegram resolves to the app channel`() {
        // «Открой Telegram» → «Напиши ему сообщение»: без контакта адресат —
        // приложение-канал, в которое пишем.
        val context = ConversationContext(lastApp = "Telegram")

        val resolution = resolver.resolve("напиши ему сообщение", context)

        assertTrue("Expected resolution to Telegram, got $resolution", resolution is ReferenceResolution.Resolved)
        resolution as ReferenceResolution.Resolved
        assertEquals(ContextSlot.APP, resolution.slot)
        assertEquals("Telegram", resolution.value)
        assertTrue(resolution.rewrittenQuery.contains("в Telegram"))
    }

    @Test
    fun `write to her after calling mom resolves mom in dative case`() {
        // «Позвони маме» → lastContact=мама → «Напиши ей» → ей = маме.
        val context = ConversationContext(lastContact = "мама")

        val resolution = resolver.resolve("напиши ей", context)

        assertTrue("Expected resolution to мама, got $resolution", resolution is ReferenceResolution.Resolved)
        resolution as ReferenceResolution.Resolved
        assertEquals("мама", resolution.value)
        assertEquals(ContextSlot.CONTACT, resolution.slot)
        assertEquals("напиши маме", resolution.rewrittenQuery)
    }

    @Test
    fun `write to him resolves when contact is known`() {
        val context = ConversationContext(lastApp = "Telegram", lastContact = "Иван")

        val resolution = resolver.resolve("напиши ему", context)

        assertTrue(resolution is ReferenceResolution.Resolved)
        resolution as ReferenceResolution.Resolved
        assertEquals("Иван", resolution.value)
        assertEquals(ContextSlot.CONTACT, resolution.slot)
        assertTrue(resolution.rewrittenQuery.contains("Иван"))
    }

    @Test
    fun `subject pronoun resolves to last person`() {
        val context = ConversationContext(lastPerson = "Эмманюэль Макрон")

        val resolution = resolver.resolve("сколько ему лет", context)

        assertTrue(resolution is ReferenceResolution.Resolved)
        assertTrue((resolution as ReferenceResolution.Resolved).rewrittenQuery.contains("Эмманюэль Макрон"))
    }

    @Test
    fun `place pronoun resolves to last location`() {
        val context = ConversationContext(lastLocation = "Париж")

        val resolution = resolver.resolve("какая там погода", context)

        assertTrue(resolution is ReferenceResolution.Resolved)
        resolution as ReferenceResolution.Resolved
        assertEquals(ContextSlot.LOCATION, resolution.slot)
        assertTrue(resolution.rewrittenQuery.contains("Париж"))
    }

    @Test
    fun `place pronoun without location asks for clarification`() {
        val resolution = resolver.resolve("какая там погода", ConversationContext())
        assertTrue(resolution is ReferenceResolution.NeedsClarification)
    }

    @Test
    fun `query without pronouns needs no resolution`() {
        val context = ConversationContext(lastPerson = "Иван")
        assertEquals(ReferenceResolution.NoReference, resolver.resolve("включи фонарик", context))
    }

    @Test
    fun `detects reference kinds`() {
        assertEquals(ReferenceKind.ANIMATE_RECIPIENT, resolver.detectReference("напиши ему"))
        assertEquals(ReferenceKind.PLACE, resolver.detectReference("какая там погода"))
        assertEquals(ReferenceKind.SUBJECT, resolver.detectReference("где он живет"))
        assertNull(resolver.detectReference("включи музыку"))
    }

    @Test
    fun `context slots are independent`() {
        val context = ConversationContext()
            .with(ContextSlot.APP, "Telegram")
            .with(ContextSlot.LOCATION, "Берлин")

        assertEquals("Telegram", context.lastApp)
        assertEquals("Берлин", context.lastLocation)
        assertNull(context.lastContact)
    }

    @Test
    fun `setting contact also fills person when empty`() {
        val context = ConversationContext().with(ContextSlot.CONTACT, "Иван")
        assertEquals("Иван", context.lastContact)
        assertEquals("Иван", context.lastPerson)
    }

    @Test
    fun `blank value does not overwrite slot`() {
        val context = ConversationContext(lastApp = "Telegram").with(ContextSlot.APP, "   ")
        assertEquals("Telegram", context.lastApp)
    }

    @Test
    fun `summary lists known slots`() {
        val summary = ConversationContext(lastApp = "Telegram", lastContact = "Иван").summary()
        assertTrue(summary.contains("Telegram"))
        assertTrue(summary.contains("Иван"))
    }

    @Test
    fun `empty context has empty summary`() {
        assertEquals("", ConversationContext().summary())
        assertTrue(ConversationContext().isEmpty())
    }
}
