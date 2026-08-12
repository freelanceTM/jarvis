package com.jarvis.assistant.agent.automation.dao

import androidx.room.*
import com.jarvis.assistant.agent.automation.entity.AutomationEntity

@Dao
interface AutomationDao {
    
    @Query("SELECT * FROM automations WHERE isEnabled = 1 ORDER BY createdAt DESC")
    suspend fun getActiveAutomations(): List<AutomationEntity>
    
    @Query("SELECT * FROM automations ORDER BY createdAt DESC")
    suspend fun getAllAutomations(): List<AutomationEntity>
    
    @Query("SELECT * FROM automations WHERE triggerType = :triggerType AND isEnabled = 1")
    suspend fun getAutomationsByTrigger(triggerType: String): List<AutomationEntity>
    
    @Query("SELECT * FROM automations WHERE ruleId = :ruleId LIMIT 1")
    suspend fun getAutomationByRuleId(ruleId: String): AutomationEntity?
    
    @Query("SELECT * FROM automations WHERE id = :id LIMIT 1")
    suspend fun getAutomationById(id: Long): AutomationEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutomation(automation: AutomationEntity): Long
    
    @Update
    suspend fun updateAutomation(automation: AutomationEntity)
    
    @Delete
    suspend fun deleteAutomation(automation: AutomationEntity)
    
    @Query("DELETE FROM automations WHERE ruleId = :ruleId")
    suspend fun deleteAutomationByRuleId(ruleId: String)
    
    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun deleteAutomationById(id: Long)
    
    @Query("UPDATE automations SET isEnabled = :enabled WHERE ruleId = :ruleId")
    suspend fun setAutomationEnabled(ruleId: String, enabled: Boolean)
    
    @Query("UPDATE automations SET lastTriggeredAt = :timestamp, triggerCount = triggerCount + 1 WHERE id = :id")
    suspend fun recordTrigger(id: Long, timestamp: Long = System.currentTimeMillis())
    
    @Query("SELECT COUNT(*) FROM automations")
    suspend fun getAutomationsCount(): Int
    
    @Query("DELETE FROM automations")
    suspend fun deleteAllAutomations()
}
