package com.jarvis.assistant.agent.automation.scheduler

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * H-06: гарантированный reconcile правил автоматизации.
 *
 * Задача:
 *   - Выполнять reconcile ровно один раз после BOOT_COMPLETED
 *     (WorkManager сам переживёт рестарт процесса, doze-mode, и т.п.).
 *   - НЕ вызывать broadcast на каждый холодный старт процесса — это
 *     дорого и провоцирует двойную работу на screen-on/password-prompt.
 *
 * Внутри воркера мы посылаем ACTION_RECONCILE broadcast самому
 * [AutomationScheduleReceiver] — он уже реализует нужную логику
 * (goAsync + 8-секундный timeout + CEH), и нам не нужно дублировать её
 * здесь.
 *
 * KEEP policy: если reconcile уже в очередке/выполняется — второй
 * экземпляр не ставится.
 */
class AutomationReconcileWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        appContext.sendBroadcast(
            Intent(appContext, AutomationScheduleReceiver::class.java)
                .setAction(AutomationScheduleReceiver.ACTION_RECONCILE)
        )
        Result.success()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        // Планировщик и так сделает ретрай по собственной политике,
        // но явно возвращаем failure для логов.
        Result.failure()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "automation-reconcile"

        /**
         * Ставим reconcile в очередь уникально (KEEP — если уже есть,
         * не дублируем). Используется из BOOT_COMPLETED receiver.
         */
        @JvmStatic
        fun enqueueUnique(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutomationReconcileWorker>()
                // Небольшая задержка после BOOT: не конкурируем за IO с
                // системными сервисами и своими стартующими foreground service.
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
