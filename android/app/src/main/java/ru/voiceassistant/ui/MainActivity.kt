package ru.voiceassistant.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.voiceassistant.data.repository.SettingsRepository
import ru.voiceassistant.service.AssistantForegroundService
import ru.voiceassistant.service.VoiceInteractionManager
import ru.voiceassistant.ui.navigation.AppNavigation
import ru.voiceassistant.ui.permissions.OnboardingScreen
import ru.voiceassistant.ui.permissions.Permissions
import ru.voiceassistant.ui.theme.VoiceAssistantTheme
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    /** null — ещё загружается. */
    val onboardingDone: StateFlow<Boolean?> = settings.settings.map { it.onboardingDone }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun finishOnboarding() = viewModelScope.launch { settings.update { it.copy(onboardingDone = true) } }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var voice: VoiceInteractionManager

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VoiceAssistantTheme {
                Surface(Modifier.fillMaxSize()) {
                    val done by vm.onboardingDone.collectAsStateWithLifecycle()
                    when (done) {
                        null -> Box(Modifier.fillMaxSize())
                        false -> OnboardingScreen(onDone = vm::finishOnboarding)
                        true -> AppNavigation()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Сервис с микрофоном можно запустить только пока приложение на экране (Android 14).
        lifecycleScope.launch {
            val s = settings.current()
            if (s.wakeWordEnabled && Permissions.mic(this@MainActivity) && !voice.wakeWordActive.value) {
                AssistantForegroundService.start(this@MainActivity)
            }
        }
    }
}
