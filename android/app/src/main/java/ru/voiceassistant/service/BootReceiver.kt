package ru.voiceassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ru.voiceassistant.data.repository.ReminderRepository
import ru.voiceassistant.util.AppLog
import ru.voiceassistant.util.TimeProvider

/**
 * После перезагрузки или обновления приложения AlarmManager забывает все будильники,
 * поэтому восстанавливаем активные напоминания из Room через WorkManager.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLog.i("Boot", "reschedule requested: ${intent.action}")
        RescheduleRemindersWorker.enqueue(context)
    }
}

@HiltWorker
class RescheduleRemindersWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reminders: ReminderRepository,
    private val scheduler: AlarmScheduler,
    private val time: TimeProvider,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = time.nowMillis()
        val active = reminders.getActive()
        var restored = 0
        active.forEach { r ->
            if (r.triggerAt > now) {
                scheduler.scheduleReminder(r.id, r.text, r.triggerAt)
                restored++
            } else {
                // Пропущено, пока телефон был выключен: показываем сразу.
                Notifications.showAlert(applicationContext, (10_000L + r.id).toInt(), "Пропущенное напоминание", r.text)
                reminders.deactivate(r.id)
            }
        }
        AppLog.i("Boot", "restored=$restored missed=${active.size - restored}")
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reschedule_reminders",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RescheduleRemindersWorker>().build(),
            )
        }
    }
}
