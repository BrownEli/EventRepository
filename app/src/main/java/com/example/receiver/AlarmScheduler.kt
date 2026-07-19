package com.example.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.Event
import java.util.Calendar

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

    fun isWorkEnvironment(): Boolean {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_work_environment", false)
    }

    fun getActiveReminderTypes(dateTimeMillis: Long): List<Triple<String, Long, Int>> {
        if (isWorkEnvironment()) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = dateTimeMillis
                set(Calendar.HOUR_OF_DAY, 8)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dailyDigestTime = cal.timeInMillis

            return listOf(
                Triple("Daily Digest", dailyDigestTime, 6),
                Triple("5 Minutes Before", dateTimeMillis - 5L * 60 * 1000, 7),
                Triple("1 Minute Before", dateTimeMillis - 1L * 60 * 1000, 8)
            )
        } else {
            return getReminderTypes(dateTimeMillis)
        }
    }

    fun scheduleAlarmsForEvent(event: Event) {
        val currentTime = System.currentTimeMillis()
        
        // Skip past events and clean up any remaining alarms
        if (event.dateTimeMillis < currentTime) {
            Log.d(TAG, "Event ${event.id} has already passed. Cancelling alarms.")
            cancelAlarmsForEvent(event)
            return
        }
        
        // Limit active alarms to events within 2 weeks (14 days) in the future
        val maxAlarmWindow = currentTime + 14L * 24 * 60 * 60 * 1000
        if (event.dateTimeMillis > maxAlarmWindow && event.id != -999) {
            Log.d(TAG, "Event ${event.id} is more than 2 weeks away. Cancelling any previously scheduled alarms.")
            cancelAlarmsForEvent(event)
            return
        }

        val reminders = getActiveReminderTypes(event.dateTimeMillis)

        for ((label, triggerTime, codeOffset) in reminders) {
            if (triggerTime > currentTime) {
                val requestCode = event.id * 10 + codeOffset
                scheduleAlarm(
                    triggerTime = triggerTime,
                    requestCode = requestCode,
                    eventId = event.id,
                    reminderLabel = label,
                    eventTitle = event.title,
                    isWorkday = event.isWorkday,
                    isMeeting = event.isMeeting
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
        isWorkday: Boolean,
        isMeeting: Boolean
    ) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("EVENT_ID", eventId)
            putExtra("REMINDER_LABEL", reminderLabel)
            putExtra("EVENT_TITLE", eventTitle)
            putExtra("IS_WORKDAY", isWorkday)
            putExtra("IS_MEETING", isMeeting)
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
        val allOffsets = listOf(1, 2, 3, 4, 5, 6, 7, 8)
        for (codeOffset in allOffsets) {
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
