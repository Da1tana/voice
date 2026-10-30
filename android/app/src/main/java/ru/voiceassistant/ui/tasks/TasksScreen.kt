package ru.voiceassistant.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.voiceassistant.domain.model.Task
import ru.voiceassistant.domain.model.TaskFilter
import ru.voiceassistant.ui.components.CenteredEmpty
import ru.voiceassistant.ui.components.ConfirmDialog
import ru.voiceassistant.ui.components.DateTimeField
import ru.voiceassistant.util.DateTimeUtils

@Composable
fun TasksScreen(openCreate: Boolean, viewModel: TasksViewModel = hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Task?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Task?>(null) }
    val now = viewModel.time.nowMillis()
    val zone = viewModel.time.zone()

    LaunchedEffect(openCreate) { if (openCreate) creating = true }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TaskFilter.entries.forEach { f ->
                    FilterChip(selected = f == filter, onClick = { viewModel.setFilter(f) }, label = { Text(f.title) })
                }
            }
            if (tasks.isEmpty()) {
                CenteredEmpty(
                    when (filter) {
                        TaskFilter.COMPLETED -> "Выполненных задач пока нет"
                        else -> "Задач нет.\nНажмите «+» или скажите: «добавь задачу купить хлеб»"
                    }
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(tasks, key = { it.id }) { task ->
                    val due = task.dueAt
                    val overdue = !task.isCompleted && due != null && due < now
                    ListItem(
                        modifier = Modifier.clickable { editing = task },
                        headlineContent = {
                            Text(
                                task.title,
                                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                            )
                        },
                        supportingContent = if (due != null || task.description != null) {
                            {
                                Column {
                                    task.description?.let { Text(it, maxLines = 2) }
                                    if (due != null) {
                                        Text(
                                            DateTimeUtils.formatHuman(due, zone, now),
                                            color = if (overdue) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        } else null,
                        leadingContent = {
                            Checkbox(checked = task.isCompleted, onCheckedChange = { viewModel.toggle(task) })
                        },
                        trailingContent = {
                            IconButton(onClick = { toDelete = task }) { Icon(Icons.Outlined.Delete, "Удалить") }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Новая задача") }
    }

    if (creating || editing != null) {
        TaskEditorDialog(
            task = editing,
            nowMillis = now,
            zone = zone,
            onSave = { title, desc, due -> viewModel.save(editing, title, desc, due) },
            onDismiss = { creating = false; editing = null },
        )
    }
    toDelete?.let { t ->
        ConfirmDialog(
            title = "Удалить задачу?",
            text = "«${t.title}» будет удалена без возможности восстановления.",
            confirmLabel = "Удалить",
            destructive = true,
            onConfirm = { viewModel.delete(t) },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun TaskEditorDialog(
    task: Task?,
    nowMillis: Long,
    zone: java.time.ZoneId,
    onSave: (String, String, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(task?.id) { mutableStateOf(task?.title.orEmpty()) }
    var description by rememberSaveable(task?.id) { mutableStateOf(task?.description.orEmpty()) }
    var dueAt by rememberSaveable(task?.id) { mutableStateOf(task?.dueAt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Новая задача" else "Задача") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Что сделать") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Описание") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth())
                DateTimeField(value = dueAt, onChange = { dueAt = it }, zone = zone, nowMillis = nowMillis)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onSave(title, description, dueAt); onDismiss() }) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
