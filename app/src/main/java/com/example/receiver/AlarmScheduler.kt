package com.example.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.Event

class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        private const val TAG = "AlarmScheduler"

        fun getReminderTypes(dateTimeMillis: Long): List<Triple<String, Long, Int>> {
            return listOf(
                Triple("2 Weeks Before", dateTimeMillis - 14L * 24 * 60 * 60 * 1000, 1),
                Triple("1 Week Before", dateTimeMillis - 7L * 24 * 60 * 60 * 1000, 2),
                Triple("1 Day Before", dateTimeMillis - 1L * 24 * 60 * 60 * 1000, 3),
                Triple("1 Minute Before", dateTimeMillis - 1L * 60 * 1000, 4),
                Triple("Day of the Event", dateTimeMillis, 5)
            )
        }
    }

    fun scheduleAlarmsForEvent(event: Event) {
        val currentTime = System.currentTimeMillis()
        val reminders = getReminderTypes(event.dateTimeMillis)

        for ((label, triggerTime, codeOffset) in reminders) {
            if (triggerTime > currentTime) {
                val requestCode = event.id * 10 + codeOffset
                scheduleAlarm(
                    triggerTime = triggerTime,
                    requestCode = requestCode,
                    eventId = event.id,
                    reminderLabel = label,
                    eventTitle = event.title,
                    isWorkday = event.isWorkday
                )
            } else {
                Log.d(TAG, "Skipping past reminder '$label' for event ${event.id} (Scheduled trigger: $triggerTime, current: $currentTime)")
            }
        }
    }

    private fun scheduleAlarm(
        triggerTime: Long,
        requestCode: Int,
        eventId: Int,
        reminderLabel: String,
        eventTitle: String,
        isWorkday: Boolean
    ) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("EVENT_ID", eventId)
            putExtra("REMINDER_LABEL", reminderLabel)
            putExtra("EVENT_TITLE", eventTitle)
            putExtra("IS_WORKDAY", isWorkday)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d(TAG, "Scheduled exact alarm for Event $eventId ($reminderLabel) at $triggerTime (Request code $requestCode)")
                } else {
                    // Fallback to inexact exact-like
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.w(TAG, "No exact alarm permission. Fallback used for Event $eventId ($reminderLabel)")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact alarm for Event $eventId ($reminderLabel) at $triggerTime on older API")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while scheduling exact alarm: ${e.message}. Using fallback.", e)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    fun cancelAlarmsForEvent(event: Event) {
        val reminders = getReminderTypes(event.dateTimeMillis)
        for ((_, _, codeOffset) in reminders) {
            val requestCode = event.id * 10 + codeOffset
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.d(TAG, "Cancelled scheduled alarm request code $requestCode for Event ${event.id}")
            }
        }
    }
}
