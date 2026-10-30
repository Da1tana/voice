package ru.voiceassistant.service

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.voiceassistant.util.AppLog
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class TtsVoice(val name: String, val label: String)

/**
 * Озвучка ответов через системный TextToSpeech.
 * Если русского голоса нет (частая ситуация на Huawei без Google), speak() вернёт false,
 * и приложение просто покажет ответ текстом.
 */
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val pending = ConcurrentHashMap<String, (Boolean) -> Unit>()

    private suspend fun engine(): TextToSpeech? {
        val existing = ready
        if (existing != null) return if (existing.await()) tts else null

        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        tts = TextToSpeech(context) { status ->
            deferred.complete(status == TextToSpeech.SUCCESS)
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { pending.remove(it)?.invoke(true) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { pending.remove(it)?.invoke(false) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    AppLog.w(TAG, "tts error code=$errorCode")
                    utteranceId?.let { pending.remove(it)?.invoke(false) }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    utteranceId?.let { pending.remove(it)?.invoke(true) }
                }
            })
        }
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { deferred.await() } ?: false
        if (!ok) {
            AppLog.w(TAG, "tts init failed")
            shutdown()
        }
        return if (ok) tts else null
    }

    /** @return true, если фраза озвучена полностью (или прервана пользователем). */
    suspend fun speak(text: String, languageTag: String, voiceName: String?, rate: Float): Boolean {
        val engine = engine() ?: return false
        val locale = Locale.forLanguageTag(languageTag)
        val langResult = engine.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            AppLog.w(TAG, "language not supported by tts engine")
            return false
        }
        if (!voiceName.isNullOrBlank()) {
            engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))

        val id = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            pending[id] = { ok -> if (cont.isActive) cont.resume(ok) }
            val code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (code != TextToSpeech.SUCCESS) {
                pending.remove(id)
                if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation {
                pending.remove(id)
                engine.stop()
            }
        }
    }

    fun stop() {
        tts?.stop()
    }

    /** Список голосов для выбранного языка — для экрана настроек. */
    suspend fun voices(languageTag: String): List<TtsVoice> {
        val engine = engine() ?: return emptyList()
        val lang = Locale.forLanguageTag(languageTag).language
        return engine.voices.orEmpty()
            .filter { it.locale.language == lang && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedBy { it.name }
            .mapIndexed { i, v ->
                val kind = if (v.isNetworkConnectionRequired) "онлайн" else "офлайн"
                TtsVoice(v.name, "Голос ${i + 1} ($kind)")
            }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = null
        pending.clear()
    }

    private companion object {
        const val TAG = "Tts"
        const val INIT_TIMEOUT_MS = 5_000L
    }
}
