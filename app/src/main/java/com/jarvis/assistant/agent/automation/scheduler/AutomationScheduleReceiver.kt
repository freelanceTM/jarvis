package com.jarvis.assistant.agent.automation.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.agent.automation.dao.AutomationDao
import com.jarvis.assistant.agent.automation.engine.PersonalAutomationEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutomationScheduleEntryPoint {
    fun automationEngine(): PersonalAutomationEngine
    fun automationScheduleManager(): AutomationScheduleManager
    fun automationDao(): AutomationDao
}

class AutomationScheduleReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AutomationScheduleRx"

        const val ACTION_TRIGGER = "com.jarvis.assistant.action.AUTOMATION_SCHEDULE_TRIGGER"
        const val ACTION_RECONCILE = "com.jarvis.assistant.action.AUTOMATION_SCHEDULE_RECONCILE"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    AutomationScheduleEntryPoint::class.java
                )
                val engine = entryPoint.automationEngine()
                val scheduler = entryPoint.automationScheduleManager()
                when (intent.action) {
                    ACTION_TRIGGER -> executeScheduled(intent, engine, scheduler)
                    ACTION_RECONCILE,
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> reconcile(engine, scheduler)
                }
            } catch (failure: Throwable) {
                Log.e(TAG, "Schedule receiver failed | type=${failure.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun executeScheduled(
        intent: Intent,
        engine: PersonalAutomationEngine,
        scheduler: AutomationScheduleManager
    ) {
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, 0)
        val hour = intent.getIntExtra(EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(EXTRA_MINUTE, -1)
        if (hour !in 0..23 || minute !in 0..59 || !scheduler.claimDelivery(scheduledAt)) {
            Log.w(TAG, "Invalid or duplicate scheduled delivery rejected")
            scheduler.reconcile()
            return
        }
        engine.onTimeSchedule(hour, minute)
        scheduler.reconcile()
    }

    private suspend fun reconcile(
        engine: PersonalAutomationEngine,
        scheduler: AutomationScheduleManager
    ) {
        // Also creates default rules before querying the scheduler source of truth.
        engine.getActiveRules()
        val result = scheduler.reconcile(includeMissed = true)
        for (missed in result.missed) {
            if (scheduler.claimDelivery(missed.scheduledAtMillis)) {
                engine.onTimeSchedule(missed.slot.hour, missed.slot.minute)
            }
        }
        if (result.missed.isNotEmpty()) scheduler.reconcile()
    }
}
