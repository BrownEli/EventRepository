package com.example.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Event
import com.example.data.EventRepository
import com.example.receiver.AlarmScheduler
import com.example.service.AlarmService
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EventViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: EventRepository
    private val alarmScheduler: AlarmScheduler

    val allEvents: StateFlow<List<Event>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = EventRepository(database.eventDao())
        alarmScheduler = AlarmScheduler(application)

        allEvents = repository.allEvents.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun addEvent(title: String, description: String, dateTimeMillis: Long, isWorkday: Boolean) {
        viewModelScope.launch {
            try {
                val event = Event(
                    title = title,
                    description = description,
                    dateTimeMillis = dateTimeMillis,
                    isWorkday = isWorkday,
                    isEmailSent = false
                )
                val newId = repository.insert(event)
                val insertedEvent = event.copy(id = newId.toInt())
                
                // Schedule alarms for the event
                alarmScheduler.scheduleAlarmsForEvent(insertedEvent)
                com.example.receiver.EventWidgetProvider.triggerUpdate(getApplication())
                Log.d("EventViewModel", "Event added and alarms scheduled. ID: $newId")
            } catch (e: Exception) {
                Log.e("EventViewModel", "Error adding event: ${e.message}", e)
            }
        }
    }

    fun deleteEvent(event: Event) {
        viewModelScope.launch {
            try {
                // Cancel existing alarms
                alarmScheduler.cancelAlarmsForEvent(event)
                repository.delete(event)
                com.example.receiver.EventWidgetProvider.triggerUpdate(getApplication())
                Log.d("EventViewModel", "Event deleted and alarms cancelled. ID: ${event.id}")
            } catch (e: Exception) {
                Log.e("EventViewModel", "Error deleting event: ${e.message}", e)
            }
        }
    }

    fun toggleEmailSent(event: Event) {
        viewModelScope.launch {
            try {
                val updatedEvent = event.copy(isEmailSent = !event.isEmailSent)
                repository.update(updatedEvent)
                com.example.receiver.EventWidgetProvider.triggerUpdate(getApplication())
                Log.d("EventViewModel", "Toggled email sent status for Event: ${event.id}")
            } catch (e: Exception) {
                Log.e("EventViewModel", "Error toggling email sent status: ${e.message}", e)
            }
        }
    }

    /**
     * Triggers an immediate alarm for testing purposes (starts in 5 seconds)
     */
    fun triggerInstantTestAlarm(title: String, isWorkday: Boolean) {
        viewModelScope.launch {
            try {
                val testTime = System.currentTimeMillis() + 5000L // 5 seconds from now
                val testEvent = Event(
                    id = -999, // Use a special negative ID for testing
                    title = "[TEST] $title",
                    description = "This is a test alarm to verify sound, vibration, and sticky notification actions.",
                    dateTimeMillis = testTime,
                    isWorkday = isWorkday,
                    isEmailSent = false
                )
                
                // Schedule the test alarm
                alarmScheduler.scheduleAlarmsForEvent(testEvent)
                Log.d("EventViewModel", "Instant test alarm scheduled for 5 seconds from now.")
            } catch (e: Exception) {
                Log.e("EventViewModel", "Error scheduling test alarm: ${e.message}", e)
            }
        }
    }
    
    /**
     * Synchronize events from the device's Google Calendar using CalendarContract content provider.
     * Returns the count of newly synchronized and scheduled events, or negative values on error/no permission.
     */
    fun syncGoogleCalendar(context: Context, onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_CALENDAR
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    onComplete(-1) // Permission not granted
                    return@launch
                }

                val addedCount = withContext(Dispatchers.IO) {
                    var count = 0
                    val contentResolver = context.contentResolver
                    val uri = CalendarContract.Events.CONTENT_URI
                    
                    val projection = arrayOf(
                        CalendarContract.Events._ID,
                        CalendarContract.Events.TITLE,
                        CalendarContract.Events.DESCRIPTION,
                        CalendarContract.Events.DTSTART,
                        CalendarContract.Events.CALENDAR_DISPLAY_NAME
                    )
                    
                    val now = System.currentTimeMillis()
                    // Fetch events starting from 3 weeks ago (21 days) to 30 days in the future
                    val startMillis = now - 3L * 7 * 24 * 60 * 60 * 1000
                    val endMillis = now + 30L * 24 * 60 * 60 * 1000
                    
                    val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} = 0"
                    val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())
                    
                    val cursor = contentResolver.query(
                        uri,
                        projection,
                        selection,
                        selectionArgs,
                        "${CalendarContract.Events.DTSTART} ASC"
                    )
                    
                    cursor?.use { c ->
                        val idCol = c.getColumnIndex(CalendarContract.Events._ID)
                        val titleCol = c.getColumnIndex(CalendarContract.Events.TITLE)
                        val descCol = c.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                        val dtStartCol = c.getColumnIndex(CalendarContract.Events.DTSTART)
                        val calNameCol = c.getColumnIndex(CalendarContract.Events.CALENDAR_DISPLAY_NAME)
                        
                        val currentEvents = repository.getAllEventsList()
                        
                        while (c.moveToNext()) {
                            val title = if (titleCol >= 0) c.getString(titleCol) ?: "Untitled Event" else "Untitled Event"
                            val desc = if (descCol >= 0) c.getString(descCol) ?: "" else ""
                            val dtStart = if (dtStartCol >= 0) c.getLong(dtStartCol) else 0L
                            val calName = if (calNameCol >= 0) c.getString(calNameCol) ?: "" else ""
                            
                            if (dtStart < now - 3L * 7 * 24 * 60 * 60 * 1000) continue // Skip events older than 3 weeks
                            
                            // Check if it already exists by title and dateTimeMillis
                            val alreadyExists = currentEvents.any { 
                                it.title == title && Math.abs(it.dateTimeMillis - dtStart) < 5000 
                            }
                            
                            if (!alreadyExists) {
                                // Find out if it's weekday
                                val calendar = Calendar.getInstance().apply { timeInMillis = dtStart }
                                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                                val isWeekday = dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY
                                
                                val newEvent = Event(
                                    title = title,
                                    description = desc.ifBlank { "Synced from calendar: $calName" },
                                    dateTimeMillis = dtStart,
                                    isWorkday = isWeekday,
                                    isEmailSent = false
                                )
                                val newId = repository.insert(newEvent)
                                val insertedEvent = newEvent.copy(id = newId.toInt())
                                
                                // Schedule alarms
                                alarmScheduler.scheduleAlarmsForEvent(insertedEvent)
                                count++
                            }
                        }
                    }
                    count
                }
                
                if (addedCount > 0) {
                    com.example.receiver.EventWidgetProvider.triggerUpdate(getApplication())
                }
                onComplete(addedCount)
            } catch (e: Exception) {
                Log.e("EventViewModel", "Failed to sync calendar: ${e.message}", e)
                onComplete(-2)
            }
        }
    }

    /**
     * Stop any active alarm service manually from the app UI
     */
    fun stopActiveAlarmService() {
        try {
            val intent = Intent(getApplication(), AlarmService::class.java)
            getApplication<Application>().stopService(intent)
            Log.d("EventViewModel", "Requested manual stop of AlarmService")
        } catch (e: Exception) {
            Log.e("EventViewModel", "Error stopping active alarm service: ${e.message}", e)
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(EventViewModel::class.java)) {
                return EventViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
