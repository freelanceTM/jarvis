package com.jarvis.assistant.agent.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.agent.memory.entity.SemanticMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SemanticMemoryDao {

    @Query("SELECT * FROM semantic_memory ORDER BY updated_at DESC")
    fun getAllMemoriesStream(): Flow<List<SemanticMemoryEntity>>

    @Query("SELECT * FROM semantic_memory WHERE key_name = :key LIMIT 1")
    suspend fun getMemoryByKey(key: String): SemanticMemoryEntity?

    @Query("SELECT * FROM semantic_memory WHERE key_name LIKE '%' || :query || '%' OR value_text LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchMemories(query: String, limit: Int = 5): List<SemanticMemoryEntity>

    @Query("SELECT * FROM semantic_memory ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int = 10): List<SemanticMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMemory(memory: SemanticMemoryEntity): Long

    @Query("DELETE FROM semantic_memory WHERE key_name = :key")
    suspend fun deleteMemory(key: String)
}
