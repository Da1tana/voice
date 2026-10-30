package ru.voiceassistant

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.voiceassistant.domain.model.ToolResult
import ru.voiceassistant.domain.usecase.AddExpenseUseCase
import ru.voiceassistant.domain.usecase.AddNoteUseCase
import ru.voiceassistant.domain.usecase.AddTaskUseCase
import ru.voiceassistant.domain.usecase.CompleteTaskUseCase
import ru.voiceassistant.domain.usecase.GetExpenseSummaryUseCase
import ru.voiceassistant.domain.usecase.GetTodaySummaryUseCase
import ru.voiceassistant.domain.usecase.ListTasksUseCase
import ru.voiceassistant.domain.usecase.SearchNotesUseCase
import ru.voiceassistant.domain.usecase.SetReminderUseCase
import ru.voiceassistant.domain.usecase.StartTimerUseCase
import ru.voiceassistant.domain.usecase.ToolExecutor
import java.time.ZoneId
import java.time.ZonedDateTime

class UseCaseTest {
    private lateinit var time: FixedTime
    private lateinit var tasks: FakeTasks
    private lateinit var notes: FakeNotes
    private lateinit var expenses: FakeExpenses
    private lateinit var reminders: FakeReminders
    private lateinit var scheduler: FakeScheduler
    private lateinit var executor: ToolExecutor

    private val msk = ZoneId.of("Europe/Moscow")

    @Before
    fun setUp() {
        time = FixedTime()
        tasks = FakeTasks()
        notes = FakeNotes()
        expenses = FakeExpenses()
        reminders = FakeReminders()
        scheduler = FakeScheduler()
        val listTasks = ListTasksUseCase(tasks, time)
        executor = ToolExecutor(
            AddTaskUseCase(tasks, time),
            CompleteTaskUseCase(tasks, time),
            listTasks,
            SetReminderUseCase(reminders, scheduler, time),
            AddNoteUseCase(notes, time),
            SearchNotesUseCase(notes, time),
            AddExpenseUseCase(expenses, time),
            GetExpenseSummaryUseCase(expenses, time),
            StartTimerUseCase(scheduler, time),
            GetTodaySummaryUseCase(listTasks, reminders, expenses, time),
        )
    }

