package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Event
import com.example.data.EventRepository
import com.example.receiver.AlarmScheduler
import com.example.service.AlarmService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
