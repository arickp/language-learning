package io.github.arickp.languagelearning

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface PronunciationState {
    data object Idle : PronunciationState
    data object Loading : PronunciationState
    data object Playing : PronunciationState
    data object Unavailable : PronunciationState
}

object PronunciationPlayer {
    private var player: MediaPlayer? = null

    suspend fun play(context: Context, text: String, variant: LanguageVariant, languageOverride: String? = null): PronunciationState {
        if (BuildConfig.SERVER_URL.isBlank()) return PronunciationState.Unavailable
        val audioFile = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${variant.name}|${languageOverride.orEmpty()}|$text".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val cached = File(context.cacheDir, "pronunciation-$digest.mp3")
            if (!cached.exists()) {
                val connection = URL("${BuildConfig.SERVER_URL}/pronunciation").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 30_000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    val body = JSONObject()
                        .put("text", text)
                        .put("language", languageOverride ?: variant.language.label)
                        .put("region", variant.speechRegion)
                        .toString()
                    connection.outputStream.bufferedWriter().use { it.write(body) }
                    if (connection.responseCode !in 200..299) error("Speech helper returned ${connection.responseCode}")
                    cached.outputStream().use { output -> connection.inputStream.use { it.copyTo(output) } }
                } finally {
                    connection.disconnect()
                }
            }
            cached
        }
        return withContext(Dispatchers.Main) {
            runCatching {
                player?.release()
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(audioFile.absolutePath)
                    prepare()
                    start()
                }
                PronunciationState.Playing
            }.getOrElse { PronunciationState.Unavailable }
        }
    }
}
