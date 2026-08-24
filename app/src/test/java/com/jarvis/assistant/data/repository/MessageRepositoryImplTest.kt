package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.local.dao.MessageDao
import com.jarvis.assistant.data.local.entity.MessageEntity
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.testing.ImmediateTestDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageRepositoryImplTest {
    @Test
    fun `stream maps persisted entities to domain messages`() = runTest {
        val dao = FakeMessageDao()
        val repository = MessageRepositoryImpl(dao, ImmediateTestDispatchers())
        dao.messages.value = listOf(
            MessageEntity(1, "USER", "hello", 10),
            MessageEntity(2, "assistant", "hi", 20)
        )

        val result = repository.getMessagesStream().first()

        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), result.map(Message::role))
        assertEquals(listOf("hello", "hi"), result.map(Message::text))
    }

    @Test
    fun `recent messages reverse DAO newest-first result into conversation order`() = runTest {
        val dao = FakeMessageDao().apply {
            recent = listOf(
                MessageEntity(3, "assistant", "third", 30),
                MessageEntity(2, "user", "second", 20)
            )
        }
        val repository = MessageRepositoryImpl(dao, ImmediateTestDispatchers())

        val result = repository.getRecentMessages(2)

        assertEquals(2, dao.lastLimit)
        assertEquals(listOf("second", "third"), result.map(Message::text))
    }

    @Test
    fun `insert clear and delete delegate exact production values`() = runTest {
        val dao = FakeMessageDao().apply { nextInsertId = 42 }
        val repository = MessageRepositoryImpl(dao, ImmediateTestDispatchers())
        val message = Message(7, MessageRole.SYSTEM, "system", 123)

        assertEquals(42L, repository.insertMessage(message))
        repository.deleteMessageById(7)
        repository.clearHistory()

        assertEquals(MessageEntity(7, "system", "system", 123), dao.inserted)
        assertEquals(7L, dao.deletedId)
        assertEquals(1, dao.clearCalls)
    }

    @Test
    fun `missing last message remains null and DAO failures propagate`() = runTest {
        val dao = FakeMessageDao()
        val repository = MessageRepositoryImpl(dao, ImmediateTestDispatchers())
        assertNull(repository.getLastMessage())

        dao.failure = IllegalStateException("database unavailable")
        var thrown: IllegalStateException? = null
        try {
            repository.getRecentMessages(10)
        } catch (failure: IllegalStateException) {
            thrown = failure
        }
        assertEquals("database unavailable", thrown?.message)
    }

    private class FakeMessageDao : MessageDao {
        val messages = MutableStateFlow<List<MessageEntity>>(emptyList())
        var recent: List<MessageEntity> = emptyList()
        var last: MessageEntity? = null
        var inserted: MessageEntity? = null
        var nextInsertId: Long = 1
        var deletedId: Long? = null
        var clearCalls = 0
        var lastLimit: Int? = null
        var failure: RuntimeException? = null

        override fun getAllMessagesStream(): Flow<List<MessageEntity>> = messages
        override suspend fun getRecentMessages(limit: Int): List<MessageEntity> {
            failure?.let { throw it }
            lastLimit = limit
            return recent
        }
        override suspend fun getLastMessage(): MessageEntity? = last
        override suspend fun insertMessage(message: MessageEntity): Long {
            inserted = message
            return nextInsertId
        }
        override suspend fun clearAllMessages() {
            clearCalls++
        }
        override suspend fun deleteMessageById(id: Long) {
            deletedId = id
        }
    }
}
