package ru.voiceassistant.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.voiceassistant.data.repository.ConversationRepository
import ru.voiceassistant.domain.model.ConversationEntry
import ru.voiceassistant.domain.model.ConversationStatus
import ru.voiceassistant.domain.usecase.ToolExecutor
import ru.voiceassistant.ui.components.CenteredEmpty
import ru.voiceassistant.ui.components.ConfirmDialog
import ru.voiceassistant.util.DateTimeUtils
import ru.voiceassistant.util.TimeProvider
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repo: ConversationRepository,
    val time: TimeProvider,
) : ViewModel() {
    val entries: StateFlow<List<ConversationEntry>> = repo.observeRecent(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() = viewModelScope.launch { repo.clear() }
}

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val zone = viewModel.time.zone()
    val now = viewModel.time.nowMillis()

    if (entries.isEmpty()) {
        CenteredEmpty("Здесь появятся ваши голосовые запросы и ответы ассистента")
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirmClear = true }) { Text("Очистить историю") }
            }
        }
        items(entries, key = { it.id }) { e -> HistoryCard(e, zone, now) }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Очистить историю?",
            text = "Все записи диалогов будут удалены. Задачи, заметки и расходы останутся.",
            confirmLabel = "Очистить",
            destructive = true,
            onConfirm = viewModel::clear,
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun HistoryCard(e: ConversationEntry, zone: java.time.ZoneId, now: Long) {
    val isError = e.status == ConversationStatus.ERROR
    Card(
        colors = if (isError) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                DateTimeUtils.formatHuman(e.createdAt, zone, now),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Person, null, Modifier.padding(end = 8.dp))
                Text(e.requestText, style = MaterialTheme.typography.bodyLarge)
            }
            e.responseText?.let {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.SmartToy, null,
                        Modifier.padding(end = 8.dp))
                    Text(it)
                }
            }
            e.actionType?.let { types ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.split(',').forEach { t ->
                        SuggestionChip(onClick = {}, label = { Text(ToolExecutor.titles[t] ?: t) })
                    }
                }
            }
        }
    }
}
