package ru.voiceassistant.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

/** Источник текущего времени. Подменяется в тестах. */
interface TimeProvider {
    fun nowMillis(): Long
    fun zone(): ZoneId
    fun now(): ZonedDateTime = Instant.ofEpochMilli(nowMillis()).atZone(zone())
}

class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
