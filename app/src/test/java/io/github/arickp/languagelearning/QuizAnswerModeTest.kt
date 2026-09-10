package io.github.arickp.languagelearning

import org.junit.Assert.*
import org.junit.Test

class QuizAnswerModeTest {
    @Test fun formalPossessiveMeaningAcceptsYourWithoutRegisterLabel() {
        val item = QuizItem("In “Ist das Ihr Pass?,” Ihr means…", "your (formal)",
            QuizCategory.GRAMMAR)
        assertTrue(item.acceptsAnswer("your"))
        assertTrue(item.acceptsAnswer(" YOUR. "))
        assertTrue(item.acceptsAnswer("your (formal)"))
        assertEquals("your (formal)", item.answer)
        assertFalse(item.acceptsAnswer("you"))
        assertFalse(item.acceptsAnswer("you (formal)"))
        assertFalse(item.copy(answer = "you (formal)").acceptsAnswer("your"))
        assertFalse(item.copy(spelling = true).acceptsAnswer("your"))
        assertFalse(item.copy(category = QuizCategory.ARTICLES).acceptsAnswer("your"))
        assertTrue(item.copy(category = QuizCategory.VOCABULARY).acceptsAnswer("your"))
    }

    @Test fun firstSpellingAcceptsNounWithoutArticleAndKeepsFullFeedbackAnswer() {
        val noun = QuizItem("Meaning?", "connecting flight", QuizCategory.VOCABULARY,
            vocabularyTerm = "der Anschlussflug")
        val spelling = noun.forPractice(false, setOf(noun.vocabularyProgressKey()!!))
        assertTrue(spelling.acceptsAnswer("Anschlussflug"))
        assertEquals("der Anschlussflug", spelling.answer)
        assertFalse(spelling.acceptsAnswer("die Anschlussflug"))
    }

    @Test fun frenchAndGermanArticlesSupportNounOnlyOnAssistedSpelling() {
        for ((language, term, noun) in listOf(
            Triple(Language.FRENCH, "l’avion", "avion"),
            Triple(Language.FRENCH, "les avions", "avions"),
            Triple(Language.FRENCH, "un avion", "avion"),
            Triple(Language.FRENCH, "une gare", "gare"),
            Triple(Language.GERMAN, "ein Flug", "Flug"),
            Triple(Language.GERMAN, "eine Reise", "Reise"),
            Triple(Language.GERMAN, "die Flüge", "Flüge"),
            Triple(Language.GERMAN, "das Haus", "Haus")
        )) {
            val item = QuizItem("Meaning?", "translation", QuizCategory.VOCABULARY,
                language = language, vocabularyTerm = term)
            val spelling = item.forPractice(false, setOf(item.vocabularyProgressKey()!!))
            assertTrue(term, spelling.acceptsAnswer(noun))
            assertFalse(term, spelling.copy(assistedSpelling = false).acceptsAnswer(noun))
            assertTrue(spelling.copy(assistedSpelling = false).acceptsAnswer(term))
        }
    }

    @Test fun spellingPromptExplainsArticleDefinitenessAndOnlyReliableNumber() {
        for ((language, term, description) in listOf(
            Triple(Language.GERMAN, "der Anschlussflug", "Definite article (the)"),
            Triple(Language.GERMAN, "die Flüge", "Definite article (the)"),
            Triple(Language.GERMAN, "ein Flug", "Indefinite article (a/an) · singular"),
            Triple(Language.FRENCH, "l'avion", "Definite article (the)"),
            Triple(Language.FRENCH, "les avions", "Definite article (the) · plural"),
            Triple(Language.FRENCH, "une gare", "Indefinite article (a/an) · singular"),
            Triple(Language.FRENCH, "des avions", "Indefinite article (some; plural of a/an) · plural")
        )) {
            val item = QuizItem("Meaning?", "translation", QuizCategory.VOCABULARY,
                language = language, vocabularyTerm = term)
            val spelling = item.forPractice(false, setOf(item.vocabularyProgressKey()!!))
            assertTrue(term, spelling.prompt.contains(description))
            if (term.startsWith("die ") || term.startsWith("l'")) {
                assertFalse(spelling.prompt.contains("singular"))
                assertFalse(spelling.prompt.contains("plural"))
            }
        }
    }

    @Test fun nounAlternativesStillRequireArticleOnLaterSpelling() {
        val item = QuizItem("Meaning?", "newspaper", QuizCategory.VOCABULARY,
            language = Language.FRENCH, vocabularyTerm = "le journal / quotidien")
        val seen = setOf(item.vocabularyProgressKey()!!)
        val first = item.forPractice(false, seen)
        assertTrue(first.acceptsAnswer("quotidien"))
        val later = item.forPractice(false, seen, seen)
        assertFalse(later.acceptsAnswer("quotidien"))
        assertTrue(later.acceptsAnswer("le quotidien"))
    }

    @Test fun verbsAndUnrecognizedPrefixesNeverGetArticleAssistance() {
        for (term in listOf("répéter", "lire", "lernen", "einkaufen")) {
            val item = QuizItem("Meaning?", "translation", QuizCategory.VOCABULARY,
                vocabularyTerm = term)
            val spelling = item.forPractice(false, setOf(item.vocabularyProgressKey()!!))
            assertNull(spelling.spellingArticle())
            assertTrue(spelling.acceptsAnswer(term))
            assertFalse(spelling.prompt.contains("article"))
        }
    }

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
