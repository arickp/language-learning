package io.github.arickp.languagelearning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeneratedTripQuiz(
    val title: String,
    val questions: List<QuizItem>
)

object TripQuizGenerator {
    val isConfigured: Boolean get() = BuildConfig.SERVER_URL.isNotBlank()

    suspend fun generate(
        source: String,
        variant: LanguageVariant,
        questionCount: Int,
        allowExplicit: Boolean
    ): Result<GeneratedTripQuiz> = withContext(Dispatchers.IO) {
        runCatching {
            require(isConfigured) { "SERVER_URL is not configured" }
            require(source.isNotBlank()) { "Paste an itinerary or public link first." }
            val connection =
                URL("${BuildConfig.SERVER_URL}/trip/quiz").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 100_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val request = JSONObject()
                    .put("source", source.trim())
                    .put("language", variant.language.name)
                    .put("region", variant.speechRegion)
                    .put("question_count", questionCount)
                    .put("allow_explicit", allowExplicit)
                connection.outputStream.bufferedWriter().use { it.write(request.toString()) }

                val status = connection.responseCode
                if (status !in 200..299) {
                    val message = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?.trim()
                        .orEmpty()
                    error(message.ifEmpty { "The helper returned HTTP $status" })
                }
                val response =
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val questionsJson = response.getJSONArray("questions")
                val questions = buildList {
                    for (index in 0 until questionsJson.length()) {
                        val item = questionsJson.getJSONObject(index)
                        val term = item.getString("term")
                        val translation = item.getString("translation")
                        val hintsJson = item.optJSONArray("hints")
                        val hints = buildList {
                            if (hintsJson != null) {
                                for (hintIndex in 0 until hintsJson.length()) {
                                    add(hintsJson.getString(hintIndex))
                                }
                            }
                        }
                        val distractorsJson = item.optJSONArray("distractors")
                        val distractors = buildList {
                            if (distractorsJson != null) {
                                for (decoyIndex in 0 until distractorsJson.length()) {
                                    val decoy = distractorsJson.getString(decoyIndex).trim()
                                    if (decoy.isNotEmpty() && !decoy.equals(translation, ignoreCase = true)) {
                                        add(decoy)
                                    }
                                }
                            }
                        }.distinct()
                        add(
                            QuizItem(
                                prompt = "What does “$term” mean?",
                                answer = translation,
                                category = QuizCategory.VOCABULARY,
                                difficulty = runCatching {
                                    Difficulty.valueOf(item.optString("difficulty", "MEDIUM"))
                                }.getOrDefault(Difficulty.MEDIUM),
                                explanation = item.optString(
                                    "explanation",
                                    "$term = $translation"
                                ),
                                language = variant.language,
                                hints = hints,
                                spokenText = term,
                                explicit = item.optBoolean("explicit", false),
                                emoji = item.optString("emoji").trim().ifEmpty { null },
                                distractors = distractors
                            )
                        )
                    }
                }
                check(questions.size == questionCount) {
                    "The helper returned ${questions.size} of $questionCount requested questions."
                }
                GeneratedTripQuiz(
                    title = response.optString("title", "Trip quiz"),
                    questions = questions
                )
            } finally {
                connection.disconnect()
            }
        }
    }
}
