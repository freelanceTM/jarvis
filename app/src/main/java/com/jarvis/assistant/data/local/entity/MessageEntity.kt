package com.jarvis.assistant.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole

@Entity(
    tableName = "messages",
    indices = [Index(value = ["timestamp"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)

fun MessageEntity.toDomain(): Message {
    return Message(
        id = id,
        role = MessageRole.fromString(role),
        text = text,
        timestamp = timestamp
    )
}

fun Message.toEntity(): MessageEntity {
    return MessageEntity(
        id = id,
        role = role.value,
        text = text,
        timestamp = timestamp
    )
}
