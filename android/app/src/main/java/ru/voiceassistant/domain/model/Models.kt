package ru.voiceassistant.domain.model

data class Task(
    val id: Long = 0,
    val title: String,
    val description: String? = null,
    val dueAt: Long? = null,
    val isCompleted: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Note(
    val id: Long = 0,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

data class Expense(
    val id: Long = 0,
    /** Сумма в копейках. */
    val amountKopecks: Long,
    val category: String,
    val comment: String? = null,
    val spentAt: Long,
    val createdAt: Long,
)

data class Reminder(
    val id: Long = 0,
    val text: String,
    val triggerAt: Long,
    val isActive: Boolean = true,
    val createdAt: Long,
)

data class ConversationEntry(
    val id: Long = 0,
    val requestText: String,
    val responseText: String?,
    val actionType: String?,
    val actionPayload: String?,
    val status: ConversationStatus,
    val createdAt: Long,
)

enum class ConversationStatus { SUCCESS, ERROR }

enum class TaskFilter(val apiValue: String, val title: String) {
    TODAY("today", "Сегодня"),
    TOMORROW("tomorrow", "Завтра"),
    ALL("all", "Все"),
    COMPLETED("completed", "Выполненные");

    companion object {
        fun fromApi(value: String?): TaskFilter = entries.firstOrNull { it.apiValue == value } ?: TODAY
    }
}

data class CategoryTotal(val category: String, val totalKopecks: Long)

data class ExpenseSummary(
    val totalKopecks: Long,
    val byCategory: List<CategoryTotal>,
    val count: Int,
)

object ExpenseCategories {
    val all = listOf("Продукты", "Кафе", "Транспорт", "Дом", "Здоровье", "Развлечения", "Одежда", "Связь", "Другое")

    /** Приводит категорию от ассистента к одной из известных, сохраняя незнакомые как есть. */
    fun normalize(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "Другое"
        return all.firstOrNull { it.equals(t, ignoreCase = true) }
            ?: t.replaceFirstChar { it.uppercase() }
    }
}

/** Состояние голосового ассистента для индикатора на главном экране. */
sealed interface AssistantState {
    data object Off : AssistantState
    data object WaitingWakeWord : AssistantState
    data class Recording(val partialText: String = "", val rms: Float = 0f) : AssistantState
    data class Thinking(val requestText: String) : AssistantState
    data class Answering(val requestText: String, val answer: String) : AssistantState
    data class Error(val message: String) : AssistantState
}
