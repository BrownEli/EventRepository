package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.EventRepository
import com.example.receiver.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var isSilenced = false

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "calendar_alarm_channel"
        private const val NOTIFICATION_ID = 2026

        const val ACTION_START = "com.example.action.START_ALARM"
        const val ACTION_SILENCE = "com.example.action.SILENCE_ALARM"
        const val ACTION_SNOOZE = "com.example.action.SNOOZE_ALARM"
        const val ACTION_MARK_SENT = "com.example.action.MARK_EMAIL_SENT"
        const val ACTION_DISMISS = "com.example.action.DISMISS_ALARM"
        const val ACTION_NOTIFICATION_DISMISSED = "com.example.action.NOTIFICATION_DISMISSED"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val eventId = intent?.getIntExtra("EVENT_ID", -1) ?: -1
        val reminderLabel = intent?.getStringExtra("REMINDER_LABEL") ?: "Event Reminder"
        val eventTitle = intent?.getStringExtra("EVENT_TITLE") ?: "Calendar Event"
        val isWorkday = intent?.getBooleanExtra("IS_WORKDAY", false) ?: false
        val action = intent?.action ?: ACTION_START

        Log.d(TAG, "onStartCommand: action=$action, eventId=$eventId, title=$eventTitle, isWorkday=$isWorkday")

        if (eventId == -1) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START, ACTION_START + "_ALT" -> {
                // Play alarm and start vibration
                startAlarmSound()
                startVibration()
                // Show initial alarm notification
                val notification = buildAlarmNotification(eventId, eventTitle, reminderLabel, isWorkday, false)
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_SILENCE -> {
                stopAlarmSound()
                stopVibration()
                isSilenced = true
                
                if (isWorkday) {
                    // Update notification to remain sticky, telling them they must mark email as sent
                    val notification = buildAlarmNotification(eventId, eventTitle, reminderLabel, isWorkday = true, silenced = true)
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, notification)
                    Log.d(TAG, "Alarm silenced. Sticky workday email notification remains.")
                } else {
                    // For non-workday events, silence stops the service completely
                    Log.d(TAG, "Alarm silenced. Non-workday event: stopping service.")
                    stopSelf()
                }
            }
            ACTION_SNOOZE -> {
                stopAlarmSound()
                stopVibration()
                snoozeAlarm(eventId, eventTitle, reminderLabel, isWorkday)
                stopSelf()
            }
            ACTION_MARK_SENT -> {
                stopAlarmSound()
                stopVibration()
                markEmailAsSent(eventId)
                stopSelf()
            }
            ACTION_DISMISS -> {
                stopAlarmSound()
                stopVibration()
                stopSelf()
            }
            ACTION_NOTIFICATION_DISMISSED -> {
                Log.d(TAG, "Notification swiped/dismissed: stopping alarm service.")
                stopAlarmSound()
                stopVibration()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startAlarmSound() {
        if (mediaPlayer != null) return
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmService, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start media player: ${e.message}", e)
        }
    }

    private fun stopAlarmSound() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player: ${e.message}")
        } finally {
            mediaPlayer = null
        }
    }

    private fun startVibration() {
        if (vibrator != null) return
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 1000, 1000) // Vibrate 1s, pause 1s
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vibration: ${e.message}")
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration: ${e.message}")
        } finally {
            vibrator = null
        }
    }

    private fun snoozeAlarm(eventId: Int, eventTitle: String, reminderLabel: String, isWorkday: Boolean) {
        val snoozeTime = System.currentTimeMillis() + 5 * 60 * 1000L // 5 minutes
        val alarmScheduler = AlarmScheduler(this)
        
        // We use codeOffset = 99 for snoozed alarms to avoid collisions
        val requestCode = eventId * 10 + 99
        val intent = Intent(this, com.example.receiver.AlarmReceiver::class.java).apply {
            putExtra("EVENT_ID", eventId)
            putExtra("REMINDER_LABEL", "$reminderLabel (Snoozed)")
            putExtra("EVENT_TITLE", eventTitle)
            putExtra("IS_WORKDAY", isWorkday)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        try {
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                snoozeTime,
                pendingIntent
            )
            Log.d(TAG, "Alarm snoozed for 5 minutes (Event $eventId, request code $requestCode)")
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                snoozeTime,
                pendingIntent
            )
        }
    }

    private fun markEmailAsSent(eventId: Int) {
        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@AlarmService)
                val repository = EventRepository(db.eventDao())
                val event = repository.getEventById(eventId)
                if (event != null) {
                    repository.update(event.copy(isEmailSent = true))
                    Log.d(TAG, "Marked event $eventId email as sent.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error marking email as sent: ${e.message}", e)
            }
        }
    }

    private fun buildAlarmNotification(
        eventId: Int,
        eventTitle: String,
        reminderLabel: String,
        isWorkday: Boolean,
        silenced: Boolean
    ): Notification {
        // Main intent to open app
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            eventId * 10 + 100,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Notification builders
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(appPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(true)
            .setColor(0xFF4F378B.toInt())
            .setColorized(true)

        val deleteIntent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_NOTIFICATION_DISMISSED
            putExtra("EVENT_ID", eventId)
            putExtra("REMINDER_LABEL", reminderLabel)
            putExtra("EVENT_TITLE", eventTitle)
            putExtra("IS_WORKDAY", isWorkday)
            putExtra("SILENCED", silenced)
        }
        val deletePendingIntent = PendingIntent.getService(
            this,
            eventId * 10 + 107,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setDeleteIntent(deletePendingIntent)

        if (isWorkday) {
            if (silenced) {
                // Alarm sound is stopped, but email is pending - sticky system notification remains
                val title = "✉️ Workday Protocol: Mark Email Sent"
                val body = "🛡️ WORKDAY EXCEPTION RUNNING\n\nEvent: $eventTitle\nStatus: Alarm silenced, but notification remains active.\n\n⚠️ Action Required:\nYou must mark the email to your manager as sent to dismiss this notification completely."
                
                builder.setContentTitle(title)
                    .setContentText("Workday event: mark email sent to dismiss.")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setOngoing(false)

                // Button to mark sent and fully dismiss
                val markSentIntent = Intent(this, AlarmService::class.java).apply {
                    action = ACTION_MARK_SENT
                    putExtra("EVENT_ID", eventId)
                }
                val markSentPendingIntent = PendingIntent.getService(
                    this,
                    eventId * 10 + 101,
                    markSentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_menu_send,
                    "Mark Email as Sent",
                    markSentPendingIntent
                )
            } else {
                // Ringing alarm
                val title = "🚨 ALARM: $eventTitle"
                val body = "⏰ Reminder: $reminderLabel\n\n💼 WORKDAY MODE ACTIVE\nThis is a workday event. Send an email to your job if it breaks standard work hours!"
                
                builder.setContentTitle(title)
                    .setContentText("Workday Event! Email is required.")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setOngoing(false)

                // Button to silence sound
                val silenceIntent = Intent(this, AlarmService::class.java).apply {
                    action = ACTION_SILENCE
                    putExtra("EVENT_ID", eventId)
                    putExtra("REMINDER_LABEL", reminderLabel)
                    putExtra("EVENT_TITLE", eventTitle)
                    putExtra("IS_WORKDAY", isWorkday)
                }
                val silencePendingIntent = PendingIntent.getService(
                    this,
                    eventId * 10 + 102,
                    silenceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Silence Alarm Sound",
                    silencePendingIntent
                )

                // Button to Snooze 5 minutes
                val snoozeIntent = Intent(this, AlarmService::class.java).apply {
                    action = ACTION_SNOOZE
                    putExtra("EVENT_ID", eventId)
                    putExtra("REMINDER_LABEL", reminderLabel)
                    putExtra("EVENT_TITLE", eventTitle)
                    putExtra("IS_WORKDAY", isWorkday)
                }
                val snoozePendingIntent = PendingIntent.getService(
                    this,
                    eventId * 10 + 103,
                    snoozeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_menu_recent_history,
                    "Snooze (5 min)",
                    snoozePendingIntent
                )

                // Button to Mark Sent directly and stop alarm
                val markSentIntent = Intent(this, AlarmService::class.java).apply {
                    action = ACTION_MARK_SENT
                    putExtra("EVENT_ID", eventId)
                }
                val markSentPendingIntent = PendingIntent.getService(
                    this,
                    eventId * 10 + 104,
                    markSentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_menu_send,
                    "Mark Email Sent & Stop",
                    markSentPendingIntent
                )
            }
        } else {
            // Non-workday alarm
            val title = "⏰ ALARM: $eventTitle"
            val body = "📅 Calendar Event Alarm\nReminder: $reminderLabel\n\nPress Snooze to be reminded in 5 minutes, or Dismiss to turn off."
            
            builder.setContentTitle(title)
                .setContentText("Appointment reminder: $reminderLabel")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setOngoing(false)

            // Button to Snooze
            val snoozeIntent = Intent(this, AlarmService::class.java).apply {
                action = ACTION_SNOOZE
                putExtra("EVENT_ID", eventId)
                putExtra("REMINDER_LABEL", reminderLabel)
                putExtra("EVENT_TITLE", eventTitle)
                putExtra("IS_WORKDAY", isWorkday)
            }
            val snoozePendingIntent = PendingIntent.getService(
                this,
                eventId * 10 + 105,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_recent_history,
                "Snooze (5 min)",
                snoozePendingIntent
            )

            // Button to Dismiss
            val dismissIntent = Intent(this, AlarmService::class.java).apply {
                action = ACTION_DISMISS
                putExtra("EVENT_ID", eventId)
            }
            val dismissPendingIntent = PendingIntent.getService(
                this,
                eventId * 10 + 106,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss",
                dismissPendingIntent
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Calendar Event Alarms Channel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Used to display high-priority persistent alarms for appointments and deadlines"
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopAlarmSound()
        stopVibration()
        super.onDestroy()
        Log.d(TAG, "AlarmService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
