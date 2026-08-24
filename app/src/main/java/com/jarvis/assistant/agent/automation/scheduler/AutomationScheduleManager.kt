package com.jarvis.assistant.agent.automation.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jarvis.assistant.agent.automation.dao.AutomationDao
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

data class ScheduleSlot(val hour: Int, val minute: Int)
data class MissedSchedule(val slot: ScheduleSlot, val scheduledAtMillis: Long)
data class ScheduleReconcileResult(
    val nextTriggerAtMillis: Long?,
    val missed: List<MissedSchedule>,
    val exact: Boolean
)

/** Pure calendar logic, including timezone/DST behavior. */
object AutomationScheduleCalculator {
    private val canonicalTime = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")

    fun parse(value: String): ScheduleSlot? {
        val clean = value.trim()
        if (!canonicalTime.matches(clean)) return null
        val parts = clean.split(':')
        return ScheduleSlot(parts[0].toInt(), parts[1].toInt())
    }

    fun nextOccurrence(now: Instant, slot: ScheduleSlot, zoneId: ZoneId): Instant {
        val zonedNow = now.atZone(zoneId)
        var candidate = zonedNow.toLocalDate()
            .atTime(LocalTime.of(slot.hour, slot.minute))
            .atZone(zoneId)
        if (!candidate.toInstant().isAfter(now)) {
            candidate = zonedNow.toLocalDate().plusDays(1)
                .atTime(LocalTime.of(slot.hour, slot.minute))
                .atZone(zoneId)
        }
        return candidate.toInstant()
    }

    fun missedOccurrences(
        rules: List<AutomationEntity>,
        now: Instant,
        zoneId: ZoneId,
        grace: Duration
    ): List<MissedSchedule> = rules
        .mapNotNull { rule ->
            val slot = parse(rule.triggerParam) ?: return@mapNotNull null
            val today = now.atZone(zoneId).toLocalDate()
                .atTime(LocalTime.of(slot.hour, slot.minute))
                .atZone(zoneId)
                .toInstant()
            val age = Duration.between(today, now)
            if (age.isNegative || age > grace || rule.lastTriggeredAt >= today.toEpochMilli()) {
                null
            } else {
                MissedSchedule(slot, today.toEpochMilli())
            }
        }
        .distinctBy(MissedSchedule::scheduledAtMillis)
        .sortedBy(MissedSchedule::scheduledAtMillis)
}

/**
 * Reconciles persisted TIME_SCHEDULE rules into one next AlarmManager alarm.
 * Room is the source of truth; alarms are rebuilt on app start, reboot and
 * time/timezone/package changes.
 */
@Singleton
class AutomationScheduleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val automationDao: AutomationDao
) {
    companion object {
        private const val TAG = "AutomationScheduler"
        private const val REQUEST_CODE = 0x4a52
        private const val PREFS = "automation_schedule_delivery"
        private const val LEGACY_LAST_DELIVERY = "last_scheduled_at"
        private const val CLAIMED_OCCURRENCES = "claimed_occurrences"
        private const val MAX_PERSISTED_CLAIMS = 64
        val MISSED_GRACE: Duration = Duration.ofMinutes(15)
    }

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val reconcileMutex = Mutex()
    private val deliveryLock = Any()

    suspend fun reconcile(
        includeMissed: Boolean = false,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ScheduleReconcileResult = reconcileMutex.withLock {
        val rules = automationDao.getAutomationsByTrigger(AutomationTriggerType.TIME_SCHEDULE.name)
            .filter(AutomationEntity::isEnabled)
        val slots = rules.mapNotNull { AutomationScheduleCalculator.parse(it.triggerParam) }.distinct()
        val missed = if (includeMissed) {
            AutomationScheduleCalculator.missedOccurrences(rules, now, zoneId, MISSED_GRACE)
        } else {
            emptyList()
        }

        val next = slots.minOfOrNull {
            AutomationScheduleCalculator.nextOccurrence(now, it, zoneId).toEpochMilli()
        }
        if (next == null) {
            cancelLocked()
            return@withLock ScheduleReconcileResult(null, missed, exact = false)
        }
        val nextSlot = slots.first {
            AutomationScheduleCalculator.nextOccurrence(now, it, zoneId).toEpochMilli() == next
        }
        val exact = scheduleLocked(next, nextSlot)
        ScheduleReconcileResult(next, missed, exact)
    }

    fun cancel() {
        val manager = alarmManager ?: return
        triggerPendingIntent(PendingIntent.FLAG_NO_CREATE)?.let(manager::cancel)
    }

    /**
     * Persistent at-most-once claim for one scheduled occurrence.
     *
     * This is an equality claim rather than a monotonic high-water mark: after a
     * manual clock correction to an earlier date, valid alarms must not be
     * rejected until wall time catches up with the old timestamp.
     */
    fun claimDelivery(scheduledAtMillis: Long): Boolean = synchronized(deliveryLock) {
        if (scheduledAtMillis <= 0) return@synchronized false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val claims = prefs.getStringSet(CLAIMED_OCCURRENCES, emptySet())
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .toMutableSet()
        // One-time migration preserves the old duplicate guard without retaining
        // its incorrect monotonic ordering semantics.
        prefs.getLong(LEGACY_LAST_DELIVERY, 0).takeIf { it > 0 }?.let(claims::add)
        if (!claims.add(scheduledAtMillis)) return@synchronized false

        val bounded = claims.sortedDescending()
            .take(MAX_PERSISTED_CLAIMS)
            .map(Long::toString)
            .toSet()
        prefs.edit()
            .putStringSet(CLAIMED_OCCURRENCES, bounded)
            .remove(LEGACY_LAST_DELIVERY)
            .commit()
    }

    /** Instrumentation hook: still uses the real AlarmManager/receiver path. */
    fun scheduleOneShotForTest(triggerAtMillis: Long, slot: ScheduleSlot) {
        require(triggerAtMillis > System.currentTimeMillis())
        scheduleLocked(triggerAtMillis, slot)
    }

    private fun scheduleLocked(triggerAtMillis: Long, slot: ScheduleSlot): Boolean {
        val manager = alarmManager ?: return false
        val operation = requireNotNull(
            triggerPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT, triggerAtMillis, slot)
        ) { "Alarm PendingIntent creation failed" }
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (canExact) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
                return true
            } catch (_: SecurityException) {
                // Special access can be revoked between the check and this call.
                Log.w(TAG, "Exact alarm access changed; scheduled inexact fallback")
            }
        } else {
            Log.w(TAG, "Exact alarm access unavailable; scheduled inexact fallback")
        }
        // Retain functionality without special access and expose exact=false to diagnostics.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        return false
    }

    private fun cancelLocked() {
        val manager = alarmManager ?: return
        triggerPendingIntent(PendingIntent.FLAG_NO_CREATE)?.let(manager::cancel)
    }

    private fun triggerPendingIntent(
        flags: Int,
        triggerAtMillis: Long = 0,
        slot: ScheduleSlot = ScheduleSlot(0, 0)
    ): PendingIntent? {
        val intent = Intent(context, AutomationScheduleReceiver::class.java)
            .setAction(AutomationScheduleReceiver.ACTION_TRIGGER)
            .putExtra(AutomationScheduleReceiver.EXTRA_SCHEDULED_AT, triggerAtMillis)
            .putExtra(AutomationScheduleReceiver.EXTRA_HOUR, slot.hour)
            .putExtra(AutomationScheduleReceiver.EXTRA_MINUTE, slot.minute)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
