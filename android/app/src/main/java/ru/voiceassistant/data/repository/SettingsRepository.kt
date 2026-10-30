package ru.voiceassistant.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val backendUrl: String = "",
    val appToken: String = "",
    val wakeWordEnabled: Boolean = false,
    val wakeWordKeywordId: String = "assistant_ru",
    val picovoiceAccessKey: String = "",
    val wakeWordSensitivity: Float = 0.6f,
    val speechLocale: String = "ru-RU",
    val ttsVoiceName: String = "",
    val ttsRate: Float = 1.0f,
    val ttsEnabled: Boolean = true,
    val onboardingDone: Boolean = false,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val APP_TOKEN = stringPreferencesKey("app_token")
        val WAKE_ENABLED = booleanPreferencesKey("wake_enabled")
        val WAKE_KEYWORD = stringPreferencesKey("wake_keyword")
        val PICOVOICE_KEY = stringPreferencesKey("picovoice_key")
        val WAKE_SENSITIVITY = floatPreferencesKey("wake_sensitivity")
        val SPEECH_LOCALE = stringPreferencesKey("speech_locale")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
    }

    override val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val d = AppSettings()
            AppSettings(
                backendUrl = p[Keys.BACKEND_URL] ?: d.backendUrl,
                appToken = p[Keys.APP_TOKEN] ?: d.appToken,
                wakeWordEnabled = p[Keys.WAKE_ENABLED] ?: d.wakeWordEnabled,
                wakeWordKeywordId = p[Keys.WAKE_KEYWORD] ?: d.wakeWordKeywordId,
                picovoiceAccessKey = p[Keys.PICOVOICE_KEY] ?: d.picovoiceAccessKey,
                wakeWordSensitivity = p[Keys.WAKE_SENSITIVITY] ?: d.wakeWordSensitivity,
                speechLocale = p[Keys.SPEECH_LOCALE] ?: d.speechLocale,
                ttsVoiceName = p[Keys.TTS_VOICE] ?: d.ttsVoiceName,
                ttsRate = p[Keys.TTS_RATE] ?: d.ttsRate,
                ttsEnabled = p[Keys.TTS_ENABLED] ?: d.ttsEnabled,
                onboardingDone = p[Keys.ONBOARDING] ?: d.onboardingDone,
            )
        }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val old = AppSettings(
                backendUrl = p[Keys.BACKEND_URL] ?: "",
                appToken = p[Keys.APP_TOKEN] ?: "",
                wakeWordEnabled = p[Keys.WAKE_ENABLED] ?: false,
                wakeWordKeywordId = p[Keys.WAKE_KEYWORD] ?: "assistant_ru",
                picovoiceAccessKey = p[Keys.PICOVOICE_KEY] ?: "",
                wakeWordSensitivity = p[Keys.WAKE_SENSITIVITY] ?: 0.6f,
                speechLocale = p[Keys.SPEECH_LOCALE] ?: "ru-RU",
                ttsVoiceName = p[Keys.TTS_VOICE] ?: "",
                ttsRate = p[Keys.TTS_RATE] ?: 1.0f,
                ttsEnabled = p[Keys.TTS_ENABLED] ?: true,
                onboardingDone = p[Keys.ONBOARDING] ?: false,
            )
            val n = transform(old)
            p[Keys.BACKEND_URL] = n.backendUrl.trim().trimEnd('/')
            p[Keys.APP_TOKEN] = n.appToken.trim()
            p[Keys.WAKE_ENABLED] = n.wakeWordEnabled
            p[Keys.WAKE_KEYWORD] = n.wakeWordKeywordId
            p[Keys.PICOVOICE_KEY] = n.picovoiceAccessKey.trim()
            p[Keys.WAKE_SENSITIVITY] = n.wakeWordSensitivity
            p[Keys.SPEECH_LOCALE] = n.speechLocale
            p[Keys.TTS_VOICE] = n.ttsVoiceName
            p[Keys.TTS_RATE] = n.ttsRate
            p[Keys.TTS_ENABLED] = n.ttsEnabled
            p[Keys.ONBOARDING] = n.onboardingDone
        }
    }
}
