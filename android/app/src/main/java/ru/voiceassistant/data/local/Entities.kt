package ru.voiceassistant.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Все даты — UTC epoch milliseconds.
// Сущности сериализуемы: тот же формат используется для экспорта/импорта JSON.

@Serializable
@Entity(tableName = "tasks", indices = [Index("due_at"), Index("is_completed")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String? = null,
    @SerialName("due_at") @ColumnInfo(name = "due_at") val dueAt: Long? = null,
    @SerialName("is_completed") @ColumnInfo(name = "is_completed") val isCompleted: Boolean = false,
    @SerialName("created_at") @ColumnInfo(name = "created_at") val createdAt: Long,
    @SerialName("updated_at") @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Serializable
@Entity(tableName = "notes", indices = [Index("updated_at")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    /** Теги через запятую в нижнем регистре: "работа,идеи". */
    val tags: String = "",
    @SerialName("created_at") @ColumnInfo(name = "created_at") val createdAt: Long,
    @SerialName("updated_at") @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Serializable
@Entity(tableName = "expenses", indices = [Index("spent_at")])
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Сумма в копейках (целое число), чтобы избежать ошибок округления. */
    val amount: Long,
    val category: String,
    val comment: String? = null,
    @SerialName("spent_at") @ColumnInfo(name = "spent_at") val spentAt: Long,
    @SerialName("created_at") @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Serializable
@Entity(tableName = "reminders", indices = [Index("trigger_at")])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    @SerialName("trigger_at") @ColumnInfo(name = "trigger_at") val triggerAt: Long,
    @SerialName("is_active") @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @SerialName("created_at") @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Serializable
@Entity(tableName = "conversations", indices = [Index("created_at")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @SerialName("request_text") @ColumnInfo(name = "request_text") val requestText: String,
    @SerialName("response_text") @ColumnInfo(name = "response_text") val responseText: String? = null,
    @SerialName("action_type") @ColumnInfo(name = "action_type") val actionType: String? = null,
    @SerialName("action_payload") @ColumnInfo(name = "action_payload") val actionPayload: String? = null,
    /** SUCCESS | ERROR */
    val status: String,
    @SerialName("created_at") @ColumnInfo(name = "created_at") val createdAt: Long,
)
