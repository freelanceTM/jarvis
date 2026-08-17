package com.jarvis.assistant.presentation.navigation

sealed class Screen(val route: String) {
    data object Main : Screen("main_screen")
    data object Chat : Screen("chat_screen")
    data object Settings : Screen("settings_screen")
    data object Interpreter : Screen("interpreter_screen")
}
