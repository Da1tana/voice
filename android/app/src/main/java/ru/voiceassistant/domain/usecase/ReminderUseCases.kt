package ru.voiceassistant.domain.usecase

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import ru.voiceassistant.data.repository.ExpenseRepository
import ru.voiceassistant.data.repository.ReminderRepository
import ru.voiceassistant.domain.model.TaskFilter
import ru.voiceassistant.domain.model.ToolResult
import ru.voiceassistant.service.AlarmScheduler
import ru.voiceassistant.util.DateTimeUtils
import ru.voiceassistant.util.ExpensePeriodKind
import ru.voiceassistant.util.TimeProvider
import ru.voiceassistant.util.Validation
import javax.inject.Inject

data class ReminderCreated(val id: Long, val exact: Boolean)

class SetReminderUseCase @Inject constructor(
    private val reminders: ReminderRepository,
    private val scheduler: AlarmScheduler,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(text: String, triggerAt: Long): ReminderCreated {
        val t = text.trim()
        if (t.isEmpty()) throw ToolArgumentException("Не указано, о чём напомнить")
        val now = time.nowMillis()
        // Небольшой допуск: модель могла округлить «через минуту» до текущей минуты.
        if (triggerAt < now - PAST_TOLERANCE_MS) throw ToolArgumentException("Это время уже прошло")
        val at = maxOf(triggerAt, now + MIN_DELAY_MS)
        val id = reminders.add(t.take(Validation.MAX_TITLE), at, now)
        val exact = scheduler.scheduleReminder(id, t, at)
        return ReminderCreated(id, exact)
    }

    suspend fun fromTool(args: JsonObject): ToolResult {
        val text = args.str("text") ?: throw ToolArgumentException("Не указано, о чём напомнить")
        val whenRaw = args.str("when") ?: throw ToolArgumentException("Не указано время напоминания")
        val at = DateTimeUtils.parseToEpochMillis(whenRaw, time.zone())
            ?: throw ToolArgumentException("Не удалось разобрать время: $whenRaw")
        val created = invoke(text, at)
        return ToolResult.Success("set_reminder", buildJsonObject {
            put("reminder_id", created.id)
            put("when", DateTimeUtils.toIsoOffset(at, time.zone()))
            put("exact", created.exact)
            if (!created.exact) put("note", "Точные будильники не разрешены, напоминание может прийти с задержкой")
        })
    }

    private companion object {
        const val PAST_TOLERANCE_MS = 2 * 60_000L
        const val MIN_DELAY_MS = 5_000L
    }
}

class StartTimerUseCase @Inject constructor(
    private val scheduler: AlarmScheduler,
    private val time: TimeProvider,
) {
    fun fromTool(args: JsonObject): ToolResult {
        val seconds = args.long("duration_seconds") ?: throw ToolArgumentException("Не указана длительность таймера")
        if (seconds !in 1..MAX_SECONDS) throw ToolArgumentException("Таймер можно поставить от секунды до суток")
        val label = args.str("label").orEmpty()
        val now = time.nowMillis()
        val timerId = ((now / 1000) % 100_000).toInt()
        val exact = scheduler.scheduleTimer(timerId, label, now + seconds * 1000)
        return ToolResult.Success("start_timer", buildJsonObject {
            put("duration_seconds", seconds)
            put("ends_at", DateTimeUtils.toIsoOffset(now + seconds * 1000, time.zone()))
            put("exact", exact)
        })
    }

    private companion object {
        const val MAX_SECONDS = 86_400L
    }
}

class GetTodaySummaryUseCase @Inject constructor(
    private val listTasks: ListTasksUseCase,
    private val reminders: ReminderRepository,
    private val expenses: ExpenseRepository,
    private val time: TimeProvider,
) {
    suspend fun fromTool(@Suppress("UNUSED_PARAMETER") args: JsonObject): ToolResult {
        val now = time.nowMillis()
        val zone = time.zone()
        val tasks = listTasks(TaskFilter.TODAY)
        val endOfDay = DateTimeUtils.startOfDay(now, zone, 1)
        val upcoming = reminders.getUpcoming(now, 10).filter { it.triggerAt < endOfDay }
        val (from, to) = DateTimeUtils.periodBounds(ExpensePeriodKind.DAY, now, zone)
        val spent = expenses.sumByCategory(from, to).sumOf { it.totalKopecks }
        return ToolResult.Success("get_today_summary", buildJsonObject {
            put("now", DateTimeUtils.toIsoOffset(now, zone))
            put("tasks_count", tasks.size)
            putJsonArray("tasks") {
                tasks.take(10).forEach { t ->
                    addJsonObject {
                        put("task_id", t.id)
                        put("title", t.title)
                        t.dueAt?.let {
                            put("due_at", DateTimeUtils.toIsoOffset(it, zone))
                            put("overdue", it < now)
                        }
                    }
                }
            }
            putJsonArray("reminders_today") {
                upcoming.forEach { r ->
                    addJsonObject {
                        put("text", r.text)
                        put("when", DateTimeUtils.toIsoOffset(r.triggerAt, zone))
                    }
                }
            }
            put("spent_today_rub", spent / 100.0)
        })
    }
}
