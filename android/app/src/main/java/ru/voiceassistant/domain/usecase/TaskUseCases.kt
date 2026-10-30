package ru.voiceassistant.domain.usecase

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import ru.voiceassistant.data.repository.TaskRepository
import ru.voiceassistant.domain.model.Task
import ru.voiceassistant.domain.model.TaskFilter
import ru.voiceassistant.domain.model.ToolResult
import ru.voiceassistant.util.DateTimeUtils
import ru.voiceassistant.util.TimeProvider
import ru.voiceassistant.util.Validation
import javax.inject.Inject

class AddTaskUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val time: TimeProvider,
) {
    /** Для UI. */
    suspend operator fun invoke(title: String, dueAt: Long?, description: String? = null): Long {
        val t = title.trim()
        if (t.isEmpty()) throw ToolArgumentException("Название задачи пустое")
        val now = time.nowMillis()
        return tasks.add(
            Task(title = t.take(Validation.MAX_TITLE), description = description?.trim()?.ifEmpty { null },
                dueAt = dueAt, createdAt = now, updatedAt = now)
        )
    }

    /** Для голосового инструмента add_task(title, due_at?). */
    suspend fun fromTool(args: JsonObject): ToolResult {
        val title = args.str("title") ?: throw ToolArgumentException("Не указано название задачи")
        val dueRaw = args.str("due_at")
        val dueAt = dueRaw?.let {
            DateTimeUtils.parseToEpochMillis(it, time.zone())
                ?: throw ToolArgumentException("Не удалось разобрать дату: $it")
        }
        val id = invoke(title, dueAt)
        return ToolResult.Success("add_task", buildJsonObject {
            put("task_id", id)
            put("title", title)
            dueAt?.let { put("due_at", DateTimeUtils.toIsoOffset(it, time.zone())) }
        })
    }
}

class CompleteTaskUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(id: Long, completed: Boolean = true): Boolean =
        tasks.setCompleted(id, completed, time.nowMillis())

    suspend fun fromTool(args: JsonObject): ToolResult {
        val id = args.long("task_id") ?: throw ToolArgumentException("Не указан номер задачи")
        val task = tasks.get(id) ?: return ToolResult.Failure("complete_task", "Задача с номером $id не найдена")
        invoke(id, true)
        return ToolResult.Success("complete_task", buildJsonObject {
            put("task_id", id)
            put("title", task.title)
            put("completed", true)
        })
    }
}

class ListTasksUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(filter: TaskFilter): List<Task> {
        val now = time.nowMillis()
        val zone = time.zone()
        return when (filter) {
            TaskFilter.TODAY -> tasks.getDue(
                DateTimeUtils.startOfDay(now, zone), DateTimeUtils.startOfDay(now, zone, 1), includeOverdue = true,
            )
            TaskFilter.TOMORROW -> tasks.getDue(
                DateTimeUtils.startOfDay(now, zone, 1), DateTimeUtils.startOfDay(now, zone, 2), includeOverdue = false,
            )
            TaskFilter.ALL -> tasks.getOpen()
            TaskFilter.COMPLETED -> tasks.getCompleted()
        }
    }

    suspend fun fromTool(args: JsonObject): ToolResult {
        val filter = TaskFilter.fromApi(args.str("filter"))
        val list = invoke(filter)
        val now = time.nowMillis()
        return ToolResult.Success("list_tasks", buildJsonObject {
            put("filter", filter.apiValue)
            put("count", list.size)
            putJsonArray("tasks") {
                list.take(MAX_ITEMS).forEach { t ->
                    addJsonObject {
                        put("task_id", t.id)
                        put("title", t.title)
                        t.dueAt?.let {
                            put("due_at", DateTimeUtils.toIsoOffset(it, time.zone()))
                            put("overdue", !t.isCompleted && it < now)
                        }
                        put("completed", t.isCompleted)
                    }
                }
            }
            if (list.size > MAX_ITEMS) put("truncated", true)
        })
    }

    private companion object {
        const val MAX_ITEMS = 20
    }
}
