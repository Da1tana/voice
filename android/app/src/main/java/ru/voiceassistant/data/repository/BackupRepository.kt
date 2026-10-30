package ru.voiceassistant.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ru.voiceassistant.data.local.AppDatabase
import ru.voiceassistant.data.local.ConversationEntity
import ru.voiceassistant.data.local.ExpenseEntity
import ru.voiceassistant.data.local.NoteEntity
import ru.voiceassistant.data.local.ReminderEntity
import ru.voiceassistant.data.local.TaskEntity
import ru.voiceassistant.service.AlarmScheduler
import ru.voiceassistant.util.AppLog
import ru.voiceassistant.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long,
    val tasks: List<TaskEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val expenses: List<ExpenseEntity> = emptyList(),
    val reminders: List<ReminderEntity> = emptyList(),
    val conversations: List<ConversationEntity> = emptyList(),
) {
    companion object {
        const val FORMAT = "voice-assistant-backup"
    }
}

data class BackupStats(val tasks: Int, val notes: Int, val expenses: Int, val reminders: Int, val conversations: Int) {
    val total get() = tasks + notes + expenses + reminders + conversations
}

/** Экспорт, импорт и полное удаление локальных данных. */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val scheduler: AlarmScheduler,
    private val time: TimeProvider,
) {

    suspend fun export(uri: Uri): BackupStats = withContext(Dispatchers.IO) {
        val backup = BackupFile(
            exportedAt = time.nowMillis(),
            tasks = db.taskDao().getAll(),
            notes = db.noteDao().getAll(),
            expenses = db.expenseDao().getAll(),
            reminders = db.reminderDao().getAll(),
            conversations = db.conversationDao().getAll(),
        )
        val json = AppJson.encodeToString(BackupFile.serializer(), backup)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: error("Не удалось открыть файл для записи")
        AppLog.i(TAG, "export done")
        backup.stats()
    }

    /** Заменяет все локальные данные содержимым файла. */
    suspend fun import(uri: Uri): BackupStats = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Не удалось прочитать файл")
        val backup = parse(text)

        // Сначала снимаем старые будильники, потом заменяем данные.
        db.reminderDao().getActive().forEach { scheduler.cancelReminder(it.id) }
        db.withTransaction {
            clearTables()
            db.taskDao().insertAll(backup.tasks)
            db.noteDao().insertAll(backup.notes)
            db.expenseDao().insertAll(backup.expenses)
            db.reminderDao().insertAll(backup.reminders)
            db.conversationDao().insertAll(backup.conversations)
        }
        val now = time.nowMillis()
        backup.reminders.filter { it.isActive && it.triggerAt > now }
            .forEach { scheduler.scheduleReminder(it.id, it.text, it.triggerAt) }
        AppLog.i(TAG, "import done")
        backup.stats()
    }

    suspend fun wipe() = withContext(Dispatchers.IO) {
        db.reminderDao().getActive().forEach { scheduler.cancelReminder(it.id) }
        db.withTransaction { clearTables() }
        AppLog.i(TAG, "wipe done")
    }

    private suspend fun clearTables() {
        db.taskDao().clear()
        db.noteDao().clear()
        db.expenseDao().clear()
        db.reminderDao().clear()
        db.conversationDao().clear()
    }

    private fun BackupFile.stats() =
        BackupStats(tasks.size, notes.size, expenses.size, reminders.size, conversations.size)

    companion object {
        private const val TAG = "Backup"

        fun parse(text: String): BackupFile {
            val backup = try {
                AppJson.decodeFromString(BackupFile.serializer(), text)
            } catch (e: Exception) {
                throw IllegalArgumentException("Файл не похож на резервную копию ассистента", e)
            }
            require(backup.format == BackupFile.FORMAT) { "Неизвестный формат файла" }
            require(backup.version <= 1) { "Файл создан более новой версией приложения" }
            return backup
        }
    }
}
