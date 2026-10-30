package ru.voiceassistant

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.voiceassistant.data.remote.AssistantReply
import ru.voiceassistant.data.remote.BackendException
import ru.voiceassistant.domain.model.ConversationStatus
import ru.voiceassistant.domain.model.ToolResult
import ru.voiceassistant.domain.usecase.AddExpenseUseCase
import ru.voiceassistant.domain.usecase.AddNoteUseCase
import ru.voiceassistant.domain.usecase.AddTaskUseCase
import ru.voiceassistant.domain.usecase.CommandOutcome
import ru.voiceassistant.domain.usecase.CompleteTaskUseCase
import ru.voiceassistant.domain.usecase.GetExpenseSummaryUseCase
import ru.voiceassistant.domain.usecase.GetTodaySummaryUseCase
import ru.voiceassistant.domain.usecase.ListTasksUseCase
import ru.voiceassistant.domain.usecase.ProcessVoiceCommandUseCase
import ru.voiceassistant.domain.usecase.SearchNotesUseCase
import ru.voiceassistant.domain.usecase.SetReminderUseCase
import ru.voiceassistant.domain.usecase.StartTimerUseCase
import ru.voiceassistant.domain.usecase.ToolExecutor

class ProcessVoiceCommandTest {
    private val time = FixedTime()
    private val tasks = FakeTasks()
    private val history = FakeHistory()

    private fun executor(): ToolExecutor {
        val notes = FakeNotes()
        val expenses = FakeExpenses()
        val reminders = FakeReminders()
        val scheduler = FakeScheduler()
        val list = ListTasksUseCase(tasks, time)
        return ToolExecutor(
            AddTaskUseCase(tasks, time), CompleteTaskUseCase(tasks, time), list,
            SetReminderUseCase(reminders, scheduler, time), AddNoteUseCase(notes, time),
            SearchNotesUseCase(notes, time), AddExpenseUseCase(expenses, time),
            GetExpenseSummaryUseCase(expenses, time), StartTimerUseCase(scheduler, time),
            GetTodaySummaryUseCase(list, reminders, expenses, time),
        )
    }

    private fun addTaskCall() = AssistantReply.ToolCall(
        "add_task", "toolu_1",
        JsonObject(mapOf("title" to JsonPrimitive("Купить хлеб"), "due_at" to JsonPrimitive("2026-10-31T18:00:00+03:00"))),
        null,
    )

    @Test
    fun fullToolLoop_executesLocallyAndLogsHistory() = runTest {
        val assistant = FakeAssistant(addTaskCall(), AssistantReply.Reply("Добавил задачу на завтра."))
        val useCase = ProcessVoiceCommandUseCase(assistant, executor(), history, time)
        val outcome = useCase("добавь задачу купить хлеб завтра в шесть", "c1", "ru-RU")

        assertEquals(CommandOutcome.Answer("Добавил задачу на завтра.", listOf("add_task")), outcome)
        assertEquals(1, tasks.items.value.size)
        assertTrue(assistant.sentResults.single() is ToolResult.Success)
        val entry = history.items.single()
        assertEquals("add_task", entry.actionType)
        assertEquals(ConversationStatus.SUCCESS, entry.status)
    }

    @Test
    fun plainReply_noTools() = runTest {
        val assistant = FakeAssistant(AssistantReply.Reply("Привет!"))
        val outcome = ProcessVoiceCommandUseCase(assistant, executor(), history, time)("привет", "c1", "ru-RU")
        assertEquals(CommandOutcome.Answer("Привет!", emptyList()), outcome)
        assertEquals(null, history.items.single().actionType)
    }

    @Test
    fun backendDown_beforeAnyAction_isFailure() = runTest {
        val assistant = FakeAssistant(BackendException.NoConnection())
        val outcome = ProcessVoiceCommandUseCase(assistant, executor(), history, time)("привет", "c1", "ru-RU")
        assertEquals(CommandOutcome.Failed("Нет подключения к серверу ассистента"), outcome)
        assertEquals(ConversationStatus.ERROR, history.items.single().status)
    }

    @Test
    fun backendDown_afterSuccessfulAction_stillAnswers() = runTest {
        val assistant = FakeAssistant(addTaskCall(), BackendException.Timeout())
        val outcome = ProcessVoiceCommandUseCase(assistant, executor(), history, time)("добавь", "c1", "ru-RU")
        assertTrue(outcome is CommandOutcome.Answer)
        assertEquals(1, tasks.items.value.size)
    }

    @Test
    fun endlessToolCalls_areCapped() = runTest {
        val calls = Array<Any>(10) { addTaskCall() }
        val assistant = FakeAssistant(*calls)
        val outcome = ProcessVoiceCommandUseCase(assistant, executor(), history, time)("спам", "c1", "ru-RU")
        assertTrue(outcome is CommandOutcome.Answer)
        assertEquals(4, tasks.items.value.size)
    }
}
