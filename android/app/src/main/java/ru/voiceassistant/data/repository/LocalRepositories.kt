package ru.voiceassistant.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.voiceassistant.data.local.CategorySum
import ru.voiceassistant.data.local.ConversationDao
import ru.voiceassistant.data.local.ConversationEntity
import ru.voiceassistant.data.local.ExpenseDao
import ru.voiceassistant.data.local.NoteDao
import ru.voiceassistant.data.local.ReminderDao
import ru.voiceassistant.data.local.ReminderEntity
import ru.voiceassistant.data.local.TaskDao
import ru.voiceassistant.data.local.toDomain
import ru.voiceassistant.data.local.toEntity
import ru.voiceassistant.domain.model.CategoryTotal
import ru.voiceassistant.domain.model.ConversationEntry
import ru.voiceassistant.domain.model.Expense
import ru.voiceassistant.domain.model.Note
import ru.voiceassistant.domain.model.Reminder
import ru.voiceassistant.domain.model.Task
import javax.inject.Inject
import javax.inject.Singleton

// ---------- Задачи ----------

interface TaskRepository {
    fun observeDue(from: Long, to: Long, includeOverdue: Boolean): Flow<List<Task>>
    fun observeOpen(): Flow<List<Task>>
    fun observeCompleted(): Flow<List<Task>>
    suspend fun getDue(from: Long, to: Long, includeOverdue: Boolean): List<Task>
    suspend fun getOpen(): List<Task>
    suspend fun getCompleted(): List<Task>
    suspend fun get(id: Long): Task?
    suspend fun add(task: Task): Long
    suspend fun update(task: Task)
    suspend fun delete(task: Task)
    /** @return true, если задача найдена. */
    suspend fun setCompleted(id: Long, completed: Boolean, now: Long): Boolean
}

@Singleton
class RoomTaskRepository @Inject constructor(private val dao: TaskDao) : TaskRepository {
    override fun observeDue(from: Long, to: Long, includeOverdue: Boolean) =
        dao.observeDueBetween(from, to, includeOverdue).map { l -> l.map { it.toDomain() } }

    override fun observeOpen() = dao.observeOpen().map { l -> l.map { it.toDomain() } }
    override fun observeCompleted() = dao.observeCompleted().map { l -> l.map { it.toDomain() } }
    override suspend fun getDue(from: Long, to: Long, includeOverdue: Boolean) =
        dao.getDueBetween(from, to, includeOverdue).map { it.toDomain() }

    override suspend fun getOpen() = dao.getOpen().map { it.toDomain() }
    override suspend fun getCompleted() = dao.getCompleted().map { it.toDomain() }
    override suspend fun get(id: Long) = dao.getById(id)?.toDomain()
    override suspend fun add(task: Task) = dao.insert(task.toEntity().copy(id = 0))
    override suspend fun update(task: Task) = dao.update(task.toEntity())
    override suspend fun delete(task: Task) = dao.delete(task.toEntity())
    override suspend fun setCompleted(id: Long, completed: Boolean, now: Long) =
        dao.setCompleted(id, completed, now) > 0
}

// ---------- Заметки ----------

interface NoteRepository {
    fun observeAll(): Flow<List<Note>>
    fun observeRecent(limit: Int): Flow<List<Note>>
    fun observeSearch(query: String): Flow<List<Note>>
    suspend fun search(query: String, limit: Int): List<Note>
    suspend fun get(id: Long): Note?
    suspend fun add(note: Note): Long
    suspend fun update(note: Note)
    suspend fun delete(note: Note)
}

@Singleton
class RoomNoteRepository @Inject constructor(private val dao: NoteDao) : NoteRepository {
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override fun observeRecent(limit: Int) = dao.observeRecent(limit).map { l -> l.map { it.toDomain() } }
    override fun observeSearch(query: String) = dao.observeSearch(escapeLike(query)).map { l -> l.map { it.toDomain() } }
    override suspend fun search(query: String, limit: Int) = dao.search(escapeLike(query), limit).map { it.toDomain() }
    override suspend fun get(id: Long) = dao.getById(id)?.toDomain()
    override suspend fun add(note: Note) = dao.insert(note.toEntity().copy(id = 0))
    override suspend fun update(note: Note) = dao.update(note.toEntity())
    override suspend fun delete(note: Note) = dao.delete(note.toEntity())
}

