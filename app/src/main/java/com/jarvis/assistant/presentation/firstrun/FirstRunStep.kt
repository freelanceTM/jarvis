package com.jarvis.assistant.presentation.firstrun

/**
 * The first-run sequence (§34, §67).
 *
 * ```
 * Welcome → Device detection → Clip pairing → Microphone → First command → Home
 * ```
 *
 * Each step asks for exactly one thing and explains it in human language. The
 * words "SDK", "service", "permission identifier", "STT" and "provider" do not
 * appear anywhere in this flow (§4, §35).
 */
enum class FirstRunStep {
    /** "Voice in your ear." Presence before instruction. */
    Welcome,

    /**
     * Looking for a Clip. This step is skippable: OMNIX is usable with the
     * phone alone, and blocking here would strand a user without hardware.
     */
    DeviceDetection,

    /** Confirming the Clip that was found and connecting to it. */
    ClipPairing,

    /** The microphone request, framed as What / Why / Action (§38, §50). */
    Microphone,

    /** One real spoken command, so the first success happens here (§34). */
    FirstCommand,

    /** Finished — Home takes over. */
    Complete;

    val isFirst: Boolean get() = this == Welcome

    /** The step that follows, given whether a Clip was actually found. */
    fun next(clipFound: Boolean): FirstRunStep = when (this) {
        Welcome -> DeviceDetection
        DeviceDetection -> if (clipFound) ClipPairing else Microphone
        ClipPairing -> Microphone
        Microphone -> FirstCommand
        FirstCommand -> Complete
        Complete -> Complete
    }

    /** The step before, used by the system back gesture (§46). */
    fun previous(): FirstRunStep? = when (this) {
        Welcome -> null
        DeviceDetection -> Welcome
        ClipPairing -> DeviceDetection
        Microphone -> DeviceDetection
        FirstCommand -> Microphone
        Complete -> null
    }
}
