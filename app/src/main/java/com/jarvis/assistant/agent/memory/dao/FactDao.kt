package com.jarvis.assistant.agent.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.agent.memory.entity.FactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FactDao {

    @Query("SELECT * FROM facts ORDER BY updated_at DESC")
    fun getAllFactsStream(): Flow<List<FactEntity>>

    @Query("SELECT * FROM facts WHERE fact_key = :key LIMIT 1")
    suspend fun getFactByKey(key: String): FactEntity?

    @Query("SELECT * FROM facts ORDER BY updated_at DESC")
    suspend fun getAllFacts(): List<FactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFact(fact: FactEntity): Long

    @Query("DELETE FROM facts WHERE fact_key = :key")
    suspend fun deleteFact(key: String)
}
