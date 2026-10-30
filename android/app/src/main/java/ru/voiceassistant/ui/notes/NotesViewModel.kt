package ru.voiceassistant.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.voiceassistant.data.repository.NoteRepository
import ru.voiceassistant.domain.model.Note
import ru.voiceassistant.domain.usecase.AddNoteUseCase
import ru.voiceassistant.util.Formatters
import ru.voiceassistant.util.TimeProvider
import ru.voiceassistant.util.Validation
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repo: NoteRepository,
    private val addNote: AddNoteUseCase,
    val time: TimeProvider,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val notes: StateFlow<List<Note>> = _query.debounce(200).flatMapLatest { q ->
        if (q.isBlank()) repo.observeAll() else repo.observeSearch(q.removePrefix("#"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        _query.value = q
    }

    fun save(existing: Note?, title: String, content: String, tagsRaw: String) = viewModelScope.launch {
        if (content.isBlank() && title.isBlank()) return@launch
        val tags = Formatters.parseTags(tagsRaw)
        val body = content.ifBlank { title }
        if (existing == null) {
            addNote(body, tags, title)
        } else {
            repo.update(
                existing.copy(
                    title = title.trim().ifEmpty { AddNoteUseCase.deriveTitle(body) }.take(Validation.MAX_TITLE),
                    content = body.trim().take(Validation.MAX_TEXT),
                    tags = tags,
                    updatedAt = time.nowMillis(),
                )
            )
        }
    }

    fun delete(note: Note) = viewModelScope.launch { repo.delete(note) }
}
