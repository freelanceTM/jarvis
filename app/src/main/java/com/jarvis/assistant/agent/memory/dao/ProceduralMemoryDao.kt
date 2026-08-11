package com.jarvis.assistant.agent.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.agent.memory.entity.ProceduralMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProceduralMemoryDao {

    @Query("SELECT * FROM procedural_memory")
    fun getAllWorkflows(): Flow<List<ProceduralMemoryEntity>>

    @Query("SELECT * FROM procedural_memory WHERE trigger_phrase = :trigger LIMIT 1")
    suspend fun getWorkflowByTrigger(trigger: String): ProceduralMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWorkflow(workflow: ProceduralMemoryEntity): Long

    @Query("DELETE FROM procedural_memory WHERE trigger_phrase = :trigger")
    suspend fun deleteWorkflow(trigger: String)
}
