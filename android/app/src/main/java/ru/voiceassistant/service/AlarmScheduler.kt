package ru.voiceassistant.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.voiceassistant.util.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/** Планирование напоминаний и таймеров. Интерфейс нужен для подмены в тестах. */
interface AlarmScheduler {
    /** @return true, если будильник точный; false — система может сдвинуть срабатывание. */
    fun scheduleReminder(id: Long, text: String, triggerAt: Long): Boolean
    fun cancelReminder(id: Long)
    fun scheduleTimer(timerId: Int, label: String, triggerAt: Long): Boolean
    fun canScheduleExact(): Boolean
}

@Singleton
class AndroidAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlarmScheduler {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun scheduleReminder(id: Long, text: String, triggerAt: Long): Boolean {
        val intent = ReminderReceiver.reminderIntent(context, id, text)
        return schedule(requestCode(id), intent, triggerAt)
    }

    override fun cancelReminder(id: Long) {
        val pi = PendingIntent.getBroadcast(
            context, requestCode(id),
            // PendingIntent сравнивается по action и компоненту, extras не учитываются.
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REMINDER),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    override fun scheduleTimer(timerId: Int, label: String, triggerAt: Long): Boolean {
        val intent = ReminderReceiver.timerIntent(context, timerId, label)
        return schedule(TIMER_BASE + timerId, intent, triggerAt)
    }

    private fun schedule(requestCode: Int, intent: Intent, triggerAt: Long): Boolean {
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return if (canScheduleExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                true
            } catch (e: SecurityException) {
                AppLog.w(TAG, "exact alarm denied, fallback to inexact", e)
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                false
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            false
        }
    }

    // Разные request code — разные PendingIntent. Напоминания: 1..; таймеры: 1_000_000_000+.
    private fun requestCode(reminderId: Long): Int = (reminderId % TIMER_BASE).toInt()

    private companion object {
        const val TAG = "Alarm"
        const val TIMER_BASE = 1_000_000_000
    }
}
