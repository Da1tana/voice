package ru.voiceassistant.ui.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.voiceassistant.data.repository.ExpenseRepository
import ru.voiceassistant.domain.model.CategoryTotal
import ru.voiceassistant.domain.model.Expense
import ru.voiceassistant.domain.usecase.AddExpenseUseCase
import ru.voiceassistant.util.DateTimeUtils
import ru.voiceassistant.util.ExpensePeriodKind
import ru.voiceassistant.util.TimeProvider
import javax.inject.Inject

data class ExpensesUi(
    val period: ExpensePeriodKind = ExpensePeriodKind.DAY,
    val dayTotal: Long = 0,
    val weekTotal: Long = 0,
    val monthTotal: Long = 0,
    val byCategory: List<CategoryTotal> = emptyList(),
    val recent: List<Expense> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExpensesViewModel @Inject constructor(
    private val repo: ExpenseRepository,
    private val addExpense: AddExpenseUseCase,
    val time: TimeProvider,
) : ViewModel() {

    private val period = MutableStateFlow(ExpensePeriodKind.MONTH)
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun bounds(p: ExpensePeriodKind) = DateTimeUtils.periodBounds(p, time.nowMillis(), time.zone())

    private fun total(p: ExpensePeriodKind) = bounds(p).let { (f, t) -> repo.observeTotal(f, t) }

    val ui: StateFlow<ExpensesUi> = combine(
        period,
        total(ExpensePeriodKind.DAY),
        total(ExpensePeriodKind.WEEK),
        total(ExpensePeriodKind.MONTH),
        period.flatMapLatest { p -> bounds(p).let { (f, t) -> repo.observeByCategory(f, t) } },
    ) { p, d, w, m, cats -> ExpensesUi(p, d, w, m, cats) }
        .combine(repo.observeRecent(100)) { ui, recent -> ui.copy(recent = recent) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesUi())

    fun setPeriod(p: ExpensePeriodKind) {
        period.value = p
    }

    fun add(amountText: String, category: String, comment: String, spentAt: Long?) = viewModelScope.launch {
        val amount = amountText.replace(',', '.').replace(" ", "").toDoubleOrNull()
        if (amount == null) {
            _error.value = "Введите сумму числом"
            return@launch
        }
        try {
            addExpense(amount, category, comment, spentAt)
        } catch (e: IllegalArgumentException) {
            _error.value = e.message
        }
    }

    fun delete(e: Expense) = viewModelScope.launch { repo.delete(e) }

    fun clearError() {
        _error.value = null
    }
}
