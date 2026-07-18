package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.service.AlarmService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getIntExtra("EVENT_ID", -1)
        val reminderLabel = intent.getStringExtra("REMINDER_LABEL") ?: "Event Reminder"
        val eventTitle = intent.getStringExtra("EVENT_TITLE") ?: "Event"
        val isWorkday = intent.getBooleanExtra("IS_WORKDAY", false)

        Log.d("AlarmReceiver", "Alarm received for event: $eventId, Title: $eventTitle, Label: $reminderLabel, isWorkday: $isWorkday")

        if (eventId != -1) {
            val serviceIntent = Intent(context, AlarmService::class.java).apply {
                putExtra("EVENT_ID", eventId)
                putExtra("REMINDER_LABEL", reminderLabel)
                putExtra("EVENT_TITLE", eventTitle)
                putExtra("IS_WORKDAY", isWorkday)
            }
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Log.e("AlarmReceiver", "Failed to start AlarmService: ${e.message}", e)
            }
        }
    }
}
