package io.github.arickp.languagelearning

import org.junit.Assert.assertEquals
import org.junit.Test

class TripItineraryPairingTest {
    @Test
    fun submittedPhoneItineraryStartsQuizImmediately() {
        assertEquals(true, shouldAutoStartTripQuiz("Paris for three nights"))
        assertEquals(false, shouldAutoStartTripQuiz("   "))
    }

    @Test
    fun tvTripEntryHidesRemoteInputByDefault() {
        assertEquals(false, showRemoteItineraryInputByDefault(isTv = true))
    }

    @Test
    fun nonTvTripEntryShowsManualInputByDefault() {
        assertEquals(true, showRemoteItineraryInputByDefault(isTv = false))
    }

    @Test
    fun tvQuizUsesSeparateQuestionAndFeedbackPanes() {
        assertEquals(2, quizPaneCount(isTv = true))
        assertEquals(1, quizPaneCount(isTv = false))
    }

    @Test
    fun tvQuizInitiallyFocusesFirstAnswer() {
        assertEquals(0, initialQuizAnswerIndex(isTv = true, optionCount = 4))
        assertEquals(null, initialQuizAnswerIndex(isTv = false, optionCount = 4))
        assertEquals(null, initialQuizAnswerIndex(isTv = true, optionCount = 0))
    }

    @Test
    fun phoneQuizRevealsFeedbackAfterAnswering() {
        assertEquals(true, shouldAutoRevealQuizFeedback(isTv = false, hasAnswer = true))
        assertEquals(false, shouldAutoRevealQuizFeedback(isTv = false, hasAnswer = false))
        assertEquals(false, shouldAutoRevealQuizFeedback(isTv = true, hasAnswer = true))
    }

    @Test
    fun formUrlNormalizesTheServerSlash() {
        assertEquals(
            "https://example.com/trip/itinerary/opaque-token",
            TripItineraryPairing.formUrl("https://example.com/", "opaque-token")
        )
    }
}
