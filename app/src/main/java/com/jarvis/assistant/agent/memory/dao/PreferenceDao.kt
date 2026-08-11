package com.jarvis.assistant.agent.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.agent.memory.entity.PreferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PreferenceDao {

    @Query("SELECT * FROM preferences ORDER BY updated_at DESC")
    fun getAllPreferencesStream(): Flow<List<PreferenceEntity>>

    @Query("SELECT * FROM preferences WHERE pref_key = :key LIMIT 1")
    suspend fun getPreferenceByKey(key: String): PreferenceEntity?

    @Query("SELECT * FROM preferences ORDER BY updated_at DESC")
    suspend fun getAllPreferences(): List<PreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreference(pref: PreferenceEntity): Long

    @Query("DELETE FROM preferences WHERE pref_key = :key")
    suspend fun deletePreference(key: String)
}
