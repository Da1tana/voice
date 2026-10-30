package ru.voiceassistant.data.local

import ru.voiceassistant.domain.model.ConversationEntry
import ru.voiceassistant.domain.model.ConversationStatus
import ru.voiceassistant.domain.model.Expense
import ru.voiceassistant.domain.model.Note
import ru.voiceassistant.domain.model.Reminder
import ru.voiceassistant.domain.model.Task
import ru.voiceassistant.util.Formatters

fun TaskEntity.toDomain() = Task(id, title, description, dueAt, isCompleted, createdAt, updatedAt)
fun Task.toEntity() = TaskEntity(id, title, description, dueAt, isCompleted, createdAt, updatedAt)

fun NoteEntity.toDomain() = Note(id, title, content, Formatters.parseTags(tags), createdAt, updatedAt)
fun Note.toEntity() = NoteEntity(id, title, content, Formatters.joinTags(tags), createdAt, updatedAt)

fun ExpenseEntity.toDomain() = Expense(id, amount, category, comment, spentAt, createdAt)
fun Expense.toEntity() = ExpenseEntity(id, amountKopecks, category, comment, spentAt, createdAt)

fun ReminderEntity.toDomain() = Reminder(id, text, triggerAt, isActive, createdAt)

fun ConversationEntity.toDomain() = ConversationEntry(
    id, requestText, responseText, actionType, actionPayload,
    runCatching { ConversationStatus.valueOf(status) }.getOrDefault(ConversationStatus.ERROR),
    createdAt,
)
