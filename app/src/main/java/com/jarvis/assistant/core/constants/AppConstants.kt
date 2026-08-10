package com.jarvis.assistant.core.constants

object AppConstants {
    // OpenAI Compatible API Defaults
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1/"
    const val DEFAULT_MODEL = "gpt-4o-mini"
    
    // Fallback System Prompt
    const val DEFAULT_SYSTEM_PROMPT = """Ты JARVIS — персональный AI-ассистент пользователя.
Твоя задача:
- помогать пользователю;
- объяснять понятным языком;
- быть кратким;
- помогать в обучении;
- помогать в бизнесе;
- помогать в планировании.

Стиль общения:
- спокойный;
- профессиональный;
- умный;
- дружелюбный."""

    // TTS & Voice Defaults
    const val DEFAULT_SPEECH_RATE = 1.0f
    const val DEFAULT_SPEECH_PITCH = 1.0f
    const val DEFAULT_LANGUAGE_TAG = "ru-RU"
    
    // Security Preferences Key
    const val SECURE_PREFS_NAME = "jarvis_secure_prefs"
    const val KEY_API_TOKEN = "enc_openai_api_key"
    
    // Database
    const val DATABASE_NAME = "jarvis_database.db"
    
    // Network Timeouts
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L
}
