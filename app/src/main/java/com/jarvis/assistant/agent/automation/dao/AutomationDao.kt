package com.jarvis.assistant.agent.automation.dao

import androidx.room.*
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {
    
    @Query("SELECT * FROM automations ORDER BY updated_at DESC")
    fun getAllAutomationsStream(): Flow<List<AutomationEntity>>

    @Query("SELECT * FROM automations WHERE is_enabled = 1 ORDER BY updated_at DESC")
    suspend fun getActiveAutomations(): List<AutomationEntity>
    
    @Query("SELECT * FROM automations ORDER BY updated_at DESC")
    suspend fun getAllAutomations(): List<AutomationEntity>
    
    @Query("SELECT * FROM automations WHERE trigger_type = :triggerType AND is_enabled = 1")
    suspend fun getAutomationsByTrigger(triggerType: String): List<AutomationEntity>
    
    @Query("SELECT * FROM automations WHERE rule_id = :ruleId LIMIT 1")
    suspend fun getAutomationByRuleId(ruleId: String): AutomationEntity?
    
    @Query("SELECT * FROM automations WHERE id = :id LIMIT 1")
    suspend fun getAutomationById(id: Long): AutomationEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutomation(automation: AutomationEntity): Long
    
    @Update
    suspend fun updateAutomation(automation: AutomationEntity)
    
    @Delete
    suspend fun deleteAutomation(automation: AutomationEntity)
    
    @Query("DELETE FROM automations WHERE rule_id = :ruleId")
    suspend fun deleteAutomation(ruleId: String)

    @Query("DELETE FROM automations WHERE rule_id = :ruleId")
    suspend fun deleteAutomationByRuleId(ruleId: String)
    
    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun deleteAutomationById(id: Long)
    
    @Query("UPDATE automations SET is_enabled = :enabled WHERE rule_id = :ruleId")
    suspend fun toggleEnabled(ruleId: String, enabled: Boolean)

    @Query("UPDATE automations SET is_enabled = :enabled WHERE rule_id = :ruleId")
    suspend fun setAutomationEnabled(ruleId: String, enabled: Boolean)
    
    @Query("UPDATE automations SET last_triggered_at = :timestamp, trigger_count = trigger_count + 1 WHERE id = :id")
    suspend fun recordTrigger(id: Long, timestamp: Long = System.currentTimeMillis())
    
    @Query("SELECT COUNT(*) FROM automations")
    suspend fun getAutomationsCount(): Int
    
    @Query("DELETE FROM automations")
    suspend fun deleteAllAutomations()
}
