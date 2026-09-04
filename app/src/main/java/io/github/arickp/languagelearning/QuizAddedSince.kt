package io.github.arickp.languagelearning

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

fun parseAddedOn(value: String?): LocalDate? =
    value?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

fun newestAddedOn(items: Iterable<QuizItem>): LocalDate? =
    items.mapNotNull { parseAddedOn(it.dateAdded) }.maxOrNull()

fun addedOnOrAfter(item: QuizItem, since: LocalDate): Boolean {
    val added = parseAddedOn(item.dateAdded) ?: return false
    return !added.isBefore(since)
}

fun relativeAddedAgo(newest: LocalDate?, today: LocalDate = LocalDate.now()): String? {
    if (newest == null) return null
    val days = ChronoUnit.DAYS.between(newest, today).toInt().coerceAtLeast(0)
    return when {
        days == 0 -> "today"
        days == 1 -> "1 day ago"
        days < 7 -> "$days days ago"
        else -> {
            val weeks = days / 7
            if (weeks == 1) "1 week ago" else "$weeks weeks ago"
        }
    }
}

fun formatAddedSinceLabel(since: LocalDate?): String =
    if (since == null) {
        "Any date"
    } else {
        since.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }

fun localDateFromUtcMillis(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

fun utcMillisFromLocalDate(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
