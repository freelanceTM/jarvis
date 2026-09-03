package com.jarvis.assistant.agent.tools.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime


/**
 * Доктрина execute → verify → SUCCESS: чистая логика верификации мутаций.
 *
 * Каждый тест — контракт честности: SUCCESS возможен только после
 * подтверждённого системой изменения состояния устройства.
 */
class ExecutionVerificationTest {

    // ------------------------------------------------------------ volume

    @Test
    fun `volume up verified only when index actually increased`() {
        val ok = ExecutionVerification.verifyVolumeChange(VolumeAction.UP, previousIndex = 4, actualIndex = 7, maxIndex = 15)
        assertTrue(ok.verified)
        assertNull(ok.reason)

        val unchanged = ExecutionVerification.verifyVolumeChange(VolumeAction.UP, previousIndex = 4, actualIndex = 4, maxIndex = 15)
        assertFalse(unchanged.verified)
        assertEquals(ExecutionVerification.REASON_VOLUME_UNCHANGED, unchanged.reason)

        val unreadable = ExecutionVerification.verifyVolumeChange(VolumeAction.UP, previousIndex = 4, actualIndex = null, maxIndex = 15)
        assertFalse(unreadable.verified)
        assertEquals(ExecutionVerification.REASON_VOLUME_VERIFY_FAILED, unreadable.reason)
    }

    @Test
    fun `volume up at maximum reports limit instead of fake success`() {
        val atLimit = ExecutionVerification.verifyVolumeChange(VolumeAction.UP, previousIndex = 15, actualIndex = 15, maxIndex = 15)
        assertFalse(atLimit.verified)
        assertEquals(ExecutionVerification.REASON_VOLUME_AT_LIMIT, atLimit.reason)
        assertTrue(atLimit.summary.contains("максимуме"))
    }

    @Test
    fun `volume down at zero reports already muted instead of fake success`() {
        val atLimit = ExecutionVerification.verifyVolumeChange(VolumeAction.DOWN, previousIndex = 0, actualIndex = 0, maxIndex = 15, minIndex = 0)
        assertFalse(atLimit.verified)
        assertEquals(ExecutionVerification.REASON_VOLUME_AT_LIMIT, atLimit.reason)
    }

    @Test
    fun `mute verified only at minimum index`() {
        val ok = ExecutionVerification.verifyVolumeChange(VolumeAction.MUTE, previousIndex = 5, actualIndex = 0, maxIndex = 15)
        assertTrue(ok.verified)

        val failed = ExecutionVerification.verifyVolumeChange(VolumeAction.MUTE, previousIndex = 5, actualIndex = 3, maxIndex = 15)
        assertFalse(failed.verified)
        assertEquals(ExecutionVerification.REASON_VOLUME_VERIFY_FAILED, failed.reason)
    }

    @Test
    fun `set percent verified only at exact target index and reports actual level on mismatch`() {
        // maxIndex=15: 40% → (15*0.4).toInt() = 6
        val ok = ExecutionVerification.verifyVolumeChange(VolumeAction.SET, previousIndex = 4, actualIndex = 6, maxIndex = 15, requestedPercent = 40)
        assertTrue(ok.verified)
        assertTrue(ok.summary.contains("40%"))

        val mismatch = ExecutionVerification.verifyVolumeChange(VolumeAction.SET, previousIndex = 4, actualIndex = 8, maxIndex = 15, requestedPercent = 40)
        assertFalse(mismatch.verified)
        assertTrue(mismatch.summary.contains("53%"))
        assertEquals(ExecutionVerification.REASON_VOLUME_VERIFY_FAILED, mismatch.reason)
    }

    @Test
    fun `volume target index keeps historical truncation formula`() {
        assertEquals(6, ExecutionVerification.volumeTargetIndex(40, 15))
        assertEquals(0, ExecutionVerification.volumeTargetIndex(0, 15))
        assertEquals(15, ExecutionVerification.volumeTargetIndex(100, 15))
        assertEquals(33, ExecutionVerification.volumeTargetIndex(33, 100))
    }

