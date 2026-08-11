package com.jarvis.assistant.agent.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.agent.memory.entity.ProcedureEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcedureDao {

    @Query("SELECT * FROM procedures ORDER BY execution_count DESC")
    fun getAllProceduresStream(): Flow<List<ProcedureEntity>>

    @Query("SELECT * FROM procedures WHERE trigger_phrase = :trigger LIMIT 1")
    suspend fun getProcedureByTrigger(trigger: String): ProcedureEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProcedure(proc: ProcedureEntity): Long

    @Query("UPDATE procedures SET execution_count = execution_count + 1, updated_at = :timestamp WHERE trigger_phrase = :trigger")
    suspend fun recordExecution(trigger: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM procedures WHERE trigger_phrase = :trigger")
    suspend fun deleteProcedure(trigger: String)
}
