package ru.voiceassistant

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import ru.voiceassistant.data.remote.AssistantReply
import ru.voiceassistant.data.repository.AssistantRepository
import ru.voiceassistant.data.repository.ConversationRepository
import ru.voiceassistant.data.repository.ExpenseRepository
import ru.voiceassistant.data.repository.NoteRepository
import ru.voiceassistant.data.repository.ReminderRepository
import ru.voiceassistant.data.repository.TaskRepository
import ru.voiceassistant.domain.model.CategoryTotal
import ru.voiceassistant.domain.model.ConversationEntry
import ru.voiceassistant.domain.model.Expense
import ru.voiceassistant.domain.model.Note
import ru.voiceassistant.domain.model.Reminder
import ru.voiceassistant.domain.model.Task
import ru.voiceassistant.domain.model.ToolResult
import ru.voiceassistant.service.AlarmScheduler
import ru.voiceassistant.util.TimeProvider
import java.time.ZoneId
import java.time.ZonedDateTime

/** 30 октября 2026, 12:00 по Москве. */
class FixedTime(
    var millis: Long = ZonedDateTime.of(2026, 10, 30, 12, 0, 0, 0, ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(),
) : TimeProvider {
    override fun nowMillis() = millis
    override fun zone(): ZoneId = ZoneId.of("Europe/Moscow")
}

class FakeTasks : TaskRepository {
    val items = MutableStateFlow<List<Task>>(emptyList())
    private var nextId = 1L

    override fun observeDue(from: Long, to: Long, includeOverdue: Boolean): Flow<List<Task>> =
        items.map { l -> l.filter { due(it, from, to, includeOverdue) } }

    override fun observeOpen() = items.map { l -> l.filter { !it.isCompleted } }
    override fun observeCompleted() = items.map { l -> l.filter { it.isCompleted } }
    override suspend fun getDue(from: Long, to: Long, includeOverdue: Boolean) =
        items.value.filter { due(it, from, to, includeOverdue) }.sortedBy { it.dueAt }

    override suspend fun getOpen() = items.value.filter { !it.isCompleted }
    override suspend fun getCompleted() = items.value.filter { it.isCompleted }
    override suspend fun get(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun add(task: Task): Long {
        val id = nextId++
        items.value = items.value + task.copy(id = id)
        return id
    }

    override suspend fun update(task: Task) {
        items.value = items.value.map { if (it.id == task.id) task else it }
    }

    override suspend fun delete(task: Task) {
        items.value = items.value.filterNot { it.id == task.id }
    }

    override suspend fun setCompleted(id: Long, completed: Boolean, now: Long): Boolean {
        val t = get(id) ?: return false
        update(t.copy(isCompleted = completed, updatedAt = now))
        return true
    }

    private fun due(t: Task, from: Long, to: Long, overdue: Boolean): Boolean {
        val d = t.dueAt ?: return false
        return !t.isCompleted && d < to && (d >= from || overdue)
    }
}

class FakeNotes : NoteRepository {
    val items = MutableStateFlow<List<Note>>(emptyList())
    private var nextId = 1L
    override fun observeAll() = items
    override fun observeRecent(limit: Int) = items.map { it.take(limit) }
    override fun observeSearch(query: String) = items.map { l -> l.filter { matches(it, query) } }
    override suspend fun search(query: String, limit: Int) = items.value.filter { matches(it, query) }.take(limit)
    override suspend fun get(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun add(note: Note): Long {
        val id = nextId++
        items.value = items.value + note.copy(id = id)
        return id
    }

    override suspend fun update(note: Note) {
        items.value = items.value.map { if (it.id == note.id) note else it }
    }

    override suspend fun delete(note: Note) {
        items.value = items.value.filterNot { it.id == note.id }
    }

    private fun matches(n: Note, q: String) =
        n.title.contains(q, true) || n.content.contains(q, true) || n.tags.any { it.contains(q, true) }
}

class FakeExpenses : ExpenseRepository {
    val items = MutableStateFlow<List<Expense>>(emptyList())
    private var nextId = 1L
    override fun observeRecent(limit: Int) = items.map { it.take(limit) }
    override fun observeTotal(from: Long, to: Long) = items.map { l -> inRange(l, from, to).sumOf { it.amountKopecks } }
    override fun observeByCategory(from: Long, to: Long) = items.map { group(inRange(it, from, to)) }
    override suspend fun add(expense: Expense): Long {
        val id = nextId++
        items.value = items.value + expense.copy(id = id)
        return id
    }

    override suspend fun delete(expense: Expense) {
        items.value = items.value.filterNot { it.id == expense.id }
    }

    override suspend fun sumByCategory(from: Long, to: Long) = group(inRange(items.value, from, to))
    override suspend fun count(from: Long, to: Long) = inRange(items.value, from, to).size

    private fun inRange(l: List<Expense>, from: Long, to: Long) = l.filter { it.spentAt in from until to }
    private fun group(l: List<Expense>) = l.groupBy { it.category }
        .map { (c, e) -> CategoryTotal(c, e.sumOf { it.amountKopecks }) }
        .sortedByDescending { it.totalKopecks }
}

class FakeReminders : ReminderRepository {
    val items = MutableStateFlow<List<Reminder>>(emptyList())
    private var nextId = 1L
    override fun observeUpcoming(from: Long, limit: Int) = items.map { l -> l.filter { it.isActive && it.triggerAt >= from }.take(limit) }
    override suspend fun getUpcoming(from: Long, limit: Int) = items.value.filter { it.isActive && it.triggerAt >= from }.take(limit)
    override suspend fun getActive() = items.value.filter { it.isActive }
    override suspend fun get(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun add(text: String, triggerAt: Long, createdAt: Long): Long {
        val id = nextId++
        items.value = items.value + Reminder(id, text, triggerAt, true, createdAt)
        return id
    }

    override suspend fun deactivate(id: Long) {
        items.value = items.value.map { if (it.id == id) it.copy(isActive = false) else it }
    }

    override suspend fun delete(id: Long) {
        items.value = items.value.filterNot { it.id == id }
    }
}

class FakeScheduler(var exact: Boolean = true) : AlarmScheduler {
    val reminders = mutableMapOf<Long, Long>()
    val timers = mutableListOf<Long>()
    override fun scheduleReminder(id: Long, text: String, triggerAt: Long): Boolean {
        reminders[id] = triggerAt
        return exact
    }

    override fun cancelReminder(id: Long) {
        reminders.remove(id)
    }

    override fun scheduleTimer(timerId: Int, label: String, triggerAt: Long): Boolean {
        timers += triggerAt
        return exact
    }

    override fun canScheduleExact() = exact
}

class FakeHistory : ConversationRepository {
    val items = mutableListOf<ConversationEntry>()
    override fun observeRecent(limit: Int) = MutableStateFlow(items.toList())
    override suspend fun add(entry: ConversationEntry): Long {
        items += entry
        return items.size.toLong()
    }

    override suspend fun clear() = items.clear()
}

/** Отвечает заранее заданной очередью ответов и записывает отправленные результаты. */
class FakeAssistant(vararg replies: Any) : AssistantRepository {
    private val queue = ArrayDeque(replies.toList())
    val sentResults = mutableListOf<ToolResult>()
    val sentCommands = mutableListOf<String>()

    private fun next(): AssistantReply = when (val r = queue.removeFirst()) {
        is Exception -> throw r
        is AssistantReply -> r
        else -> error("bad fake reply")
    }

    override suspend fun sendCommand(text: String, conversationId: String, locale: String): AssistantReply {
        sentCommands += text
        return next()
    }

    override suspend fun sendToolResult(conversationId: String, toolCallId: String?, result: ToolResult): AssistantReply {
        sentResults += result
        return next()
    }

    override suspend fun checkHealth(baseUrl: String) = true
}