    // ------------------------------------------------------------ brightness

    @Test
    fun `brightness verified only by exact raw read-back`() {
        assertTrue(ExecutionVerification.brightnessVerified(actualRaw = 128, expectedRaw = 128))
        assertFalse(ExecutionVerification.brightnessVerified(actualRaw = 127, expectedRaw = 128))
        assertFalse(ExecutionVerification.brightnessVerified(actualRaw = null, expectedRaw = 128))
    }

    // ------------------------------------------------------------ DND

    @Test
    fun `dnd verified only when applied and current filters both match target`() {
        val target = 2 // INTERRUPTION_FILTER_PRIORITY
        assertTrue(ExecutionVerification.dndVerified(appliedFilter = 2, currentFilter = 2, targetFilter = target))

        // applied = INTERRUPTION_FILTER_UNKNOWN (0) — система отклонила вызов.
        assertFalse(ExecutionVerification.dndVerified(appliedFilter = 0, currentFilter = 2, targetFilter = target))
        // Read-back всё ещё показывает старый фильтр — не подтверждено.
        assertFalse(ExecutionVerification.dndVerified(appliedFilter = 2, currentFilter = 1, targetFilter = target))
        assertFalse(ExecutionVerification.dndVerified(appliedFilter = null, currentFilter = null, targetFilter = target))
    }

    // ------------------------------------------------------------ alarm

    @Test
    fun `next alarm confirms requested hour in local zone`() {
        val zone = ZoneId.of("Europe/Stockholm")
        val now = ZonedDateTime.of(2026, 9, 3, 10, 0, 0, 0, zone)
        val triggerAt17 = now.toLocalDate().atTime(17, 0).atZone(zone).toInstant().toEpochMilli()
        val nowMs = now.toInstant().toEpochMilli()

        assertTrue(ExecutionVerification.nextAlarmMatchesHour(triggerAt17, nowMs, 17, zone))
        assertFalse(ExecutionVerification.nextAlarmMatchesHour(triggerAt17, nowMs, 7, zone))
        // Триггер в прошлом — подтверждением не является.
        val past = nowMs - 60_000
        assertFalse(ExecutionVerification.nextAlarmMatchesHour(past, nowMs, 9, zone))
        // Дальше 24 часов — это не «следующее наступление часа».
        val far = nowMs + 30L * 60 * 60 * 1000
        assertFalse(ExecutionVerification.nextAlarmMatchesHour(far, nowMs, 16, zone))
        // Нет следующего будильника — подтверждением не является.
        assertFalse(ExecutionVerification.nextAlarmMatchesHour(null, nowMs, 17, zone))
    }

    @Test
    fun `next alarm across midnight matches tomorrow hour`() {
        val zone = ZoneId.of("Europe/Stockholm")
        val now = ZonedDateTime.of(2026, 9, 3, 23, 30, 0, 0, zone)
        val triggerAt7 = now.toLocalDate().plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(ExecutionVerification.nextAlarmMatchesHour(triggerAt7, now.toInstant().toEpochMilli(), 7, zone))
    }

    // ------------------------------------------------------------ flash camera

    @Test
    fun `flash camera prefers back-facing with flash then any flash`() {
        val picked = ExecutionVerification.pickFlashCameraId(
            cameraIds = listOf("front", "rear", "wide"),
            hasFlash = { it == "rear" || it == "wide" },
            isBackFacing = { it == "rear" }
        )
        assertEquals("rear", picked)

        val fallback = ExecutionVerification.pickFlashCameraId(
            cameraIds = listOf("front", "aux"),
            hasFlash = { it == "aux" },
            isBackFacing = { false }
        )
        assertEquals("aux", fallback)

        assertNull(ExecutionVerification.pickFlashCameraId(emptyList(), hasFlash = { true }, isBackFacing = { true }))
    }
}
