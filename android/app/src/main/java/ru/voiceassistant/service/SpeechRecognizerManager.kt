package ru.voiceassistant.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ru.voiceassistant.util.AppLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface SpeechResult {
    data class Text(val text: String) : SpeechResult
    data class Error(val message: String, val retryable: Boolean) : SpeechResult
}

/**
 * Обёртка над android.speech.SpeechRecognizer.
 *
 * На Huawei без сервисов Google системного распознавателя может не быть.
 * Тогда на Android 12+ пробуем распознавание на устройстве, а если и его нет —
 * сообщаем пользователю, что нужно установить сервис распознавания речи.
 */
@Singleton
class SpeechRecognizerManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var recognizer: SpeechRecognizer? = null

    fun availability(): Availability = when {
        SpeechRecognizer.isRecognitionAvailable(context) -> Availability.SYSTEM
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> Availability.ON_DEVICE
        else -> Availability.NONE
    }

    enum class Availability { SYSTEM, ON_DEVICE, NONE }

    /** Слушает одну фразу. Вызывать можно с любого потока: работа с API идёт на главном. */
    suspend fun listen(
        languageTag: String,
        onPartial: (String) -> Unit,
        onRms: (Float) -> Unit,
    ): SpeechResult = withContext<SpeechResult>(Dispatchers.Main) {
        val sr = when (availability()) {
            Availability.SYSTEM -> SpeechRecognizer.createSpeechRecognizer(context)
            Availability.ON_DEVICE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else null
            Availability.NONE -> null
        } ?: return@withContext SpeechResult.Error(
            "На телефоне нет сервиса распознавания речи. Установите его и выберите в системных настройках голосового ввода",
            retryable = false,
        )
        recognizer?.destroy()
        recognizer = sr

        suspendCancellableCoroutine<SpeechResult> { cont ->
            fun finish(result: SpeechResult) {
                if (cont.isActive) cont.resume(result)
                sr.destroy()
                if (recognizer === sr) recognizer = null
            }

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = onRms(rmsdB)
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    finish(
                        if (text.isEmpty()) SpeechResult.Error("Не расслышал. Повторите, пожалуйста", true)
                        else SpeechResult.Text(text)
                    )
                }

                override fun onError(error: Int) {
                    AppLog.w(TAG, "recognizer error code=$error")
                    finish(mapError(error))
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

            try {
                sr.startListening(intent)
            } catch (e: Exception) {
                AppLog.e(TAG, "startListening failed", e)
                finish(SpeechResult.Error("Не удалось включить распознавание речи", true))
            }

            cont.invokeOnCancellation {
                // Отмена приходит не обязательно на главном потоке.
                android.os.Handler(context.mainLooper).post {
                    try {
                        sr.cancel()
                    } catch (_: Exception) {
                    }
                    sr.destroy()
                    if (recognizer === sr) recognizer = null
                }
            }
        }
    }

    /** Досрочно завершить запись: распознаватель вернёт то, что успел услышать. */
    fun stopListening() {
        android.os.Handler(context.mainLooper).post { recognizer?.stopListening() }
    }

    private fun mapError(code: Int): SpeechResult.Error = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            SpeechResult.Error("Не расслышал. Повторите, пожалуйста", true)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            SpeechResult.Error("Распознаванию речи не хватает интернета. Повторите позже", true)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SpeechResult.Error("Нет доступа к микрофону. Разрешите его в настройках", false)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            SpeechResult.Error("Распознаватель занят. Повторите через секунду", true)
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
            SpeechResult.Error("Распознаватель не поддерживает выбранный язык. Скачайте его в настройках голосового ввода", false)
        else -> SpeechResult.Error("Ошибка распознавания речи. Повторите, пожалуйста", true)
    }

    private companion object {
        const val TAG = "Speech"
        // Константы API 31, объявлены вручную для minSdk 29.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
