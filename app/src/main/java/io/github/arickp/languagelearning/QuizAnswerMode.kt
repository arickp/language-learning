package io.github.arickp.languagelearning

import java.text.Normalizer
import java.util.Locale

fun QuizItem.vocabularyProgressKey(): String? = vocabularyTerm?.let {
    "${language.name}|${variant.orEmpty()}|${spokenLanguage.orEmpty()}|$it"
}

fun QuizItem.forPractice(isTv: Boolean, seenVocabulary: Set<String>): QuizItem {
    val term = vocabularyTerm ?: return this
    if (isTv || category != QuizCategory.VOCABULARY || vocabularyProgressKey() !in seenVocabulary ||
        term.contains('+') || term.contains('…')) return this
    return copy(
        prompt = "Spell the ${language.label} word or phrase for: $answer",
        answer = term,
        spelling = true,
        hints = emptyList(),
        translation = null
    )
}

fun QuizItem.requiresTypedAnswer(isTv: Boolean): Boolean =
    !isTv && (category != QuizCategory.VOCABULARY || spelling)

/** Ignore typography and capitalization, but keep accents and grammatical endings meaningful. */
fun normalizeQuizAnswer(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
    .replace('’', '\'').replace('‘', '\'')
    .trim().trimEnd('.', '!', '?').trim()
    .replace(Regex("\\s+"), " ")
    .lowercase(Locale.ROOT)

fun QuizItem.acceptsAnswer(value: String): Boolean {
    val normalized = normalizeQuizAnswer(value)
    if (normalized.isEmpty()) return false
    return normalized == normalizeQuizAnswer(answer) ||
        answer.split(" / ").any { normalizeQuizAnswer(it) == normalized }
}
