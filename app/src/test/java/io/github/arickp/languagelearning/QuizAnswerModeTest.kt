package io.github.arickp.languagelearning

import org.junit.Assert.*
import org.junit.Test

class QuizAnswerModeTest {
    private val word = QuizItem(
        prompt = "What does “doux / douce” mean?", answer = "soft / gentle",
        category = QuizCategory.VOCABULARY, language = Language.FRENCH,
        vocabularyTerm = "doux / douce", spokenText = "doux / douce",
        exampleSentence = "J'adore tes lèvres douces."
    )

    @Test fun firstEncounterIsChoiceThenLaterEncounterIsSpelling() {
        assertFalse(word.forPractice(false, emptySet()).requiresTypedAnswer(false))
        val spelling = word.forPractice(false, setOf(word.vocabularyProgressKey()!!))
        assertTrue(spelling.requiresTypedAnswer(false))
        assertEquals("doux / douce", spelling.answer)
        assertTrue(spelling.prompt.contains("soft / gentle"))
        assertFalse(spelling.prompt.contains("douce"))
        assertEquals(word.exampleSentence, spelling.exampleSentence)
        assertTrue(spelling.acceptsAnswer(" Douce "))
        assertTrue(spelling.acceptsAnswer("doux"))
        assertFalse(spelling.acceptsAnswer("soft"))
    }

    @Test fun televisionAlwaysKeepsPicker() {
        val seen = setOf(word.vocabularyProgressKey()!!)
        assertFalse(word.forPractice(true, seen).spelling)
        for (category in QuizCategory.entries) {
            assertFalse(word.copy(category = category).requiresTypedAnswer(true))
        }
    }

    @Test fun grammarAndArticlesRequireTypingOnHandhelds() {
        assertTrue(word.copy(category = QuizCategory.GRAMMAR).requiresTypedAnswer(false))
        assertTrue(word.copy(category = QuizCategory.ARTICLES).requiresTypedAnswer(false))
    }

    @Test fun typographyIsToleratedButAccentsAndEndingsAreRequired() {
        val article = word.copy(answer = "l'", spelling = false)
        assertTrue(article.acceptsAnswer(" L’ "))
        assertFalse(article.acceptsAnswer("la"))
        assertFalse(article.acceptsAnswer(" "))
        val verb = word.copy(answer = "répéter", spelling = true)
        assertTrue(verb.acceptsAnswer("RE\u0301PE\u0301TER"))
        assertFalse(verb.acceptsAnswer("repeter"))
        assertFalse(word.copy(answer = "deinem").acceptsAnswer("deinen"))
        assertTrue(word.copy(answer = "her / their", category = QuizCategory.GRAMMAR).acceptsAnswer("their"))
    }

    @Test fun progressSeparatesLanguagesAndRegionsAndSkipsUnknownTargets() {
        val seen = setOf(word.vocabularyProgressKey()!!)
        assertFalse(word.copy(language = Language.GERMAN).forPractice(false, seen).spelling)
        assertFalse(word.copy(variant = "CANADA").forPractice(false, seen).spelling)
        assertFalse(word.copy(vocabularyTerm = null).forPractice(false, seen).spelling)
    }
}
