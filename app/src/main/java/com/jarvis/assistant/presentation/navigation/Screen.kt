package com.jarvis.assistant.presentation.navigation

sealed class Screen(val route: String, val title: String) {
    data object Main : Screen("main_screen", "JARVIS")
    data object Chat : Screen("chat_screen", "История диалогов")
    data object Settings : Screen("settings_screen", "Настройки")
    data object Interpreter : Screen("interpreter_screen", "Синхронный переводчик")
}
