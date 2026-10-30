package ru.voiceassistant.ui.expenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.voiceassistant.domain.model.Expense
import ru.voiceassistant.domain.model.ExpenseCategories
import ru.voiceassistant.ui.components.ConfirmDialog
import ru.voiceassistant.ui.components.DateTimeField
import ru.voiceassistant.ui.components.EmptyHint
import ru.voiceassistant.ui.components.SectionHeader
import ru.voiceassistant.util.DateTimeUtils
import ru.voiceassistant.util.ExpensePeriodKind
import ru.voiceassistant.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(openCreate: Boolean, viewModel: ExpensesViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Expense?>(null) }
    val zone = viewModel.time.zone()
    val now = viewModel.time.nowMillis()

    LaunchedEffect(openCreate) { if (openCreate) creating = true }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TotalCard("Сегодня", ui.dayTotal, Modifier.weight(1f))
                    TotalCard("Неделя", ui.weekTotal, Modifier.weight(1f))
                    TotalCard("Месяц", ui.monthTotal, Modifier.weight(1f))
                }
            }
            item {
                SectionHeader("По категориям")
                val periods = listOf(ExpensePeriodKind.DAY to "День", ExpensePeriodKind.WEEK to "Неделя",
                    ExpensePeriodKind.MONTH to "Месяц")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    periods.forEachIndexed { i, (p, label) ->
                        SegmentedButton(
                            selected = ui.period == p,
                            onClick = { viewModel.setPeriod(p) },
                            shape = SegmentedButtonDefaults.itemShape(i, periods.size),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (ui.byCategory.isEmpty()) EmptyHint("Расходов за период нет")
                val max = ui.byCategory.maxOfOrNull { it.totalKopecks }?.coerceAtLeast(1) ?: 1
                ui.byCategory.forEach { c ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(c.category)
                            Text(Formatters.rubles(c.totalKopecks), style = MaterialTheme.typography.labelLarge)
                        }
                        LinearProgressIndicator(
                            progress = { c.totalKopecks.toFloat() / max },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
            item { SectionHeader("Последние расходы") }
            if (ui.recent.isEmpty()) item { EmptyHint("Скажите: «потратил 350 рублей на кофе»") }
            items(ui.recent, key = { it.id }) { e ->
                ListItem(
                    headlineContent = { Text(Formatters.rubles(e.amountKopecks)) },
                    supportingContent = {
                        Text(listOfNotNull(e.category, e.comment, DateTimeUtils.formatHuman(e.spentAt, zone, now))
                            .joinToString(" · "))
                    },
                    trailingContent = {
                        IconButton(onClick = { toDelete = e }) { Icon(Icons.Outlined.Delete, "Удалить") }
                    },
                )
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Новый расход") }
    }

    if (creating) {
        ExpenseDialog(
            nowMillis = now,
            zone = zone,
            onSave = { a, c, cm, d -> viewModel.add(a, c, cm, d) },
            onDismiss = { creating = false },
        )
    }
    error?.let {
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("Не удалось сохранить") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } },
        )
    }
    toDelete?.let { e ->
        ConfirmDialog(
            title = "Удалить расход?",
            text = "${Formatters.rubles(e.amountKopecks)}, ${e.category}",
            confirmLabel = "Удалить",
            destructive = true,
            onConfirm = { viewModel.delete(e) },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun TotalCard(label: String, kopecks: Long, modifier: Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Formatters.rubles(kopecks), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpenseDialog(
    nowMillis: Long,
    zone: java.time.ZoneId,
    onSave: (String, String, String, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(ExpenseCategories.all.first()) }
    var comment by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf<Long?>(nowMillis) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый расход") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    amount, { amount = it }, label = { Text("Сумма, ₽") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExpenseCategories.all.forEach { c ->
                        FilterChip(selected = c == category, onClick = { category = c }, label = { Text(c) })
                    }
                }
                OutlinedTextField(comment, { comment = it }, label = { Text("Комментарий") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                DateTimeField(value = date, onChange = { date = it }, zone = zone, nowMillis = nowMillis,
                    emptyLabel = "Сейчас", allowClear = false)
            }
        },
        confirmButton = {
            TextButton(enabled = amount.isNotBlank(), onClick = { onSave(amount, category, comment, date); onDismiss() }) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