/** Экранирует спецсимволы LIKE, чтобы «50%» искалось буквально. */
internal fun escapeLike(q: String): String =
    q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

// ---------- Расходы ----------

interface ExpenseRepository {
    fun observeRecent(limit: Int): Flow<List<Expense>>
    fun observeTotal(from: Long, to: Long): Flow<Long>
    fun observeByCategory(from: Long, to: Long): Flow<List<CategoryTotal>>
    suspend fun add(expense: Expense): Long
    suspend fun delete(expense: Expense)
    suspend fun sumByCategory(from: Long, to: Long): List<CategoryTotal>
    suspend fun count(from: Long, to: Long): Int
}

@Singleton
class RoomExpenseRepository @Inject constructor(private val dao: ExpenseDao) : ExpenseRepository {
    override fun observeRecent(limit: Int) = dao.observeRecent(limit).map { l -> l.map { it.toDomain() } }
    override fun observeTotal(from: Long, to: Long) = dao.observeTotal(from, to)
    override fun observeByCategory(from: Long, to: Long) = dao.observeByCategory(from, to).map { l -> l.map(::toTotal) }
    override suspend fun add(expense: Expense) = dao.insert(expense.toEntity().copy(id = 0))
    override suspend fun delete(expense: Expense) = dao.delete(expense.toEntity())
    override suspend fun sumByCategory(from: Long, to: Long) = dao.sumByCategory(from, to).map(::toTotal)
    override suspend fun count(from: Long, to: Long) = dao.count(from, to)

    private fun toTotal(s: CategorySum) = CategoryTotal(s.category, s.total)
}

// ---------- Напоминания ----------

interface ReminderRepository {
    fun observeUpcoming(from: Long, limit: Int): Flow<List<Reminder>>
    suspend fun getUpcoming(from: Long, limit: Int): List<Reminder>
    suspend fun getActive(): List<Reminder>
    suspend fun get(id: Long): Reminder?
    suspend fun add(text: String, triggerAt: Long, createdAt: Long): Long
    suspend fun deactivate(id: Long)
    suspend fun delete(id: Long)
}

@Singleton
class RoomReminderRepository @Inject constructor(private val dao: ReminderDao) : ReminderRepository {
    override fun observeUpcoming(from: Long, limit: Int) = dao.observeUpcoming(from, limit).map { l -> l.map { it.toDomain() } }
    override suspend fun getUpcoming(from: Long, limit: Int) = dao.getUpcoming(from, limit).map { it.toDomain() }
    override suspend fun getActive() = dao.getActive().map { it.toDomain() }
    override suspend fun get(id: Long) = dao.getById(id)?.toDomain()
    override suspend fun add(text: String, triggerAt: Long, createdAt: Long) =
        dao.insert(ReminderEntity(text = text, triggerAt = triggerAt, isActive = true, createdAt = createdAt))

    override suspend fun deactivate(id: Long) = dao.deactivate(id)
    override suspend fun delete(id: Long) = dao.deleteById(id)
}

// ---------- История диалога ----------

interface ConversationRepository {
    fun observeRecent(limit: Int): Flow<List<ConversationEntry>>
    suspend fun add(entry: ConversationEntry): Long
    suspend fun clear()
}

@Singleton
class RoomConversationRepository @Inject constructor(private val dao: ConversationDao) : ConversationRepository {
    override fun observeRecent(limit: Int) = dao.observeRecent(limit).map { l -> l.map { it.toDomain() } }
    override suspend fun add(entry: ConversationEntry) = dao.insert(
        ConversationEntity(
            requestText = entry.requestText,
            responseText = entry.responseText,
            actionType = entry.actionType,
            actionPayload = entry.actionPayload,
            status = entry.status.name,
            createdAt = entry.createdAt,
        )
    )

    override suspend fun clear() = dao.clear()
}
