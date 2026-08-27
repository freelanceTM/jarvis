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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutomationScheduleEntryPoint {
    fun automationEngine(): PersonalAutomationEngine
    fun automationScheduleManager(): AutomationScheduleManager
    fun automationDao(): AutomationDao
}

/**
 * H-03: receiver для автоматизаций по расписанию и reconcile после
 * BOOT/TIME_CHANGED/MY_PACKAGE_REPLACED.
 *
 * Паттерн асинхронной работы скопирован 1-в-1 с [com.jarvis.assistant.voice.service.SystemEventReceiver]:
 *   - один SupervisorJob + CEH на Companion scope (не создаём новый
 *     CoroutineScope на каждый onReceive — это и была причина краша
 *     "Receiver dropped / scope leaked / uncaught CEH");
 *   - goAsync() + withTimeout(DISPATCH_TIMEOUT_MS=8000) — строгий upper-bound,
 *     чтобы не схлопотать ANR (система даёт ~10с);
 *   - AtomicBoolean finish-guard — ровно один вызов pending.finish() даже
 *     при гонке timeout vs normal completion;
 *   - CancellationException не маскируется и не логируется как ошибка.
 */
class AutomationScheduleReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AutomationScheduleRx"

        const val ACTION_TRIGGER = "com.jarvis.assistant.action.AUTOMATION_SCHEDULE_TRIGGER"
        const val ACTION_RECONCILE = "com.jarvis.assistant.action.AUTOMATION_SCHEDULE_RECONCILE"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"

        /**
         * H-03: строгий upper-bound на всю асинхронную работу receiver'а.
         * Система даёт ~10с после goAsync() — мы ограничиваемся 8с,
         * оставляя запас на finish().
         */
        private const val DISPATCH_TIMEOUT_MS = 8_000L

        // Один общий supervisor scope на все dispatch'и receiver'а,
        // с CEH — исключение в одном automation не гасит остальные.
        private val receiverJob = SupervisorJob()
        private val receiverExceptionHandler = CoroutineExceptionHandler { _, t ->
            if (t is CancellationException) throw t
            Log.e(TAG, "automation reconcile failed", t)
        }
        private val scope = CoroutineScope(
            Dispatchers.IO + receiverJob + receiverExceptionHandler
        )
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        Log.d(TAG, "onReceive action: $action")

        // H-06: BOOT_COMPLETED не должен запускать reconcile прямо в
        // BroadcastReceiver (система убивает receiver через ~10с и ждёт
        // тяжёлых IO-операций). Вместо этого ставим уникальную
        // WorkManager-задачу — она переживёт doze/process-restart и
        // выполнит reconcile в фоне с нормальными таймаутами.
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            AutomationReconcileWorker.enqueueUnique(context.applicationContext)
            return
        }

        val entryPoint = try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                AutomationScheduleEntryPoint::class.java
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cannot obtain automation entry point", e)
            return
        }

        val pending = goAsync()
        val finished = AtomicBoolean(false)
        scope.launch {
            try {
                withTimeout(DISPATCH_TIMEOUT_MS) {
                    val engine = entryPoint.automationEngine()
                    val scheduler = entryPoint.automationScheduleManager()
                    when (action) {
                        ACTION_TRIGGER -> executeScheduled(intent, engine, scheduler)
                        ACTION_RECONCILE,
                        Intent.ACTION_TIME_CHANGED,
                        Intent.ACTION_TIMEZONE_CHANGED,
                        Intent.ACTION_MY_PACKAGE_REPLACED -> reconcile(engine, scheduler)
                    }
                }
            } catch (ce: CancellationException) {
                // Timeout / scope shutdown — ожидаемое завершение.
                Log.w(TAG, "automation dispatch cancelled: $action")
                throw ce
            } catch (e: Throwable) {
                Log.e(TAG, "reconcile error | action=$action", e)
            } finally {
                if (finished.compareAndSet(false, true)) {
                    pending.finish()
                }
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
