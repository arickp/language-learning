package com.arick.languagelearning

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class QuizCategory(val label: String) {
    VOCABULARY("Vocabulary"), ARTICLES("Articles"), GRAMMAR("Grammar")
}

enum class Difficulty(val label: String, val description: String) {
    EASY("Easy", "Everyday basics and beginner-friendly words"),
    MEDIUM("Medium", "Useful conversation and travel vocabulary"),
    HARD("Hard", "Less common words, tricky forms, and specialist terms")
}

enum class Language(val label: String, val greeting: String) {
    GERMAN("German", "Deutsch"), FRENCH("French", "Français")
}

enum class LanguageVariant(
    val language: Language,
    val flag: String,
    val label: String,
    val speechRegion: String
) {
    GERMANY(Language.GERMAN, "🇩🇪", "German · Germany", "Germany"),
    AUSTRIA(Language.GERMAN, "🇦🇹", "German · Austria", "Austria"),
    SWISS_GERMAN(Language.GERMAN, "🇨🇭", "German · Switzerland", "German-speaking Switzerland"),
    FRANCE(Language.FRENCH, "🇫🇷", "French · France", "France"),
    CANADA(Language.FRENCH, "🇨🇦", "French · Canada / Québec", "Québec, Canada"),
    SWISS_FRENCH(Language.FRENCH, "🇨🇭", "French · Switzerland", "French-speaking Switzerland"),
    BELGIUM(Language.FRENCH, "🇧🇪", "French · Belgium", "French-speaking Belgium"),
    HAITI(Language.FRENCH, "🇭🇹", "French · Haiti", "Haiti"),
    DR_CONGO(Language.FRENCH, "🇨🇩", "French · DR Congo", "Democratic Republic of the Congo"),
    IVORY_COAST(Language.FRENCH, "🇨🇮", "French · Côte d’Ivoire", "Côte d’Ivoire"),
    SENEGAL(Language.FRENCH, "🇸🇳", "French · Senegal", "Senegal"),
    CAMEROON(Language.FRENCH, "🇨🇲", "French · Cameroon", "Cameroon"),
    MOROCCO(Language.FRENCH, "🇲🇦", "French · Morocco", "Morocco"),
    ALGERIA(Language.FRENCH, "🇩🇿", "French · Algeria", "Algeria"),
    LUXEMBOURG(Language.FRENCH, "🇱🇺", "French · Luxembourg", "Luxembourg")
}

data class QuizItem(
    val prompt: String,
    val answer: String,
    val category: QuizCategory,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val variant: String? = null,
    val spokenLanguage: String? = null,
    val explanation: String = "",
    val language: Language = Language.GERMAN,
    val translation: String? = null,
    val hints: List<String> = emptyList(),
    val spokenText: String? = null
)

object QuizData {
    var items: List<QuizItem> = emptyList()
        private set

    suspend fun load(context: Context): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val cache = File(context.filesDir, "quiz-data-cache.json")
            var refreshed = false
            val json = runCatching {
                require(BuildConfig.SERVER_URL.isNotBlank()) { "SERVER_URL is not configured" }
                val connection = URL("${BuildConfig.SERVER_URL}/api/quiz-data").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 20_000
                    if (connection.responseCode !in 200..299) error("Server returned ${connection.responseCode}")
                    connection.inputStream.bufferedReader().use { it.readText() }.also {
                        cache.writeText(it)
                        refreshed = true
                    }
                } finally { connection.disconnect() }
            }.getOrElse {
                if (cache.exists()) cache.readText() else error("The word bank is unavailable. Start the companion server and try again.")
            }
            parse(json)
            refreshed
        }
    }

    private fun parse(json: String) {
        val root = JSONObject(json)
        val loaded = mutableListOf<QuizItem>()
        val vocabulary = root.getJSONArray("vocabulary")

        for (index in 0 until vocabulary.length()) {
            val entry = vocabulary.getJSONObject(index)
            val language = Language.valueOf(entry.optString("language", "GERMAN"))
            val term = entry.getString("term")
            val translation = entry.getString("translation")
            val difficulty = runCatching {
                Difficulty.valueOf(entry.optString("difficulty", "MEDIUM"))
            }.getOrDefault(Difficulty.MEDIUM)
            loaded += QuizItem(
                prompt = "What does “$term” mean?",
                answer = translation,
                category = QuizCategory.VOCABULARY,
                difficulty = difficulty,
                variant = entry.optString("variant").ifBlank { null },
                spokenLanguage = entry.optString("spokenLanguage").ifBlank { null },
                explanation = "$term = $translation",
                language = language,
                spokenText = term
            )

            val article = entry.optString("article")
            val noun = entry.optString("noun")
            if (article.isNotBlank() && noun.isNotBlank()) {
                loaded += QuizItem(
                    prompt = "Choose the article: ___ $noun",
                    answer = article,
                    category = QuizCategory.ARTICLES,
                    difficulty = difficulty,
                    variant = entry.optString("variant").ifBlank { null },
                    spokenLanguage = entry.optString("spokenLanguage").ifBlank { null },
                    explanation = "$noun uses the article $article: $article $noun.",
                    language = language,
                    translation = translation,
                    spokenText = "$article $noun"
                )
            }
        }

        val questions = root.getJSONArray("questions")
        for (index in 0 until questions.length()) {
            val entry = questions.getJSONObject(index)
            val hintsJson = entry.optJSONArray("hints")
            val hints = buildList {
                if (hintsJson != null) {
                    for (hintIndex in 0 until hintsJson.length()) add(hintsJson.getString(hintIndex))
                }
            }
            loaded += QuizItem(
                prompt = entry.getString("prompt"),
                answer = entry.getString("answer"),
                category = QuizCategory.valueOf(entry.getString("category")),
                difficulty = runCatching {
                    Difficulty.valueOf(entry.optString("difficulty", "MEDIUM"))
                }.getOrDefault(Difficulty.MEDIUM),
                explanation = entry.optString("explanation"),
                language = Language.valueOf(entry.optString("language", "GERMAN")),
                translation = entry.optString("translation").ifBlank { null },
                hints = hints,
                spokenText = entry.optString("spokenText").ifBlank { null }
            )
        }

        items = loaded.distinctBy { "${it.language}|${it.category}|${it.prompt}|${it.answer}" }
    }
}
