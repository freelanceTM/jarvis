package com.jarvis.assistant.agent.tools.verification

import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Действие над громкостью — общее для [com.jarvis.assistant.agent.tools.device.SetVolumeTool]
 * и [com.jarvis.assistant.agent.tools.media.MediaControlTool].
 */
enum class VolumeAction { UP, DOWN, MUTE, MAX, SET }

/**
 * Execute → Verify → SUCCESS: единые правила верификации результата инструмента.
 *
 * Доктрина проекта: инструмент НЕ имеет права возвращать SUCCESS, пока Android
 * не подтвердил фактическое изменение состояния устройства. Никогда:
 *
 * ```
 * Tool → exception → «Готово»
 * ```
 *
 * Только:
 *
 * ```
 * Tool → execute → verify → SUCCESS
 * Tool → execute → failure → ERROR
 * ```
 *
 * Этот объект содержит ЧИСТУЮ логику решения «подтвердилась ли мутация» —
 * без Android-классов, чтобы она покрывалась JVM-тестами. Чтение реального
 * состояния (read-back) остаётся в самих инструментах: они знают, каким API
 * читается каждое состояние (AudioManager, Settings.System, NotificationManager,
 * CameraManager, AlarmManager, ClipboardManager).
 */
object ExecutionVerification {

    /** Дефолтные параметры поллинга read-back (суммарно << tool executionTimeoutMs = 4 с). */
    const val VERIFY_ATTEMPTS = 5
    const val VERIFY_STEP_MS = 60L

    /** Код ошибки «мутация не подтвердилась» для громкости. */
    const val REASON_VOLUME_VERIFY_FAILED = "VOLUME_VERIFY_FAILED"

    /** Код ошибки «достигнут предел регулировки» (уже максимум / уже выключено). */
    const val REASON_VOLUME_AT_LIMIT = "VOLUME_AT_LIMIT"

    /** Код ошибки «состояние не изменилось». */
    const val REASON_VOLUME_UNCHANGED = "VOLUME_UNCHANGED"

    /**
     * Читает состояние до выполнения предиката (или до исчерпания попыток).
     *
     * Android применяет изменения состояния асинхронно: один read-back сразу
     * после мутации может увидеть старое значение. Поллинг с коротким шагом
     * устраняет ложные отказы, оставаясь в пределах tool-таймаута.
     *
     * @return последнее прочитанное значение — даже если предикат не выполнился
     *         (вызывающий код верифицирует его и формулирует честный отказ).
     */
    suspend fun <T> pollFor(
        attempts: Int = VERIFY_ATTEMPTS,
        stepMs: Long = VERIFY_STEP_MS,
        read: () -> T,
        satisfied: (T) -> Boolean
    ): T {
        var value = read()
        var attempt = 1
        while (attempt < attempts && !satisfied(value)) {
            delay(stepMs)
            value = read()
            attempt++
        }
        return value
    }

    // ------------------------------------------------------------------
    // Громкость
    // ------------------------------------------------------------------

    /** Итог верификации мутации громкости. */
    data class VolumeOutcome(
        /** true — Android подтвердил изменение; только тогда инструмент вернёт SUCCESS. */
        val verified: Boolean,
        /** Честная формулировка для пользователя (и для успеха, и для отказа). */
        val summary: String,
        /** Машиночитаемая причина отказа; null у подтверждённых исходов. */
        val reason: String? = null
    )

    /** Целевой индекс для «громкость N%» — та же формула, что использовалась исторически. */
    fun volumeTargetIndex(percent: Int, maxIndex: Int): Int = (maxIndex * (percent / 100.0)).toInt()

    /** Процент для отображения по индексу. */
    fun volumePercentOf(index: Int, maxIndex: Int): Int =
        if (maxIndex <= 0) 0 else (index * 100f / maxIndex).roundToInt()

