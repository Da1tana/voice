package ru.voiceassistant.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaskEntity::class,
        NoteEntity::class,
        ExpenseEntity::class,
        ReminderEntity::class,
        ConversationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun noteDao(): NoteDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun reminderDao(): ReminderDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        const val NAME = "assistant.db"
    }
}
