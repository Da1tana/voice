package ru.voiceassistant.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.voiceassistant.data.repository.NoteRepository
import ru.voiceassistant.data.repository.ReminderRepository
import ru.voiceassistant.data.repository.SettingsRepository
import ru.voiceassistant.data.repository.TaskRepository
import ru.voiceassistant.domain.model.AssistantState
import ru.voiceassistant.domain.model.Note
import ru.voiceassistant.domain.model.Reminder
import ru.voiceassistant.domain.model.Task
import ru.voiceassistant.domain.usecase.CompleteTaskUseCase
import ru.voiceassistant.service.LastExchange
import ru.voiceassistant.service.VoiceInteractionManager
import ru.voiceassistant.util.DateTimeUtils
import ru.voiceassistant.util.TimeProvider
import java.time.ZoneId
import javax.inject.Inject

data class TodayUiState(
    val nowMillis: Long = 0,
    val zone: ZoneId = ZoneId.systemDefault(),
    val tasks: List<Task> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val notes: List<Note> = emptyList(),
    val backendConfigured: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val voice: VoiceInteractionManager,
    private val completeTask: CompleteTaskUseCase,
    tasks: TaskRepository,
    reminders: ReminderRepository,
    notes: NoteRepository,
    settings: SettingsRepository,
    private val time: TimeProvider,
) : ViewModel() {

    val assistantState: StateFlow<AssistantState> = voice.state
    val lastExchange: StateFlow<LastExchange?> = voice.lastExchange
    val wakeWordActive: StateFlow<Boolean> = voice.wakeWordActive

    /** Тикает раз в 30 секунд — для часов и смены дня. */
    private val clock = flow {
        while (true) {
            emit(time.nowMillis())
            delay(30_000)
        }
    }

    private val dayStart = clock.map { DateTimeUtils.startOfDay(it, time.zone()) }.distinctUntilChanged()

    private val todayTasks = dayStart.flatMapLatest { start ->
        tasks.observeDue(start, DateTimeUtils.startOfDay(start, time.zone(), 1), includeOverdue = true)
    }

    private val upcomingReminders = dayStart.flatMapLatest { reminders.observeUpcoming(time.nowMillis(), 3) }

    val ui: StateFlow<TodayUiState> = combine(
        clock, todayTasks, upcomingReminders, notes.observeRecent(3), settings.settings,
    ) { now, t, r, n, s ->
        TodayUiState(now, time.zone(), t, r.filter { it.triggerAt >= now }, n, s.backendUrl.isNotBlank())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState(nowMillis = time.nowMillis()))

    fun onMic() = voice.onMicPressed()
    fun onSubmitText(text: String) = voice.submitText(text)
    fun dismissError() = voice.clearError()

    fun toggleTask(task: Task) {
        viewModelScope.launch { completeTask(task.id, !task.isCompleted) }
    }
}