    /**
     * Верифицирует мутацию громкости по фактическому (прочитанному) индексу.
     *
     * @param previousIndex индекс ДО мутации
     * @param actualIndex индекс ПОСЛЕ мутации (read-back); null — прочитать не удалось
     */
    fun verifyVolumeChange(
        action: VolumeAction,
        previousIndex: Int,
        actualIndex: Int?,
        maxIndex: Int,
        minIndex: Int = 0,
        requestedPercent: Int = 50
    ): VolumeOutcome {
        val actualPercent = volumePercentOf(actualIndex ?: minIndex, maxIndex)
        return when (action) {
            VolumeAction.UP -> when {
                actualIndex == null -> VolumeOutcome(false, "Не удалось подтвердить изменение громкости", REASON_VOLUME_VERIFY_FAILED)
                actualIndex > previousIndex -> VolumeOutcome(true, "Громкость увеличена до $actualPercent%")
                previousIndex >= maxIndex -> VolumeOutcome(false, "Громкость уже на максимуме", REASON_VOLUME_AT_LIMIT)
                else -> VolumeOutcome(
                    false,
                    "Громкость не изменилась — текущий уровень $actualPercent%",
                    REASON_VOLUME_UNCHANGED
                )
            }

            VolumeAction.DOWN -> when {
                actualIndex == null -> VolumeOutcome(false, "Не удалось подтвердить изменение громкости", REASON_VOLUME_VERIFY_FAILED)
                actualIndex < previousIndex -> VolumeOutcome(true, "Громкость уменьшена до $actualPercent%")
                previousIndex <= minIndex -> VolumeOutcome(false, "Звук уже выключен", REASON_VOLUME_AT_LIMIT)
                else -> VolumeOutcome(
                    false,
                    "Громкость не изменилась — текущий уровень $actualPercent%",
                    REASON_VOLUME_UNCHANGED
                )
            }

            VolumeAction.MUTE -> when {
                actualIndex == minIndex -> VolumeOutcome(true, "Звук выключен")
                actualIndex == null -> VolumeOutcome(false, "Не удалось подтвердить выключение звука", REASON_VOLUME_VERIFY_FAILED)
                else -> VolumeOutcome(
                    false,
                    "Не удалось выключить звук — текущий уровень $actualPercent%",
                    REASON_VOLUME_VERIFY_FAILED
                )
            }

            VolumeAction.MAX -> when {
                actualIndex == maxIndex -> VolumeOutcome(true, "Громкость установлена на максимум")
                actualIndex == null -> VolumeOutcome(false, "Не удалось подтвердить установку громкости", REASON_VOLUME_VERIFY_FAILED)
                else -> VolumeOutcome(
                    false,
                    "Не удалось установить максимальную громкость — текущий уровень $actualPercent%",
                    REASON_VOLUME_VERIFY_FAILED
                )
            }

            VolumeAction.SET -> when {
                actualIndex == volumeTargetIndex(requestedPercent, maxIndex) ->
                    VolumeOutcome(true, "Громкость установлена на $requestedPercent%")
                actualIndex == null ->
                    VolumeOutcome(false, "Не удалось подтвердить установку громкости", REASON_VOLUME_VERIFY_FAILED)
                else -> VolumeOutcome(
                    false,
                    "Не удалось установить громкость на $requestedPercent% — фактический уровень $actualPercent%",
                    REASON_VOLUME_VERIFY_FAILED
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Яркость
    // ------------------------------------------------------------------

    /** Яркость подтверждена, только если read-back вернул ровно записанное значение. */
    fun brightnessVerified(actualRaw: Int?, expectedRaw: Int): Boolean = actualRaw != null && actualRaw == expectedRaw

    // ------------------------------------------------------------------
    // Режим «Не беспокоить»
    // ------------------------------------------------------------------

    /**
     * DND подтверждён, только если И применённый фильтр (возврат
     * [android.app.NotificationManager.setInterruptionFilter]), И read-back
     * [android.app.NotificationManager.currentInterruptionFilter] равны целевому.
     * INTERRUPTION_FILTER_UNKNOWN (0) так не совпасть не может — fail-closed.
     */
    fun dndVerified(appliedFilter: Int?, currentFilter: Int?, targetFilter: Int): Boolean =
        appliedFilter == targetFilter && currentFilter == targetFilter

    // ------------------------------------------------------------------
    // Будильник
    // ------------------------------------------------------------------

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Допуск на задержку применения будильника приложением часов. */
    private const val ALARM_SLACK_MS = 60_000L

    /**
     * Подтверждает будильник на час [requestedHour] по read-back
     * [android.app.AlarmManager.getNextAlarmClock].
     *
     * Правила честности:
     *  - следующего будильника нет → не подтверждено;
     *  - триггер в прошлом или дальше, чем следующее наступление часа (≤24 ч + допуск) → не подтверждено;
     *  - час триггера в локальной зоне не равен запрошенному → не подтверждено
     *    (например, ранее стоит другой будильник — мы НЕ можем доказать, что наш сохранился).
     */
    fun nextAlarmMatchesHour(
        nextTriggerEpochMs: Long?,
        nowEpochMs: Long,
        requestedHour: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (nextTriggerEpochMs == null) return false
        if (nextTriggerEpochMs <= nowEpochMs) return false
        if (nextTriggerEpochMs - nowEpochMs > DAY_MS + ALARM_SLACK_MS) return false
        return Instant.ofEpochMilli(nextTriggerEpochMs).atZone(zone).hour == requestedHour
    }

    // ------------------------------------------------------------------
    // Фонарик
    // ------------------------------------------------------------------

    /**
     * Выбирает камеру со вспышкой: сначала задняя с фонариком, затем любая со вспышкой.
     * Исторический баг: cameraIdList.first() может указывать на камеру без вспышки.
     */
    fun pickFlashCameraId(
        cameraIds: List<String>,
        hasFlash: (String) -> Boolean,
        isBackFacing: (String) -> Boolean
    ): String? = cameraIds.firstOrNull { isBackFacing(it) && hasFlash(it) }
        ?: cameraIds.firstOrNull { hasFlash(it) }
}
