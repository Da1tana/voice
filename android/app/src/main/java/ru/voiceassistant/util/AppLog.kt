package ru.voiceassistant.util

import android.util.Log
import ru.voiceassistant.BuildConfig

/**
 * Логирование только служебных событий. Не передавайте сюда тексты команд,
 * заметок, суммы и прочие пользовательские данные.
 */
object AppLog {
    private const val PREFIX = "VA/"

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(PREFIX + tag, msg)
    }

    fun i(tag: String, msg: String) = Log.i(PREFIX + tag, msg)

    fun w(tag: String, msg: String, e: Throwable? = null) =
        Log.w(PREFIX + tag, if (e != null) "$msg: ${e.javaClass.simpleName}" else msg)

    fun e(tag: String, msg: String, e: Throwable? = null) =
        Log.e(PREFIX + tag, if (e != null) "$msg: ${e.javaClass.simpleName}" else msg)
}
