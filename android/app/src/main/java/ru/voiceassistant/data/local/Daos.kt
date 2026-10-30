package ru.voiceassistant.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Query("UPDATE tasks SET is_completed = :completed, updated_at = :now WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean, now: Long): Int

    /** Незавершённые задачи со сроком в [from, to) плюс просроченные, если includeOverdue. */
    @Query(
        """SELECT * FROM tasks WHERE is_completed = 0 AND due_at IS NOT NULL
           AND due_at < :to AND (due_at >= :from OR :includeOverdue)
           ORDER BY due_at"""
    )
    fun observeDueBetween(from: Long, to: Long, includeOverdue: Boolean): Flow<List<TaskEntity>>

    @Query(
        """SELECT * FROM tasks WHERE is_completed = 0 AND due_at IS NOT NULL
           AND due_at < :to AND (due_at >= :from OR :includeOverdue)
           ORDER BY due_at"""
    )
    suspend fun getDueBetween(from: Long, to: Long, includeOverdue: Boolean): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE is_completed = 0 ORDER BY due_at IS NULL, due_at, created_at DESC")
    fun observeOpen(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE is_completed = 0 ORDER BY due_at IS NULL, due_at, created_at DESC")
    suspend fun getOpen(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE is_completed = 1 ORDER BY updated_at DESC")
    fun observeCompleted(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE is_completed = 1 ORDER BY updated_at DESC LIMIT 50")
    suspend fun getCompleted(): List<TaskEntity>

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TaskEntity>)

    @Query("DELETE FROM tasks")
    suspend fun clear()
}

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NoteEntity>>

    @Query(
        """SELECT * FROM notes WHERE title LIKE '%' || :q || '%' ESCAPE '\'
           OR content LIKE '%' || :q || '%' ESCAPE '\'
           OR tags LIKE '%' || :q || '%' ESCAPE '\'
           ORDER BY updated_at DESC"""
    )
    fun observeSearch(q: String): Flow<List<NoteEntity>>

    @Query(
        """SELECT * FROM notes WHERE title LIKE '%' || :q || '%' ESCAPE '\'
           OR content LIKE '%' || :q || '%' ESCAPE '\'
           OR tags LIKE '%' || :q || '%' ESCAPE '\'
           ORDER BY updated_at DESC LIMIT :limit"""
    )
    suspend fun search(q: String, limit: Int): List<NoteEntity>

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<NoteEntity>)

    @Query("DELETE FROM notes")
    suspend fun clear()
}

data class CategorySum(val category: String, val total: Long)

@Dao
interface ExpenseDao {
    @Insert
    suspend fun insert(expense: ExpenseEntity): Long

    @Delete
    suspend fun delete(expense: ExpenseEntity)

    @Query("SELECT * FROM expenses ORDER BY spent_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExpenseEntity>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE spent_at >= :from AND spent_at < :to")
    fun observeTotal(from: Long, to: Long): Flow<Long>

    @Query(
        """SELECT category, SUM(amount) AS total FROM expenses
           WHERE spent_at >= :from AND spent_at < :to GROUP BY category ORDER BY total DESC"""
    )
    fun observeByCategory(from: Long, to: Long): Flow<List<CategorySum>>

    @Query(
        """SELECT category, SUM(amount) AS total FROM expenses
           WHERE spent_at >= :from AND spent_at < :to GROUP BY category ORDER BY total DESC"""
    )
    suspend fun sumByCategory(from: Long, to: Long): List<CategorySum>

    @Query("SELECT COUNT(*) FROM expenses WHERE spent_at >= :from AND spent_at < :to")
    suspend fun count(from: Long, to: Long): Int

    @Query("SELECT * FROM expenses")
    suspend fun getAll(): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ExpenseEntity>)

    @Query("DELETE FROM expenses")
    suspend fun clear()
}

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    @Query("UPDATE reminders SET is_active = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM reminders WHERE is_active = 1 AND trigger_at >= :from ORDER BY trigger_at LIMIT :limit")
    fun observeUpcoming(from: Long, limit: Int): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE is_active = 1 AND trigger_at >= :from ORDER BY trigger_at LIMIT :limit")
    suspend fun getUpcoming(from: Long, limit: Int): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE is_active = 1")
    suspend fun getActive(): List<ReminderEntity>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ReminderEntity>)

    @Query("DELETE FROM reminders")
    suspend fun clear()
}

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(entry: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations")
    suspend fun getAll(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ConversationEntity>)

    @Query("DELETE FROM conversations")
    suspend fun clear()
}
