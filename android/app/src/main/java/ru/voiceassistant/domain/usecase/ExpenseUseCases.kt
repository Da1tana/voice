package ru.voiceassistant.domain.usecase

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import ru.voiceassistant.data.repository.ExpenseRepository
import ru.voiceassistant.domain.model.Expense
import ru.voiceassistant.domain.model.ExpenseCategories
import ru.voiceassistant.domain.model.ExpenseSummary
import ru.voiceassistant.domain.model.ToolResult
import ru.voiceassistant.util.DateTimeUtils
import ru.voiceassistant.util.ExpensePeriodKind
import ru.voiceassistant.util.Formatters
import ru.voiceassistant.util.TimeProvider
import ru.voiceassistant.util.Validation
import javax.inject.Inject

class AddExpenseUseCase @Inject constructor(
    private val expenses: ExpenseRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(amountRub: Double, category: String, comment: String?, spentAt: Long?): Long {
        if (amountRub.isNaN() || amountRub <= 0.0) throw ToolArgumentException("Сумма должна быть больше нуля")
        if (amountRub > Validation.MAX_AMOUNT_RUB) throw ToolArgumentException("Слишком большая сумма")
        val now = time.nowMillis()
        return expenses.add(
            Expense(
                amountKopecks = Formatters.toKopecks(amountRub),
                category = ExpenseCategories.normalize(category),
                comment = comment?.trim()?.ifEmpty { null },
                spentAt = spentAt ?: now,
                createdAt = now,
            )
        )
    }

    suspend fun fromTool(args: JsonObject): ToolResult {
        val amount = args.double("amount") ?: throw ToolArgumentException("Не указана сумма")
        val category = args.str("category") ?: "Другое"
        val dateRaw = args.str("date")
        val spentAt = dateRaw?.let {
            DateTimeUtils.parseToEpochMillis(it, time.zone())
                ?: throw ToolArgumentException("Не удалось разобрать дату: $it")
        }
        val id = invoke(amount, category, args.str("comment"), spentAt)
        return ToolResult.Success("add_expense", buildJsonObject {
            put("expense_id", id)
            put("amount_rub", amount)
            put("category", ExpenseCategories.normalize(category))
        })
    }
}

class GetExpenseSummaryUseCase @Inject constructor(
    private val expenses: ExpenseRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(period: ExpensePeriodKind): ExpenseSummary {
        val (from, to) = DateTimeUtils.periodBounds(period, time.nowMillis(), time.zone())
        val byCategory = expenses.sumByCategory(from, to)
        return ExpenseSummary(
            totalKopecks = byCategory.sumOf { it.totalKopecks },
            byCategory = byCategory,
            count = expenses.count(from, to),
        )
    }

    suspend fun fromTool(args: JsonObject): ToolResult {
        val period = when (args.str("period")?.lowercase()) {
            "day", "today" -> ExpensePeriodKind.DAY
            "week" -> ExpensePeriodKind.WEEK
            "month" -> ExpensePeriodKind.MONTH
            else -> throw ToolArgumentException("Период должен быть day, week или month")
        }
        val s = invoke(period)
        return ToolResult.Success("get_expense_summary", buildJsonObject {
            put("period", period.name.lowercase())
            put("total_rub", s.totalKopecks / 100.0)
            put("count", s.count)
            putJsonArray("by_category") {
                s.byCategory.forEach { c ->
                    addJsonObject {
                        put("category", c.category)
                        put("total_rub", c.totalKopecks / 100.0)
                    }
                }
            }
        })
    }
}
