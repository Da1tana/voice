package ru.voiceassistant.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Все даты в базе — UTC epoch millis. Здесь — разбор дат от ассистента
 * и форматирование для отображения в локальной зоне.
 */
object DateTimeUtils {

    private val RU = Locale("ru", "RU")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", RU)
    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM", RU)
    private val dateYearFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
    private val dateTimeFormat = DateTimeFormatter.ofPattern("d MMMM, HH:mm", RU)

    /**
     * Разбирает дату от ассистента. Принимает:
     *  - 2026-10-31T18:00:00+03:00 (основной формат)
     *  - 2026-10-31T18:00:00Z
     *  - 2026-10-31T18:00 / 2026-10-31T18:00:00 — без зоны, считается в зоне устройства
     *  - 2026-10-31 — начало дня в зоне устройства
     * Возвращает epoch millis или null, если строка нераспознаваема.
     */
    fun parseToEpochMillis(value: String?, zone: ZoneId): Long? {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return null
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                ZonedDateTime.parse(s).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    try {
                        LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli()
                    } catch (_: DateTimeParseException) {
                        null
                    }
                }
            }
        }
    }

    fun toIsoOffset(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).truncatedTo(ChronoUnit.SECONDS)
            .toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun startOfDay(millis: Long, zone: ZoneId, plusDays: Long = 0): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().plusDays(plusDays)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** Полуинтервал [start, end) для периода, содержащего [millis]. */
    fun periodBounds(period: ExpensePeriodKind, millis: Long, zone: ZoneId): Pair<Long, Long> {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val (start, end) = when (period) {
            ExpensePeriodKind.DAY -> date to date.plusDays(1)
            ExpensePeriodKind.WEEK -> {
                val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                monday to monday.plusWeeks(1)
            }
            ExpensePeriodKind.MONTH -> date.withDayOfMonth(1) to date.withDayOfMonth(1).plusMonths(1)
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli() to
            end.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun formatTime(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(timeFormat)

    /** «сегодня, 18:00», «завтра, 09:30», «5 ноября, 12:00». */
    fun formatHuman(millis: Long, zone: ZoneId, nowMillis: Long): String {
        val target = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val time = target.format(timeFormat)
        return when (target.toLocalDate()) {
            today -> "сегодня, $time"
            today.plusDays(1) -> "завтра, $time"
            today.minusDays(1) -> "вчера, $time"
            else -> if (target.year == today.year) target.format(dateTimeFormat)
            else "${target.format(dateYearFormat)}, $time"
        }
    }

    fun formatDate(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(dateFormat)

    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }
}

enum class ExpensePeriodKind { DAY, WEEK, MONTH }
