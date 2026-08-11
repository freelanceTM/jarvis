package com.jarvis.assistant.agent.automation.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {

    @Query("SELECT * FROM automations ORDER BY updated_at DESC")
    fun getAllAutomationsStream(): Flow<List<AutomationEntity>>

    @Query("SELECT * FROM automations WHERE is_enabled = 1")
    suspend fun getActiveAutomations(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE trigger_type = :triggerType AND is_enabled = 1")
    suspend fun getAutomationsByTrigger(triggerType: String): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE rule_id = :ruleId LIMIT 1")
    suspend fun getAutomationByRuleId(ruleId: String): AutomationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutomation(automation: AutomationEntity): Long

    @Query("UPDATE automations SET last_triggered_at = :timestamp, trigger_count = trigger_count + 1 WHERE id = :id")
    suspend fun recordTrigger(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE automations SET is_enabled = :enabled WHERE rule_id = :ruleId")
    suspend fun toggleEnabled(ruleId: String, enabled: Boolean)

    @Query("DELETE FROM automations WHERE rule_id = :ruleId")
    suspend fun deleteAutomation(ruleId: String)
}
