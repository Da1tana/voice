package ru.voiceassistant.wakeword

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineActivationException
import ai.picovoice.porcupine.PorcupineActivationLimitException
import ai.picovoice.porcupine.PorcupineActivationRefusedException
import ai.picovoice.porcupine.PorcupineActivationThrottledException
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineInvalidArgumentException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.content.Context
import android.util.Log

/** Реализация на Porcupine Android SDK (https://picovoice.ai/docs/porcupine/). */
class PorcupineWakeWordDetector(private val context: Context) : WakeWordDetector {

    private var manager: PorcupineManager? = null
    private var running = false

    override val isRunning: Boolean get() = running

    override fun start(config: WakeWordConfig, onDetected: () -> Unit, onError: (WakeWordException) -> Unit) {
        release()
        if (config.accessKey.isBlank()) {
            throw WakeWordException("Не задан AccessKey Picovoice. Укажите его в настройках.")
        }
        val callback = PorcupineManagerCallback { _ -> onDetected() }
        try {
            val builder = PorcupineManager.Builder()
                .setAccessKey(config.accessKey)
                .setSensitivity(config.sensitivity.coerceIn(0f, 1f))
                .setErrorCallback { e -> onError(WakeWordException(humanMessage(e), e)) }

            when (config.keyword) {
                WakeWordKeyword.AssistantRu -> {
                    ensureAsset(WakeWordKeyword.AssistantRu.KEYWORD_ASSET)
                    ensureAsset(WakeWordKeyword.AssistantRu.MODEL_ASSET)
                    builder.setKeywordPath(WakeWordKeyword.AssistantRu.KEYWORD_ASSET)
                        .setModelPath(WakeWordKeyword.AssistantRu.MODEL_ASSET)
                }
                WakeWordKeyword.Jarvis -> builder.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                WakeWordKeyword.Computer -> builder.setKeyword(Porcupine.BuiltInKeyword.COMPUTER)
                WakeWordKeyword.Porcupine -> builder.setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
            }

            manager = builder.build(context.applicationContext, callback).also { it.start() }
            running = true
            Log.i(TAG, "Wake word started: ${config.keyword.id}")
        } catch (e: PorcupineException) {
            release()
            throw WakeWordException(humanMessage(e), e)
        }
    }

    override fun pause() {
        if (!running) return
        try {
            manager?.stop()
        } catch (e: PorcupineException) {
            Log.w(TAG, "pause failed: ${e.javaClass.simpleName}")
        }
        running = false
    }

    override fun resume() {
        val m = manager ?: return
        if (running) return
        try {
            m.start()
            running = true
        } catch (e: PorcupineException) {
            Log.w(TAG, "resume failed: ${e.javaClass.simpleName}")
        }
    }

    override fun release() {
        manager?.let {
            try {
                it.stop()
            } catch (_: PorcupineException) {
            }
            it.delete()
        }
        manager = null
        running = false
    }

    private fun ensureAsset(path: String) {
        val exists = try {
            context.assets.open(path).close()
            true
        } catch (_: Exception) {
            false
        }
        if (!exists) {
            throw WakeWordException(
                "Нет файла $path. Создайте фразу «Ассистент» в Picovoice Console и положите файлы в assets, " +
                    "или выберите встроенную фразу в настройках."
            )
        }
    }

    private fun humanMessage(e: PorcupineException): String = when (e) {
        is PorcupineActivationException,
        is PorcupineActivationRefusedException -> "AccessKey Picovoice не принят. Проверьте ключ в настройках."
        is PorcupineActivationLimitException -> "Достигнут лимит активаций Picovoice для этого ключа."
        is PorcupineActivationThrottledException -> "Picovoice временно ограничил активации. Попробуйте позже."
        is PorcupineInvalidArgumentException -> "Неверные файлы ключевой фразы или модели."
        else -> "Ошибка детектора ключевой фразы."
    }

    private companion object {
        const val TAG = "WakeWord"
    }
}
