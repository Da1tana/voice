package ru.voiceassistant.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.voiceassistant.domain.model.AssistantState
import ru.voiceassistant.service.LastExchange
import ru.voiceassistant.ui.components.AssistantStatus
import ru.voiceassistant.ui.components.EmptyHint
import ru.voiceassistant.ui.components.MicButton
import ru.voiceassistant.ui.components.SectionHeader
import ru.voiceassistant.util.DateTimeUtils
import java.time.Instant

@Composable
fun TodayScreen(
    onNewTask: () -> Unit,
    onNewNote: () -> Unit,
    onNewExpense: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val state by viewModel.assistantState.collectAsStateWithLifecycle()
    val last by viewModel.lastExchange.collectAsStateWithLifecycle()
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // После отправки прячем клавиатуру: иначе она перекрывает ответ и нижние вкладки.
    val submit = {
        if (typed.isNotBlank()) {
            viewModel.onSubmitText(typed)
            typed = ""
            keyboard?.hide()
            focus.clearFocus()
        }
    }

    val zone = ui.zone
    val hour = Instant.ofEpochMilli(ui.nowMillis).atZone(zone).hour

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(DateTimeUtils.formatTime(ui.nowMillis, zone), style = MaterialTheme.typography.displaySmall)
                Text(
                    "${DateTimeUtils.greeting(hour)} · ${DateTimeUtils.formatDate(ui.nowMillis, zone)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!ui.backendConfigured) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Сервер ассистента не настроен", style = MaterialTheme.typography.titleMedium)
                        Text("Укажите адрес backend, чтобы голосовые команды заработали.")
                        TextButton(onClick = onOpenSettings) { Text("Открыть настройки") }
                    }
                }
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MicButton(state = state, onClick = viewModel::onMic)
                AssistantStatus(state)
                Spacer(Modifier.height(8.dp))
                LiveText(state, last, onDismissError = viewModel::dismissError)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { typing = !typing }) {
                        Icon(Icons.Outlined.Keyboard, null)
                        Text("  Написать текстом")
                    }
                }
                if (typing) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        placeholder = { Text("Например: добавь задачу позвонить маме") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        trailingIcon = {
                            IconButton(onClick = { submit() }) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Отправить")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AssistChip(onClick = onNewTask, label = { Text("Задача") }, leadingIcon = { Icon(Icons.Outlined.AddTask, null) })
                AssistChip(onClick = onNewNote, label = { Text("Заметка") }, leadingIcon = { Icon(Icons.Outlined.StickyNote2, null) })
                AssistChip(onClick = onNewExpense, label = { Text("Расход") }, leadingIcon = { Icon(Icons.Outlined.Payments, null) })
            }
        }

        item { SectionHeader("Задачи на сегодня") { TextButton(onClick = onOpenTasks) { Text("Все") } } }
        if (ui.tasks.isEmpty()) item { EmptyHint("На сегодня задач нет") }
        items(ui.tasks, key = { "t" + it.id }) { task ->
            val due = task.dueAt
            val overdue = due != null && due < ui.nowMillis
            ListItem(
                headlineContent = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = if (due != null) {
                    {
                        Text(
                            (if (overdue) "Просрочено: " else "") + DateTimeUtils.formatHuman(due, zone, ui.nowMillis),
                            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else null,
                leadingContent = { Checkbox(checked = task.isCompleted, onCheckedChange = { viewModel.toggleTask(task) }) },
            )
        }

        item { SectionHeader("Ближайшие напоминания") }
        if (ui.reminders.isEmpty()) item { EmptyHint("Напоминаний нет. Скажите: «напомни через час позвонить»") }
        items(ui.reminders, key = { "r" + it.id }) { r ->
            ListItem(
                headlineContent = { Text(r.text) },
                supportingContent = { Text(DateTimeUtils.formatHuman(r.triggerAt, zone, ui.nowMillis)) },
                leadingContent = { Icon(Icons.Outlined.Notifications, null) },
            )
        }

        item { SectionHeader("Последние заметки") { TextButton(onClick = onOpenNotes) { Text("Все") } } }
        if (ui.notes.isEmpty()) item { EmptyHint("Заметок пока нет") }
        items(ui.notes, key = { "n" + it.id }) { n ->
            ListItem(
                headlineContent = { Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(n.content, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Распознаваемый текст, ответ ассистента или ошибка под кнопкой микрофона. */
@Composable
private fun LiveText(state: AssistantState, last: LastExchange?, onDismissError: () -> Unit) {
    when (state) {
        is AssistantState.Recording -> if (state.partialText.isNotBlank()) {
            Text("«${state.partialText}»", style = MaterialTheme.typography.bodyLarge)
        }
        is AssistantState.Thinking -> Text("«${state.requestText}»", style = MaterialTheme.typography.bodyLarge)
        is AssistantState.Error -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(state.message, color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = onDismissError) { Text("Понятно") }
            }
        }
        else -> last?.takeIf { !it.isError }?.let { ex ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(ex.request, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(ex.answer, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    Box(Modifier.height(4.dp))
}