    private fun args(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    })

    @Test
    fun addTask_parsesDueDateWithOffset() = runTest {
        val r = executor.execute("add_task", args("title" to "Купить хлеб", "due_at" to "2026-10-31T18:00:00+03:00"))
        assertTrue(r is ToolResult.Success)
        val task = tasks.items.value.single()
        assertEquals("Купить хлеб", task.title)
        assertEquals(ZonedDateTime.of(2026, 10, 31, 18, 0, 0, 0, msk).toInstant().toEpochMilli(), task.dueAt)
        assertEquals(1L, (r as ToolResult.Success).data["task_id"]!!.jsonPrimitive.long)
    }

    @Test
    fun addTask_withoutTitle_failsWithoutCrash() = runTest {
        val r = executor.execute("add_task", args("due_at" to "2026-10-31T18:00:00+03:00"))
        assertTrue(r is ToolResult.Failure)
        assertTrue(tasks.items.value.isEmpty())
    }

    @Test
    fun addTask_badDate_fails() = runTest {
        val r = executor.execute("add_task", args("title" to "x", "due_at" to "завтра вечером"))
        assertTrue(r is ToolResult.Failure)
    }

    @Test
    fun listTasks_todayIncludesOverdueButNotTomorrow() = runTest {
        val add = AddTaskUseCase(tasks, time)
        val zoned = { d: Int, h: Int -> ZonedDateTime.of(2026, 10, d, h, 0, 0, 0, msk).toInstant().toEpochMilli() }
        add("Вчерашняя", zoned(29, 10))
        add("Сегодняшняя", zoned(30, 20))
        add("Завтрашняя", zoned(31, 9))
        add("Без срока", null)

        val today = executor.execute("list_tasks", args("filter" to "today")) as ToolResult.Success
        val titles = today.data["tasks"]!!.jsonArray.map { (it as JsonObject)["title"]!!.jsonPrimitive.content }
        assertEquals(listOf("Вчерашняя", "Сегодняшняя"), titles)

        val tomorrow = executor.execute("list_tasks", args("filter" to "tomorrow")) as ToolResult.Success
        assertEquals(1, tomorrow.data["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun completeTask_unknownId_returnsFailure() = runTest {
        val r = executor.execute("complete_task", args("task_id" to 42))
        assertTrue(r is ToolResult.Failure)
    }

    @Test
    fun completeTask_acceptsIdAsString() = runTest {
        AddTaskUseCase(tasks, time)("Задача", null)
        val r = executor.execute("complete_task", args("task_id" to "1"))
        assertTrue(r is ToolResult.Success)
        assertTrue(tasks.items.value.single().isCompleted)
    }

    @Test
    fun setReminder_schedulesAlarm() = runTest {
        val r = executor.execute("set_reminder", args("text" to "Позвонить", "when" to "2026-10-30T13:00:00+03:00"))
        assertTrue(r is ToolResult.Success)
        val saved = reminders.items.value.single()
        assertEquals(saved.triggerAt, scheduler.reminders[saved.id])
    }

    @Test
    fun setReminder_inPast_fails() = runTest {
        val r = executor.execute("set_reminder", args("text" to "Поздно", "when" to "2026-10-30T09:00:00+03:00"))
        assertTrue(r is ToolResult.Failure)
        assertTrue(scheduler.reminders.isEmpty())
    }

    @Test
    fun setReminder_inexact_isReported() = runTest {
        scheduler.exact = false
        val r = executor.execute("set_reminder", args("text" to "x", "when" to "2026-10-30T15:00:00+03:00")) as ToolResult.Success
        assertEquals("false", r.data["exact"]!!.jsonPrimitive.content)
    }

    @Test
    fun addExpense_storesKopecksAndNormalizesCategory() = runTest {
        val r = executor.execute("add_expense", args("amount" to "350,50", "category" to "кафе", "comment" to "кофе"))
        assertTrue(r is ToolResult.Success)
        val e = expenses.items.value.single()
        assertEquals(35050L, e.amountKopecks)
        assertEquals("Кафе", e.category)
        assertEquals(time.millis, e.spentAt)
    }

    @Test
    fun addExpense_negative_fails() = runTest {
        assertTrue(executor.execute("add_expense", args("amount" to -5, "category" to "Кафе")) is ToolResult.Failure)
    }

    @Test
    fun expenseSummary_groupsByCategoryForMonth() = runTest {
        val add = AddExpenseUseCase(expenses, time)
        add(100.0, "Кафе", null, null)
        add(250.0, "Продукты", null, null)
        add(50.0, "Кафе", null, null)
        add(999.0, "Кафе", null, ZonedDateTime.of(2026, 9, 15, 12, 0, 0, 0, msk).toInstant().toEpochMilli())

        val r = executor.execute("get_expense_summary", args("period" to "month")) as ToolResult.Success
        assertEquals(400.0, r.data["total_rub"]!!.jsonPrimitive.content.toDouble(), 0.001)
        val first = r.data["by_category"]!!.jsonArray.first() as JsonObject
        assertEquals("Продукты", first["category"]!!.jsonPrimitive.content)
    }

    @Test
    fun addNote_andSearchByWord() = runTest {
        executor.execute("add_note", buildJsonObject {
            put("text", "Пароль от вайфай роутера на даче в шкафу")
            put("tags", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("Дача"))))
        })
        val note = notes.items.value.single()
        assertEquals(listOf("дача"), note.tags)

        val r = executor.execute("search_notes", args("query" to "где записан вайфай")) as ToolResult.Success
        assertEquals(1, r.data["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun startTimer_validatesDuration() = runTest {
        assertTrue(executor.execute("start_timer", args("duration_seconds" to 0)) is ToolResult.Failure)
        assertTrue(executor.execute("start_timer", args("duration_seconds" to 300, "label" to "чай")) is ToolResult.Success)
        assertEquals(time.millis + 300_000, scheduler.timers.single())
    }

    @Test
    fun todaySummary_containsTasksAndSpending() = runTest {
        AddTaskUseCase(tasks, time)("Сегодня", time.millis + 3_600_000)
        AddExpenseUseCase(expenses, time)(120.0, "Транспорт", null, null)
        val r = executor.execute("get_today_summary", JsonObject(emptyMap())) as ToolResult.Success
        assertEquals(1, r.data["tasks_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(120.0, r.data["spent_today_rub"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    @Test
    fun unknownTool_isFailure() = runTest {
        val r = executor.execute("delete_everything", JsonObject(emptyMap()))
        assertTrue(r is ToolResult.Failure)
        assertFalse(r is ToolResult.Success)
        assertNull(tasks.items.value.firstOrNull())
    }
}
