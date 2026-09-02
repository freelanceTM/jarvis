package com.jarvis.assistant.presentation.navigation

/**
 * The OMNIX destination map (§20, §44, §66).
 *
 * Three primary destinations, with the Core in the centre:
 *
 * ```
 * History  |  ◎ Core  |  Me
 * ```
 *
 * Everything else — Chat, Translator, Devices, Privacy, the first-run flow —
 * is reached from one of those three. Secondary sections never compete with
 * the Core for attention (§45).
 */
sealed class OmnixDestination(val route: String) {

    /** Primary: the past. Conversations and completed actions. */
    data object History : OmnixDestination("omnix/history")

    /** Primary: the present. Home — presence and orientation. */
    data object Home : OmnixDestination("omnix/home")

    /** Primary: the user. Settings, devices, privacy, account. */
    data object Me : OmnixDestination("omnix/me")

    /** Secondary: text conversation, deliberately not the main surface (§41). */
    data object Chat : OmnixDestination("omnix/chat")

    /** Secondary: translation as a mode, not a separate app (§43). */
    data object Translator : OmnixDestination("omnix/translator")

    /** Secondary: the Clip and other devices (§40). */
    data object Devices : OmnixDestination("omnix/devices")

    /** Secondary: what OMNIX keeps and where it goes (§42, §52). */
    data object Privacy : OmnixDestination("omnix/privacy")

    /** Settings sub-pages, addressed by a human concept (§42). */
    data class SettingsSection(val section: String) :
        OmnixDestination("omnix/settings/$section") {
        companion object {
            const val ROUTE_PATTERN = "omnix/settings/{section}"
            const val ARG_SECTION = "section"
        }
    }

    /** The first-run flow (§34, §67). */
    data object FirstRun : OmnixDestination("omnix/first-run")

    companion object {
        /** The three destinations that appear in the navigation bar. */
        val primary = listOf(History, Home, Me)
    }
}
