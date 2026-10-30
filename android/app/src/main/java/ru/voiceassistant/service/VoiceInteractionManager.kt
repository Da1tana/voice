package ru.voiceassistant.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.voiceassistant.data.repository.SettingsRepository
import ru.voiceassistant.di.AppScope
import ru.voiceassistant.domain.model.AssistantState
import ru.voiceassistant.domain.usecase.CommandOutcome
import ru.voiceassistant.domain.usecase.ProcessVoiceCommandUseCase
import ru.voiceassistant.util.AppLog
import ru.voiceassistant.util.TimeProvider
import ru.voiceassistant.wakeword.WakeWordDetector
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LastExchange(val request: String, val answer: String, val isError: Boolean)

/**
 * Единая точка голосового сценария. Её используют и кнопка микрофона, и сервис wake word,
 * поэтому состояние ассистента одно на всё приложение.
 */
@Singleton
class VoiceInteractionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
    private val speech: SpeechRecognizerManager,
    private val tts: TtsManager,
    private val processCommand: ProcessVoiceCommandUseCase,
    private val settings: SettingsRepository,
    private val wakeWord: WakeWordDetector,
    private val time: TimeProvider,
) {
    private val _state = MutableStateFlow<AssistantState>(AssistantState.Off)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _lastExchange = MutableStateFlow<LastExchange?>(null)
    val lastExchange: StateFlow<LastExchange?> = _lastExchange.asStateFlow()

    /** true, пока работает foreground service с wake word. */
    private val _wakeWordActive = MutableStateFlow(false)
    val wakeWordActive: StateFlow<Boolean> = _wakeWordActive.asStateFlow()

    private var session: Job? = null
    private var conversationId = newConversationId()
    private var lastActivityAt = 0L

    fun setWakeWordActive(active: Boolean) {
        _wakeWordActive.value = active
        if (session?.isActive != true) _state.value = idleState()
    }

    /** Кнопка микрофона: старт, или остановка текущего этапа. */
    fun onMicPressed() {
        when (_state.value) {
            is AssistantState.Recording -> speech.stopListening()
            is AssistantState.Thinking, is AssistantState.Answering -> cancel()
            else -> startSession()
        }
    }

    fun startSession() {
        if (session?.isActive == true) return
        if (!hasMicPermission()) {
            _state.value = AssistantState.Error("Разрешите доступ к микрофону, чтобы говорить с ассистентом")
            return
        }
        session = scope.launch(Dispatchers.Main) { runSession() }
    }

    /** Текстовый ввод, если голос недоступен. */
    fun submitText(text: String) {
        if (session?.isActive == true || text.isBlank()) return
        session = scope.launch(Dispatchers.Main) { handleText(text.trim()) }
    }

    fun cancel() {
        session?.cancel()
        tts.stop()
        resumeWakeWord()
        _state.value = idleState()
    }

    fun clearError() {
        if (_state.value is AssistantState.Error) _state.value = idleState()
    }

    private suspend fun runSession() {
        val s = settings.current()
        pauseWakeWord()
        try {
            tts.stop()
            _state.value = AssistantState.Recording()
            val result = speech.listen(
                languageTag = s.speechLocale,
                onPartial = { p -> _state.value = AssistantState.Recording(partialText = p) },
                onRms = { rms ->
                    val cur = _state.value
                    if (cur is AssistantState.Recording) _state.value = cur.copy(rms = rms)
                },
            )
            when (result) {
                is SpeechResult.Error -> showError(result.message, speakIt = false)
                is SpeechResult.Text -> handleText(result.text)
            }
        } finally {
            resumeWakeWord()
            if (_state.value !is AssistantState.Error) _state.value = idleState()
        }
    }

    private suspend fun handleText(text: String) {
        val s = settings.current()
        refreshConversationIfIdle()
        _state.value = AssistantState.Thinking(text)

        val outcome = withContext(Dispatchers.IO) { processCommand(text, conversationId, s.speechLocale) }
        lastActivityAt = time.nowMillis()

        when (outcome) {
            is CommandOutcome.Answer -> {
                _lastExchange.value = LastExchange(text, outcome.text, isError = false)
                _state.value = AssistantState.Answering(text, outcome.text)
                if (s.ttsEnabled) {
                    val spoken = tts.speak(outcome.text, s.speechLocale, s.ttsVoiceName, s.ttsRate)
                    if (!spoken) AppLog.w(TAG, "tts unavailable, answer shown as text")
                }
                _state.value = idleState()
            }
            is CommandOutcome.Failed -> {
                _lastExchange.value = LastExchange(text, outcome.message, isError = true)
                // Диалог мог потеряться на сервере — следующую команду начинаем с чистого листа.
                conversationId = newConversationId()
                showError(outcome.message, speakIt = false)
            }
        }
    }

    private fun showError(message: String, @Suppress("SameParameterValue") speakIt: Boolean) {
        _state.value = AssistantState.Error(message)
        if (speakIt) scope.launch { tts.speak(message, "ru-RU", null, 1f) }
    }

    /** Через 10 минут тишины начинаем новый диалог, чтобы не тащить старый контекст. */
    private fun refreshConversationIfIdle() {
        val now = time.nowMillis()
        if (lastActivityAt != 0L && now - lastActivityAt > CONVERSATION_IDLE_MS) {
            conversationId = newConversationId()
        }
        lastActivityAt = now
    }

    private fun pauseWakeWord() {
        if (_wakeWordActive.value) wakeWord.pause()
    }

    private fun resumeWakeWord() {
        if (_wakeWordActive.value) wakeWord.resume()
    }

    private fun idleState(): AssistantState =
        if (_wakeWordActive.value) AssistantState.WaitingWakeWord else AssistantState.Off

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun newConversationId() = UUID.randomUUID().toString()

    private companion object {
        const val TAG = "Voice"
        const val CONVERSATION_IDLE_MS = 10 * 60_000L
    }
}
