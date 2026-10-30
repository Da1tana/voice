package ru.voiceassistant.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.voiceassistant.R
import ru.voiceassistant.data.repository.SettingsRepository
import ru.voiceassistant.util.AppLog
import ru.voiceassistant.wakeword.WakeWordConfig
import ru.voiceassistant.wakeword.WakeWordDetector
import ru.voiceassistant.wakeword.WakeWordException
import ru.voiceassistant.wakeword.WakeWordKeyword
import javax.inject.Inject

/**
 * Foreground service с типом microphone: держит Porcupine и ждёт ключевую фразу.
 * Аудио обрабатывается только на устройстве.
 */
@AndroidEntryPoint
class AssistantForegroundService : LifecycleService() {

    @Inject lateinit var detector: WakeWordDetector
    @Inject lateinit var voice: VoiceInteractionManager
    @Inject lateinit var settings: SettingsRepository

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }

        if (!startAsForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        lifecycleScope.launch(Dispatchers.Main) {
            val s = settings.current()
            val config = WakeWordConfig(
                accessKey = s.picovoiceAccessKey,
                keyword = WakeWordKeyword.fromId(s.wakeWordKeywordId),
                sensitivity = s.wakeWordSensitivity,
            )
            try {
                detector.start(
                    config,
                    onDetected = {
                        AppLog.i(TAG, "wake word detected")
                        lifecycleScope.launch(Dispatchers.Main) { voice.startSession() }
                    },
                    onError = { e -> lifecycleScope.launch(Dispatchers.Main) { fail(e.message) } },
                )
                voice.setWakeWordActive(true)
            } catch (e: WakeWordException) {
                fail(e.message)
            }
        }
        // Если система убьёт сервис, не перезапускаем его молча: микрофон без ведома пользователя — плохая идея.
        return START_NOT_STICKY
    }

    private fun startAsForeground(): Boolean {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, AssistantForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.notification_listening))
            .setContentText(getString(R.string.notification_listening_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Notifications.openAppIntent(this))
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Тип microphone появился в Android 11 и обязателен там для доступа к микрофону из сервиса.
                ServiceCompat.startForeground(
                    this, Notifications.ID_LISTENING, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(Notifications.ID_LISTENING, notification)
            }
            true
        } catch (e: Exception) {
            // Android 12+: ForegroundServiceStartNotAllowedException, Android 14+: SecurityException без RECORD_AUDIO.
            AppLog.e(TAG, "startForeground failed", e)
            voice.setWakeWordActive(false)
            false
        }
    }

    private fun fail(message: String?) {
        AppLog.w(TAG, "wake word failed")
        detector.release()
        voice.setWakeWordActive(false)
        WakeWordErrors.last.value = message ?: "Не удалось запустить прослушивание ключевой фразы"
        stopSelf()
    }

    private fun stopListening() {
        detector.release()
        voice.setWakeWordActive(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        detector.release()
        voice.setWakeWordActive(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeService"
        private const val ACTION_STOP = "ru.voiceassistant.action.STOP_LISTENING"

        /** Запускать только когда приложение на экране: таково требование Android 14 к микрофону. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AssistantForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AssistantForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Последняя ошибка wake word для показа в UI. */
object WakeWordErrors {
    val last = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
