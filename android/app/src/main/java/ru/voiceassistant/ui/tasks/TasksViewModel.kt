package ru.voiceassistant.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.voiceassistant.data.repository.TaskRepository
import ru.voiceassistant.domain.model.Task
import ru.voiceassistant.domain.model.TaskFilter
import ru.voiceassistant.domain.usecase.AddTaskUseCase
import ru.voiceassistant.domain.usecase.CompleteTaskUseCase
import ru.voiceassistant.util.DateTimeUtils
import ru.voiceassistant.util.TimeProvider
import ru.voiceassistant.util.Validation
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val repo: TaskRepository,
    private val addTask: AddTaskUseCase,
    private val completeTask: CompleteTaskUseCase,
    val time: TimeProvider,
) : ViewModel() {

    private val _filter = MutableStateFlow(TaskFilter.TODAY)
    val filter: StateFlow<TaskFilter> = _filter.asStateFlow()

    val tasks: StateFlow<List<Task>> = _filter.flatMapLatest { f ->
        val now = time.nowMillis()
        val zone = time.zone()
        when (f) {
            TaskFilter.TODAY -> repo.observeDue(
                DateTimeUtils.startOfDay(now, zone), DateTimeUtils.startOfDay(now, zone, 1), includeOverdue = true,
            )
            TaskFilter.TOMORROW -> repo.observeDue(
                DateTimeUtils.startOfDay(now, zone, 1), DateTimeUtils.startOfDay(now, zone, 2), includeOverdue = false,
            )
            TaskFilter.ALL -> repo.observeOpen()
            TaskFilter.COMPLETED -> repo.observeCompleted()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(f: TaskFilter) {
        _filter.value = f
    }

    fun toggle(task: Task) = viewModelScope.launch { completeTask(task.id, !task.isCompleted) }

    fun save(existing: Task?, title: String, description: String, dueAt: Long?) = viewModelScope.launch {
        if (title.isBlank()) return@launch
        if (existing == null) {
            addTask(title, dueAt, description)
        } else {
            repo.update(
                existing.copy(
                    title = title.trim().take(Validation.MAX_TITLE),
                    description = description.trim().ifEmpty { null },
                    dueAt = dueAt,
                    updatedAt = time.nowMillis(),
                )
            )
        }
    }

    fun delete(task: Task) = viewModelScope.launch { repo.delete(task) }
}
