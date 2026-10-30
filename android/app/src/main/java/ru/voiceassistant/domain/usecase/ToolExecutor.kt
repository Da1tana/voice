package ru.voiceassistant.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import ru.voiceassistant.domain.model.ToolResult
import ru.voiceassistant.util.AppLog
import javax.inject.Inject

/** Выполняет tool_call от ассистента локально: маршрутизирует по имени на нужный UseCase. */
class ToolExecutor @Inject constructor(
    private val addTask: AddTaskUseCase,
    private val completeTask: CompleteTaskUseCase,
    private val listTasks: ListTasksUseCase,
    private val setReminder: SetReminderUseCase,
    private val addNote: AddNoteUseCase,
    private val searchNotes: SearchNotesUseCase,
    private val addExpense: AddExpenseUseCase,
    private val expenseSummary: GetExpenseSummaryUseCase,
    private val startTimer: StartTimerUseCase,
    private val todaySummary: GetTodaySummaryUseCase,
) {
    suspend fun execute(tool: String, args: JsonObject): ToolResult = try {
        when (tool) {
            "add_task" -> addTask.fromTool(args)
            "complete_task" -> completeTask.fromTool(args)
            "list_tasks" -> listTasks.fromTool(args)
            "set_reminder" -> setReminder.fromTool(args)
            "add_note" -> addNote.fromTool(args)
            "search_notes" -> searchNotes.fromTool(args)
            "add_expense" -> addExpense.fromTool(args)
            "get_expense_summary" -> expenseSummary.fromTool(args)
            "start_timer" -> startTimer.fromTool(args)
            "get_today_summary" -> todaySummary.fromTool(args)
            else -> ToolResult.Failure(tool, "Это действие не поддерживается приложением")
        }.also { AppLog.i(TAG, "tool=$tool status=${if (it is ToolResult.Success) "ok" else "fail"}") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ToolArgumentException) {
        AppLog.w(TAG, "tool=$tool bad arguments")
        ToolResult.Failure(tool, e.message ?: "Неверные данные")
    } catch (e: Exception) {
        AppLog.e(TAG, "tool=$tool failed", e)
        ToolResult.Failure(tool, "Не удалось выполнить действие на телефоне")
    }

    companion object {
        private const val TAG = "Tools"

        /** Понятные человеку названия действий для экрана истории. */
        val titles = mapOf(
            "add_task" to "Новая задача",
            "complete_task" to "Задача выполнена",
            "list_tasks" to "Список задач",
            "set_reminder" to "Напоминание",
            "add_note" to "Новая заметка",
            "search_notes" to "Поиск заметок",
            "add_expense" to "Новый расход",
            "get_expense_summary" to "Сводка расходов",
            "start_timer" to "Таймер",
            "get_today_summary" to "Сводка дня",
        )
    }
}
