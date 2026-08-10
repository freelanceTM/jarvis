package com.jarvis.assistant.data.repository

import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import com.jarvis.assistant.data.local.dao.MessageDao
import com.jarvis.assistant.data.local.entity.toDomain
import com.jarvis.assistant.data.local.entity.toEntity
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val dispatchers: CoroutineDispatchers
) : MessageRepository {

    override fun getMessagesStream(): Flow<List<Message>> {
        return messageDao.getAllMessagesStream()
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override suspend fun getRecentMessages(limit: Int): List<Message> {
        return withContext(dispatchers.io) {
            messageDao.getRecentMessages(limit).reversed().map { it.toDomain() }
        }
    }

    override suspend fun getLastMessage(): Message? {
        return withContext(dispatchers.io) {
            messageDao.getLastMessage()?.toDomain()
        }
    }

    override suspend fun insertMessage(message: Message): Long {
        return withContext(dispatchers.io) {
            messageDao.insertMessage(message.toEntity())
        }
    }

    override suspend fun clearHistory() {
        withContext(dispatchers.io) {
            messageDao.clearAllMessages()
        }
    }

    override suspend fun deleteMessageById(id: Long) {
        withContext(dispatchers.io) {
            messageDao.deleteMessageById(id)
        }
    }
}
