package com.jarvis.assistant.core.constants

import com.jarvis.assistant.BuildConfig

object AppConstants {
    // Flavor-owned origin. AI provider keys still live only on the server.
    val JARVIS_API_BASE_URL: String = BuildConfig.JARVIS_API_BASE_URL
    val JARVIS_LICENSE_BASE_URL: String = BuildConfig.JARVIS_LICENSE_BASE_URL

    /**
     * Модель выбирается сервером (AI Router), а не клиентом. Константа
     * сохранена для обратной совместимости DataStore-настроек.
     */
    const val DEFAULT_MODEL = "server-managed"
    
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

    /** Токен доступа к JARVIS API (Этап 3). */
    const val KEY_ACCESS_TOKEN = "enc_jarvis_access_token"

    /**
     * Legacy-ключ AI-провайдера (BYOK до Этапа 3).
     * Больше не используется — удаляется при старте SecurityManagerImpl.
     */
    const val LEGACY_KEY_PROVIDER_API = "enc_openai_api_key"
    
    // Database
    const val DATABASE_NAME = "jarvis_database.db"
    
    // Network Timeouts
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L
}
