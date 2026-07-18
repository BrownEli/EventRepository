package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.EventRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device boot completed. Rescheduling all alarms.")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val repository = EventRepository(db.eventDao())
                    val alarmScheduler = AlarmScheduler(context)
                    val events = repository.getAllEventsList()
                    
                    for (event in events) {
                        alarmScheduler.scheduleAlarmsForEvent(event)
                    }
                    Log.d("BootReceiver", "Rescheduled alarms for ${events.size} events.")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error rescheduling alarms: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
