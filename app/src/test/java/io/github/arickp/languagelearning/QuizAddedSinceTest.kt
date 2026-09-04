package io.github.arickp.languagelearning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class QuizAddedSinceTest {
    private val older = QuizItem(
        prompt = "old",
        answer = "old",
        category = QuizCategory.VOCABULARY,
        dateAdded = "2026-08-01"
    )
    private val newer = QuizItem(
        prompt = "new",
        answer = "new",
        category = QuizCategory.VOCABULARY,
        dateAdded = "2026-09-03"
    )
    private val undated = QuizItem(
        prompt = "undated",
        answer = "undated",
        category = QuizCategory.GRAMMAR
    )

    @Test
    fun newestDateIsTheLatestIsoDay() {
        assertEquals(LocalDate.parse("2026-09-03"), newestAddedOn(listOf(older, newer, undated)))
    }

    @Test
    fun sinceFilterKeepsDatedItemsOnOrAfterCutoff() {
        val since = LocalDate.parse("2026-09-02")
        assertFalse(addedOnOrAfter(older, since))
        assertTrue(addedOnOrAfter(newer, since))
        assertFalse(addedOnOrAfter(undated, since))
    }

    @Test
    fun relativeLabelUsesDaysThenWeeks() {
        val today = LocalDate.parse("2026-09-03")
        assertEquals("today", relativeAddedAgo(today, today))
        assertEquals("1 day ago", relativeAddedAgo(today.minusDays(1), today))
        assertEquals("5 days ago", relativeAddedAgo(today.minusDays(5), today))
        assertEquals("1 week ago", relativeAddedAgo(today.minusDays(7), today))
        assertEquals("1 week ago", relativeAddedAgo(today.minusDays(13), today))
        assertEquals("2 weeks ago", relativeAddedAgo(today.minusDays(14), today))
    }
}
