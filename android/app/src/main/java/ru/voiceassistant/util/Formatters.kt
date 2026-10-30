package ru.voiceassistant.util

import java.text.NumberFormat
import java.util.Locale

object Formatters {
    private val money: NumberFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }

    /** Суммы храним в копейках, чтобы не терять точность на Double. */
    fun rubles(kopecks: Long): String = money.format(kopecks / 100.0) + " ₽"

    fun toKopecks(amount: Double): Long = Math.round(amount * 100)

    fun parseTags(raw: String?): List<String> =
        raw.orEmpty().split(',', ';', ' ', '#')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun joinTags(tags: List<String>): String = tags.joinToString(",")
}

object Validation {
    const val MAX_TITLE = 200
    const val MAX_TEXT = 10_000
    const val MAX_AMOUNT_RUB = 10_000_000.0

    fun isValidBackendUrl(url: String): Boolean {
        val u = url.trim()
        return (u.startsWith("https://") || u.startsWith("http://")) && u.length > 10 && !u.contains(' ')
    }
}
