package ru.voiceassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.CallSuper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.voiceassistant.data.repository.ReminderRepository
import javax.inject.Inject

/**
 * Hilt внедряет зависимости в BroadcastReceiver внутри super.onReceive(), а у BroadcastReceiver
 * этот метод абстрактный — Kotlin не даст его вызвать. Промежуточный класс решает проблему.
 */
abstract class HiltBroadcastReceiver : BroadcastReceiver() {
    @CallSuper
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** Показывает уведомление о напоминании или окончании таймера. */
@AndroidEntryPoint
class ReminderReceiver : HiltBroadcastReceiver() {

    @Inject lateinit var reminders: ReminderRepository

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REMINDER -> {
                val id = intent.getLongExtra(EXTRA_ID, -1)
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                Notifications.showAlert(context, (NOTIFY_BASE + id).toInt(), "Напоминание", text)
                if (id > 0) {
                    val pending = goAsync()
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        try {
                            reminders.deactivate(id)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
            ACTION_TIMER -> {
                val id = intent.getIntExtra(EXTRA_TIMER_ID, 0)
                val label = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                Notifications.showAlert(context, TIMER_NOTIFY_BASE + id % 100_000, "Таймер",
                    if (label.isBlank()) "Время вышло" else "Время вышло: $label")
            }
        }
    }

    companion object {
        const val ACTION_REMINDER = "ru.voiceassistant.action.REMINDER"
        const val ACTION_TIMER = "ru.voiceassistant.action.TIMER"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TIMER_ID = "timer_id"
        private const val EXTRA_TEXT = "text"
        private const val NOTIFY_BASE = 10_000L
        private const val TIMER_NOTIFY_BASE = 900_000

        fun reminderIntent(context: Context, id: Long, text: String) =
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_REMINDER)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TEXT, text)

        fun timerIntent(context: Context, timerId: Int, label: String) =
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_TIMER)
                .putExtra(EXTRA_TIMER_ID, timerId)
                .putExtra(EXTRA_TEXT, label)
    }
}
