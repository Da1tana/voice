package ru.voiceassistant.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.voiceassistant.data.repository.AppSettings
import ru.voiceassistant.data.repository.AssistantRepository
import ru.voiceassistant.data.repository.BackupRepository
import ru.voiceassistant.data.repository.SettingsRepository
import ru.voiceassistant.service.SpeechRecognizerManager
import ru.voiceassistant.service.TtsManager
import ru.voiceassistant.service.TtsVoice
import ru.voiceassistant.service.VoiceInteractionManager
import ru.voiceassistant.service.WakeWordErrors
import ru.voiceassistant.util.AppLog
import ru.voiceassistant.util.Validation
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val assistant: AssistantRepository,
    private val backup: BackupRepository,
    private val tts: TtsManager,
    private val speech: SpeechRecognizerManager,
    voice: VoiceInteractionManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val wakeWordActive: StateFlow<Boolean> = voice.wakeWordActive
    val wakeWordError: StateFlow<String?> = WakeWordErrors.last

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _voices = MutableStateFlow<List<TtsVoice>>(emptyList())
    val voices: StateFlow<List<TtsVoice>> = _voices.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    val recognizerAvailability: SpeechRecognizerManager.Availability get() = speech.availability()

    init {
        viewModelScope.launch { _voices.value = tts.voices(settingsRepo.current().speechLocale) }
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepo.update(transform) }

    fun saveBackend(url: String, token: String) = viewModelScope.launch {
        val u = url.trim().trimEnd('/')
        if (u.isNotEmpty() && !Validation.isValidBackendUrl(u)) {
            _message.value = "Адрес должен начинаться с https://"
            return@launch
        }
        settingsRepo.update { it.copy(backendUrl = u, appToken = token.trim()) }
        _message.value = "Сохранено"
    }

    fun testBackend(url: String) = viewModelScope.launch {
        _checking.value = true
        val ok = assistant.checkHealth(url)
        _checking.value = false
        _message.value = if (ok) "Сервер отвечает" else "Нет подключения к серверу ассистента"
    }

    fun setLocale(tag: String) = viewModelScope.launch {
        settingsRepo.update { it.copy(speechLocale = tag, ttsVoiceName = "") }
        _voices.value = tts.voices(tag)
    }

    fun previewVoice() = viewModelScope.launch {
        val s = settingsRepo.current()
        val ok = tts.speak("Привет! Так звучит мой голос.", s.speechLocale, s.ttsVoiceName, s.ttsRate)
        if (!ok) _message.value = "Синтез речи для этого языка недоступен. Установите голосовой движок, например RHVoice"
    }

    fun export(uri: Uri) = viewModelScope.launch {
        _message.value = try {
            val s = backup.export(uri)
            "Экспортировано записей: ${s.total}"
        } catch (e: Exception) {
            AppLog.e("Settings", "export failed", e)
            "Не удалось экспортировать данные"
        }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        _message.value = try {
            val s = backup.import(uri)
            "Импортировано: задач ${s.tasks}, заметок ${s.notes}, расходов ${s.expenses}, напоминаний ${s.reminders}"
        } catch (e: IllegalArgumentException) {
            e.message
        } catch (e: Exception) {
            AppLog.e("Settings", "import failed", e)
            "Не удалось импортировать файл"
        }
    }

    fun wipe() = viewModelScope.launch {
        backup.wipe()
        _message.value = "Все локальные данные удалены"
    }

    fun clearWakeWordError() {
        WakeWordErrors.last.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }
}
