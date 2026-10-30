package ru.voiceassistant.wakeword

/**
 * Детектор ключевой фразы. Работает полностью на устройстве, аудио никуда не отправляется.
 *
 * Детектор держит микрофон, поэтому перед запуском SpeechRecognizer его нужно
 * остановить через [pause], а после распознавания — снова запустить через [resume].
 */
interface WakeWordDetector {
    /** Запускает прослушивание. Бросает [WakeWordException], если конфигурация неверна. */
    fun start(config: WakeWordConfig, onDetected: () -> Unit, onError: (WakeWordException) -> Unit)

    fun pause()

    fun resume()

    /** Полностью освобождает микрофон и нативные ресурсы. */
    fun release()

    val isRunning: Boolean
}

/**
 * @param accessKey ключ Picovoice Console. Он нужен SDK для работы на устройстве и
 *                  не даёт доступа к вашим данным, но всё равно не публикуйте его.
 * @param keyword   что слушать.
 * @param sensitivity 0..1, выше — чаще срабатывает, но больше ложных срабатываний.
 */
data class WakeWordConfig(
    val accessKey: String,
    val keyword: WakeWordKeyword,
    val sensitivity: Float = 0.6f,
)

sealed class WakeWordKeyword(val id: String, val displayName: String) {
    /**
     * Собственная фраза («Ассистент»), обученная в Picovoice Console для русского языка.
     * Файлы кладутся в app/src/main/assets/wakeword/.
     */
    data object AssistantRu : WakeWordKeyword("assistant_ru", "«Ассистент» (русский)") {
        const val KEYWORD_ASSET = "wakeword/assistant_ru.ppn"
        const val MODEL_ASSET = "wakeword/porcupine_params_ru.pv"
    }

    /** Встроенные английские фразы Porcupine — работают без дополнительных файлов. */
    data object Jarvis : WakeWordKeyword("jarvis", "Jarvis (англ.)")
    data object Computer : WakeWordKeyword("computer", "Computer (англ.)")
    data object Porcupine : WakeWordKeyword("porcupine", "Porcupine (англ.)")

    companion object {
        val all: List<WakeWordKeyword> get() = listOf(AssistantRu, Jarvis, Computer, Porcupine)
        fun fromId(id: String?): WakeWordKeyword = all.firstOrNull { it.id == id } ?: AssistantRu
    }
}

class WakeWordException(message: String, cause: Throwable? = null) : Exception(message, cause)
