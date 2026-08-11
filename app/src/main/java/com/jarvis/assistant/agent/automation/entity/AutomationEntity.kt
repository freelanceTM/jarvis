package com.jarvis.assistant.agent.automation.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "automations",
    indices = [Index(value = ["trigger_type"]), Index(value = ["is_enabled"])]
)
data class AutomationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "rule_id")
    val ruleId: String,

    @ColumnInfo(name = "name")
    val name: String, // "Утренний режим в наушниках"

    @ColumnInfo(name = "trigger_type")
    val triggerType: String, // HEADPHONES_CONNECTED, BATTERY_LOW, WIFI_CONNECTED

    @ColumnInfo(name = "trigger_param")
    val triggerParam: String = "",

    @ColumnInfo(name = "conditions_json")
    val conditionsJson: String = "", // JSON временных диапазонов

    @ColumnInfo(name = "actions_json")
    val actionsJson: String, // JSON действий [{"tool":"device.open_app", ...}]

    @ColumnInfo(name = "voice_announcement")
    val voiceAnnouncement: String = "",

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "last_triggered_at")
    val lastTriggeredAt: Long = 0L,

    @ColumnInfo(name = "trigger_count")
    val triggerCount: Int = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
