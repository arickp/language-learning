package io.github.arickp.languagelearning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class ExampleSource(val apiName: String, val label: String, val icon: String) {
    REDDIT("reddit", "Reddit", "🌐"),
    BLUESKY("bluesky", "Bluesky", "🦋"),
    GUTEFRAGE("gutefrage", "gutefrage", "💬"),
    JEUXVIDEO("jeuxvideo", "Jeuxvideo.com forums", "🎮"),
    DER_SPIEGEL("der_spiegel", "Der Spiegel", "📰"),
    RADIO_CANADA("radio_canada", "Radio-Canada", "🍁"),
    LE_MONDE("le_monde", "Le Monde", "🗞️");

    companion object {
        fun forLanguage(language: Language): List<ExampleSource> = buildList {
            add(REDDIT)
            add(BLUESKY)
            if (language == Language.GERMAN) {
                add(GUTEFRAGE)
                add(DER_SPIEGEL)
            }
            if (language == Language.FRENCH) {
                add(JEUXVIDEO)
                add(RADIO_CANADA)
                add(LE_MONDE)
            }
        }
    }
}

data class ExampleResult(
    val title: String,
    val url: String,
    val summary: String,
    val nsfw: Boolean,
    val source: String
)

sealed interface ExampleDiscoveryState {
    data object Idle : ExampleDiscoveryState
    data object Loading : ExampleDiscoveryState
    data class Found(val result: ExampleResult) : ExampleDiscoveryState
    data object Unavailable : ExampleDiscoveryState
}

object ExampleDiscovery {
    val isConfigured: Boolean get() = BuildConfig.SERVER_URL.isNotBlank()

    suspend fun find(item: QuizItem, source: ExampleSource, allowExplicit: Boolean): ExampleDiscoveryState = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext ExampleDiscoveryState.Idle
        runCatching {
            val connection = URL("${BuildConfig.SERVER_URL}/example").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 8_000
                connection.readTimeout = 25_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject()
                    .put("term", item.exampleSearchPhrase())
                    .put("answer", item.answer)
                    .put("language", item.language.label)
                    .put("source", source.apiName)
                    .put("allow_explicit", allowExplicit)
                    .toString()
                connection.outputStream.bufferedWriter().use { it.write(body) }
                if (connection.responseCode !in 200..299) error("Helper returned ${connection.responseCode}")
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                ExampleDiscoveryState.Found(
                    ExampleResult(
                        title = json.getString("title"),
                        url = json.getString("url"),
                        summary = json.optString("summary"),
                        nsfw = json.optBoolean("nsfw", false),
                        source = json.optString("source", source.label)
                    )
                )
            } finally {
                connection.disconnect()
            }
        }.getOrElse { ExampleDiscoveryState.Unavailable }
    }
}

/** Prefer the spoken word/phrase over quiz-prompt wording like “What does … mean?”. */
private fun QuizItem.exampleSearchPhrase(): String {
    val raw = when {
        !spokenText.isNullOrBlank() -> spokenText
        else -> {
            Regex("""What does [“"«](.+?)[”"»] mean\?""").find(prompt)?.groupValues?.getOrNull(1)
                ?: Regex("""Choose the article:\s*___\s*(.+)""").find(prompt)?.groupValues?.getOrNull(1)
                ?: if ("___" in prompt) {
                    prompt.substringBefore('(').trim().replace("___", answer)
                } else {
                    prompt
                }
        }
    }
    // Bare nouns/verbs match real posts far more often than “der/die/das …” dictionary forms.
    return raw.stripLeadingArticle()
}

private fun String.stripLeadingArticle(): String {
    val stripped = trim().replace(
        Regex(
            """^(?:der|die|das|den|dem|des|ein|eine|einer|einem|einen|le|la|les|l'|un|une|des|du)\s+""",
            RegexOption.IGNORE_CASE
        ),
        ""
    ).trim()
    return stripped.ifEmpty { trim() }
}
