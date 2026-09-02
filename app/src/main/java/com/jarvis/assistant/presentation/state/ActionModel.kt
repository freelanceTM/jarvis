package com.jarvis.assistant.presentation.state

import androidx.compose.runtime.Immutable

/**
 * User-facing representation of an action (§16, §37, §61).
 *
 * The frontend never invents a status: every field is filled from the real
 * tool call and its execution result. The UI turns
 *
 * ```
 * type = CALL, status = EXECUTING, target = "Alex"
 * ```
 *
 * into "Calling Alex…" — and never into "Executing…" (§19 of the spec).
 */
@Immutable
data class ActionSnapshot(
    val type: ActionType,
    val status: ActionStatus,
    /** Raw tool name, kept for diagnostics; never shown to a normal user. */
    val toolName: String,
    /** Human target of the action: a contact, an app, an alarm time. */
    val target: String? = null,
    /** Result text once the action finished successfully. */
    val result: String? = null,
    /** Failure cause, already mapped to a human-readable system state. */
    val error: SystemStateType? = null,
    val confirmationRequired: Boolean = false
)

/**
 * The kinds of action the UI can phrase naturally. [Generic] is the honest
 * fallback for a tool without dedicated copy — it produces "Working…" rather
 * than leaking a tool name.
 */
enum class ActionType {
    CALL,
    MESSAGE,
    ALARM,
    TIMER,
    CALENDAR,
    NAVIGATION,
    WEATHER,
    SEARCH,
    TRANSLATE,
    APP,
    DEVICE_SETTING,
    MEDIA,
    AUTOMATION,
    Generic
}

/** Lifecycle of an action, mirroring the executor's real states. */
enum class ActionStatus {
    /** Recognised, waiting for the user's confirmation (§17). */
    PENDING_CONFIRMATION,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    /** The user cancelled before execution. */
    CANCELLED
}

/**
 * A confirmation the user must resolve before OMNIX proceeds (§17, §36, §62).
 *
 * Execution never starts until the decision is made.
 */
@Immutable
data class ConfirmationRequest(
    /** "Send this message to Alex?" */
    val title: String,
    /** The exact content that will be sent or performed. */
    val detail: String? = null,
    val confirmLabel: String,
    val cancelLabel: String,
    /** True when the voice layer also accepts a spoken decision. */
    val voiceEnabled: Boolean = false,
    val action: ActionSnapshot? = null
)

/**
 * Every system condition the UI can present, in exactly one place (§18, §48).
 *
 * A screen never invents its own error design and never shows
 * `DEVICE_DISCONNECTED`, `HTTP 503`, `ToolException` or `TimeoutException`
 * to a normal user (§18, §50).
 */
enum class SystemStateType {
    CLIP_DISCONNECTED,
    BLUETOOTH_OFF,
    MICROPHONE_DENIED,
    MICROPHONE_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    SERVICE_UNREACHABLE,
    /** The phone must be unlocked, or a system screen must be confirmed. */
    USER_ACTION_REQUIRED,
    /** Speech was not understood. */
    NOT_UNDERSTOOD,
    ACTION_FAILED,
    PERMISSION_REQUIRED,
    ACCESS_EXPIRED,
    /** The requested capability exists in the design but not in this build. */
    CAPABILITY_UNAVAILABLE
}
