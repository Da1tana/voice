package ru.voiceassistant.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.voiceassistant.domain.model.Note
import ru.voiceassistant.ui.components.CenteredEmpty
import ru.voiceassistant.ui.components.ConfirmDialog
import ru.voiceassistant.util.DateTimeUtils

@Composable
fun NotesScreen(openCreate: Boolean, viewModel: NotesViewModel = hiltViewModel()) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Note?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Note?>(null) }
    val zone = viewModel.time.zone()
    val now = viewModel.time.nowMillis()

    LaunchedEffect(openCreate) { if (openCreate) creating = true }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Поиск по заметкам и #тегам") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (notes.isEmpty()) {
                CenteredEmpty(if (query.isBlank()) "Заметок нет.\nСкажите: «запиши идею…»" else "Ничего не найдено")
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(notes, key = { it.id }) { note ->
                    Card(Modifier.fillMaxWidth().clickable { editing = note }) {
                        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton(onClick = { toDelete = note }) { Icon(Icons.Outlined.Delete, "Удалить") }
                            }
                            if (note.content != note.title) {
                                Text(note.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            if (note.tags.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    note.tags.take(4).forEach { tag ->
                                        SuggestionChip(onClick = { viewModel.setQuery("#$tag") }, label = { Text("#$tag") })
                                    }
                                }
                            }
                            Text(
                                "Создано: ${DateTimeUtils.formatHuman(note.createdAt, zone, now)}" +
                                    if (note.updatedAt != note.createdAt) " · изменено: ${DateTimeUtils.formatHuman(note.updatedAt, zone, now)}" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Новая заметка") }
    }

    if (creating || editing != null) {
        NoteEditorDialog(
            note = editing,
            onSave = { t, c, tags -> viewModel.save(editing, t, c, tags) },
            onDismiss = { creating = false; editing = null },
        )
    }
    toDelete?.let { n ->
        ConfirmDialog(
            title = "Удалить заметку?",
            text = "«${n.title}» будет удалена без возможности восстановления.",
            confirmLabel = "Удалить",
            destructive = true,
            onConfirm = { viewModel.delete(n) },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun NoteEditorDialog(note: Note?, onSave: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable(note?.id) { mutableStateOf(note?.title.orEmpty()) }
    var content by rememberSaveable(note?.id) { mutableStateOf(note?.content.orEmpty()) }
    var tags by rememberSaveable(note?.id) { mutableStateOf(note?.tags.orEmpty().joinToString(", ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note == null) "Новая заметка" else "Заметка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Заголовок") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(content, { content = it }, label = { Text("Текст") }, minLines = 4, maxLines = 10,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tags, { tags = it }, label = { Text("Теги через запятую") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() || content.isNotBlank(), onClick = { onSave(title, content, tags); onDismiss() }) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
