package io.github.arickp.languagelearning

import java.text.Normalizer
import java.util.Locale

fun QuizItem.vocabularyProgressKey(): String? = vocabularyTerm?.let {
    "${language.name}|${variant.orEmpty()}|${spokenLanguage.orEmpty()}|$it"
}

fun QuizItem.forPractice(
    isTv: Boolean, seenVocabulary: Set<String>, spellingAttempts: Set<String> = emptySet()
): QuizItem {
    val term = vocabularyTerm ?: return this
    if (isTv || category != QuizCategory.VOCABULARY || vocabularyProgressKey() !in seenVocabulary ||
        term.contains('+') || term.contains('…')) return this
    val practice = copy(
        prompt = "Spell the ${language.label} word or phrase for: $answer",
        answer = term,
        spelling = true,
        assistedSpelling = vocabularyProgressKey() !in spellingAttempts,
        hints = emptyList(),
        translation = null
    )
    val article = practice.spellingArticle() ?: return practice
    val instruction = if (practice.assistedSpelling) "Type the noun only; the article is provided."
        else "Type the article + noun."
    return practice.copy(prompt = "${practice.prompt}\n${article.description}\n$instruction")
}

fun QuizItem.requiresTypedAnswer(isTv: Boolean): Boolean =
    !isTv && (category != QuizCategory.VOCABULARY || spelling)

/** Ignore typography and capitalization, but keep accents and grammatical endings meaningful. */
fun normalizeQuizAnswer(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
    .replace('’', '\'').replace('‘', '\'')
    .trim().trimEnd('.', '!', '?').trim()
    .replace(Regex("\\s+"), " ")
    .lowercase(Locale.ROOT)

data class SpellingArticle(val article: String, val noun: String, val description: String)

/** Only recognize standalone articles, never word prefixes or article-only answers. */
fun QuizItem.spellingArticle(): SpellingArticle? {
    if (!spelling) return null
    val term = answer.replace('’', '\'').replace('‘', '\'').trim()
    val pattern = when (language) {
        Language.GERMAN -> "^(der|die|das|ein|eine)\\s+(.+)$"
        Language.FRENCH -> "^(le|la|les|un|une|des)\\s+(.+)$|^(l')\\s*(.+)$"
    }
    val match = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(term) ?: return null
    val article = match.groupValues[1].ifEmpty { match.groupValues[3] }
    val noun = match.groupValues[2].ifEmpty { match.groupValues[4] }.trim()
    val normalizedArticle = article.lowercase(Locale.ROOT)
    val indefinite = normalizedArticle in setOf("ein", "eine", "un", "une", "des")
    val number = when {
        normalizedArticle in setOf("les", "des") -> "plural"
        normalizedArticle in setOf("ein", "eine", "un", "une", "das", "le", "la") -> "singular"
        else -> null // die, der and elided l' do not supply reliable number here.
    }
    val description = when {
        normalizedArticle == "des" -> "Indefinite article (some; plural of a/an)"
        indefinite -> "Indefinite article (a/an)"
        else -> "Definite article (the)"
    } + (number?.let { " · $it" } ?: "")
    return noun.takeIf { it.isNotEmpty() }?.let { SpellingArticle(article, it, description) }
}

fun QuizItem.acceptsAnswer(value: String): Boolean {
    val normalized = normalizeQuizAnswer(value)
    if (normalized.isEmpty()) return false
    val article = spellingArticle()
    if (article != null) {
        if (normalized == normalizeQuizAnswer(answer)) return true
        return answer.split(" / ").any { alternative ->
            val parsed = copy(answer = alternative).spellingArticle()
            val noun = parsed?.noun ?: alternative
            val full = if (parsed != null) alternative else
                article.article + (if (article.article.endsWith("'")) "" else " ") + noun
            normalized == normalizeQuizAnswer(full) ||
                (assistedSpelling && normalized == normalizeQuizAnswer(noun))
        }
    }
    return normalized == normalizeQuizAnswer(answer) ||
        answer.split(" / ").any { normalizeQuizAnswer(it) == normalized }
}
