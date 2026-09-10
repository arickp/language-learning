package io.github.arickp.languagelearning

import org.junit.Assert.*
import org.junit.Test

class SpellingProgressTest {
    @Test fun onlySubmittedSpellingConsumesAssistanceAcrossHistoryReloads() {
        var stored = emptySet<String>()
        fun history() = SpellingProgress({ stored }, { stored = it })
        val word = QuizItem("Meaning?", "connecting flight", QuizCategory.VOCABULARY,
            vocabularyTerm = "der Anschlussflug")
        val seen = setOf(word.vocabularyProgressKey()!!)
        val first = word.forPractice(false, seen, history().attemptedWords())
        assertTrue(first.assistedSpelling)
        assertTrue(stored.isEmpty()) // opening/reopening never writes
        history().recordSubmission(word) // multiple choice is not spelling
        assertTrue(stored.isEmpty())
        history().recordSubmission(first) // wrong or right submissions both count
        assertEquals(seen, stored)
        val later = word.forPractice(false, seen, history().attemptedWords())
        assertFalse(later.assistedSpelling)
        assertFalse(later.acceptsAnswer("Anschlussflug"))
        assertTrue(later.acceptsAnswer("der Anschlussflug"))
        assertTrue(later.prompt.contains("Type the article + noun"))
        history().recordSubmission(later)
        assertEquals(seen, stored) // repeated attempts must not toggle or erase progress
        val other = word.copy(vocabularyTerm = "das Haus")
        assertTrue(other.forPractice(false, setOf(other.vocabularyProgressKey()!!), stored).assistedSpelling)
        history().recordSubmission(other.forPractice(false, setOf(other.vocabularyProgressKey()!!), stored))
        assertEquals(seen + other.vocabularyProgressKey()!!, stored)
        assertFalse(word.forPractice(false, emptySet(), stored).spelling)
        assertFalse(word.forPractice(true, seen, stored).spelling)
    }
}
