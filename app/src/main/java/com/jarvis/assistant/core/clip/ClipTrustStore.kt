package com.jarvis.assistant.core.clip

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Локальное хранилище доверия для Clip: публичный ключ, закреплённый ЗА
 * СЕРВЕРОМ. Ключ пишется ТОЛЬКО из ответов сервера (attest/validate), где
 * подпись уже проверена реестром — TOFU без риска: якорь доверия серверный,
 * локальная копия лишь включает офлайн-проверку.
 *
 * Формат: base64(X.509 SPKI) в EncryptedSharedPreferences (тот же MasterKey
 * паттерн, что у LicenseManager).
 */
interface ClipTrustStore {
    fun pinnedPublicKey(clipSerial: String): String?
    fun pin(clipSerial: String, publicKeyBase64: String)
    fun forget(clipSerial: String)
}

class EncryptedClipTrustStore(context: Context) : ClipTrustStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "jarvis_clip_trust",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun pinnedPublicKey(clipSerial: String): String? =
        prefs.readOrNull(key(clipSerial))

    override fun pin(clipSerial: String, publicKeyBase64: String) {
        prefs.edit().putString(key(clipSerial), publicKeyBase64).apply()
    }

    override fun forget(clipSerial: String) {
        prefs.edit().remove(key(clipSerial)).apply()
    }

    /** Серийник — внешний идентификатор: экранируем служебные символы ключа. */
    private fun key(serial: String): String =
        "clip_" + serial.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private fun SharedPreferences.readOrNull(name: String): String? =
        runCatching { getString(name, null) }.getOrNull()
}
