package io.github.arickp.languagelearning

/** Persistent first-submission history, deliberately separate from vocabulary encounters. */
class SpellingProgress(
    private val read: () -> Set<String>,
    private val write: (Set<String>) -> Unit
) {
    fun attemptedWords(): Set<String> = read().toSet()

    fun recordSubmission(item: QuizItem) {
        if (!item.spelling || item.category != QuizCategory.VOCABULARY) return
        val key = item.vocabularyProgressKey() ?: return
        val previous = attemptedWords()
        if (key !in previous) write(previous + key)
    }
}
