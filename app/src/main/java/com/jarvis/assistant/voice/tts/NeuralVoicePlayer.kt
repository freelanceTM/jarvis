package com.jarvis.assistant.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NeuralVoicePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private var currentPlayer: MediaPlayer? = null

    /**
     * Высококачественный синтез естественного нейро-голоса JARVIS
     */
    suspend fun playNeuralVoice(text: String, onFinished: () -> Unit): Boolean = withContext(Dispatchers.IO) {
        stop()

        val cleanText = text.replace(Regex("[*#_`~]"), "").trim()
        if (cleanText.isEmpty()) return@withContext false

        val tempFile = File(context.cacheDir, "jarvis_voice_temp.mp3")

        try {
            val encodedText = URLEncoder.encode(cleanText, "UTF-8")
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&q=$encodedText&tl=ru&client=tw-ob"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body
            if (response.isSuccessful && responseBody != null) {
                val bytes = responseBody.bytes()
                FileOutputStream(tempFile).use { fos ->
                    fos.write(bytes)
                }

                withContext(Dispatchers.Main) {
                    playAudioFile(tempFile, onFinished)
                }
                return@withContext true
            }
        } catch (_: Exception) { }

        return@withContext false
    }

    private fun playAudioFile(file: File, onFinished: () -> Unit) {
        try {
            currentPlayer?.release()
            val player = MediaPlayer()
            currentPlayer = player

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                stop()
                onFinished()
            }
            player.setOnErrorListener { _, _, _ ->
                stop()
                onFinished()
                true
            }
            player.prepare()
            player.start()
        } catch (_: Exception) {
            stop()
            onFinished()
        }
    }

    fun stop() {
        try {
            currentPlayer?.stop()
            currentPlayer?.release()
        } catch (_: Exception) { }
        currentPlayer = null
    }
}
