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
    private val sharedPrefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    val allEvents: StateFlow<List<Event>>
    
    val isWorkEnvironmentState = kotlinx.coroutines.flow.MutableStateFlow(
        sharedPrefs.getBoolean("is_work_environment", false)
    )
    val isWorkEnvironment: StateFlow<Boolean> = isWorkEnvironmentState

    val userEmailState = kotlinx.coroutines.flow.MutableStateFlow(
        sharedPrefs.getString("user_email", "") ?: ""
    )
    val userEmail: StateFlow<String> = userEmailState

    val userNameState = kotlinx.coroutines.flow.MutableStateFlow(
        sharedPrefs.getString("user_name", "") ?: ""
    )
    val userName: StateFlow<String> = userNameState

    val isGcalSyncEnabledState = kotlinx.coroutines.flow.MutableStateFlow(
        sharedPrefs.getBoolean("gcal_sync_enabled", false)
    )
    val isGcalSyncEnabled: StateFlow<Boolean> = isGcalSyncEnabledState

    fun toggleGcalSync(context: Context, onComplete: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val newValue = !isGcalSyncEnabledState.value
            sharedPrefs.edit().putBoolean("gcal_sync_enabled", newValue).apply()
            isGcalSyncEnabledState.value = newValue
            
            // Setup or cancel periodic background sync
            com.example.service.SyncWorker.scheduleSync(getApplication())

            if (newValue) {
                syncGoogleCalendar(context) { count ->
                    onComplete(count)
                }
            } else {
                onComplete(0)
            }
        }
    }

    fun saveUserIdentity(email: String, name: String) {
        viewModelScope.launch {
            sharedPrefs.edit()
                .putString("user_email", email.trim())
                .putString("user_name", name.trim())
                .apply()
            userEmailState.value = email.trim()
            userNameState.value = name.trim()
        }
    }

    init {
        val savedEmail = sharedPrefs.getString("user_email", "") ?: ""
        val savedName = sharedPrefs.getString("user_name", "") ?: ""
        userEmailState.value = savedEmail
        userNameState.value = savedName

        val database = AppDatabase.getDatabase(application)
        repository = EventRepository(database.eventDao())
        alarmScheduler = AlarmScheduler(application)

        allEvents = repository.allEvents.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Setup periodic background sync
        com.example.service.SyncWorker.scheduleSync(application)
    }

    fun toggleWorkEnvironment() {
        viewModelScope.launch {
            val newValue = !isWorkEnvironmentState.value
            sharedPrefs.edit().putBoolean("is_work_environment", newValue).apply()
            isWorkEnvironmentState.value = newValue
            
            // Reschedule periodic background sync with updated frequency
            com.example.service.SyncWorker.scheduleSync(getApplication())

            // Reschedule all alarms
            rescheduleAllAlarms()
        }
    }

    private fun rescheduleAllAlarms() {
        viewModelScope.launch {
            try {
                val events = repository.getAllEventsList()
                for (event in events) {
                    alarmScheduler.cancelAlarmsForEvent(event)
                    alarmScheduler.scheduleAlarmsForEvent(event)
                }
                Log.d("EventViewModel", "Rescheduled alarms for ${events.size} events after switching environment.")
            } catch (e: Exception) {
                Log.e("EventViewModel", "Error rescheduling alarms: ${e.message}", e)
            }
        }
    }

    fun addEvent(title: String, description: String, dateTimeMillis: Long, isWorkday: Boolean, isImportant: Boolean = false) {
        viewModelScope.launch {
            try {
                val event = Event(
                    title = title,
                    description = description,
                    dateTimeMillis = dateTimeMillis,
                    isWorkday = isWorkday,
                    isEmailSent = false,
                    isImportant = isImportant
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

                // Attempts to load user's calendar email as default if currently blank
                tryLoadDefaultEmailFromCalendar(context)

                val addedCount = withContext(Dispatchers.IO) {
                    var count = 0
                    val contentResolver = context.contentResolver
                    
                    // Determine personal calendar IDs and display names from the device
                    val personalCalendarIds = mutableSetOf<Long>()
                    val personalCalendarNames = mutableSetOf<String>()
                    val currentUserEmail = userEmailState.value.trim()
                    val currentUserName = userNameState.value.trim()
                    val emailPrefix = if (currentUserEmail.contains("@")) currentUserEmail.substringBefore("@") else ""
                    val firstName = if (currentUserName.contains(" ")) currentUserName.substringBefore(" ") else currentUserName

                    try {
                        val calUri = CalendarContract.Calendars.CONTENT_URI
                        val calProjection = arrayOf(
                            CalendarContract.Calendars._ID,
                            CalendarContract.Calendars.ACCOUNT_NAME,
                            CalendarContract.Calendars.OWNER_ACCOUNT,
                            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                            CalendarContract.Calendars.IS_PRIMARY
                        )
                        contentResolver.query(calUri, calProjection, null, null, null)?.use { cursor ->
                            val idCol = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                            val accCol = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                            val ownerCol = cursor.getColumnIndex(CalendarContract.Calendars.OWNER_ACCOUNT)
                            val nameCol = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                            val primaryCol = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)

                            while (cursor.moveToNext()) {
                                val id = if (idCol >= 0) cursor.getLong(idCol) else -1L
                                val accountName = if (accCol >= 0) cursor.getString(accCol) ?: "" else ""
                                val ownerAccount = if (ownerCol >= 0) cursor.getString(ownerCol) ?: "" else ""
                                val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                                val isPrimary = if (primaryCol >= 0) cursor.getInt(primaryCol) == 1 else false

                                val isPersonal = isPrimary ||
                                        (currentUserEmail.isNotBlank() && (
                                            accountName.equals(currentUserEmail, ignoreCase = true) ||
                                            ownerAccount.equals(currentUserEmail, ignoreCase = true) ||
                                            displayName.contains(currentUserEmail, ignoreCase = true)
                                        )) ||
                                        (currentUserName.isNotBlank() && displayName.equals(currentUserName, ignoreCase = true)) ||
                                        displayName.contains("personal", ignoreCase = true) ||
                                        displayName.contains("private", ignoreCase = true) ||
                                        displayName.contains("my calendar", ignoreCase = true) ||
                                        displayName.equals("Calendar", ignoreCase = true) ||
                                        displayName.equals("Birthdays", ignoreCase = true) ||
                                        displayName.equals("Tasks", ignoreCase = true) ||
                                        (emailPrefix.isNotBlank() && displayName.contains(emailPrefix, ignoreCase = true)) ||
                                        (firstName.isNotBlank() && displayName.contains(firstName, ignoreCase = true))

                                if (id != -1L && isPersonal) {
                                    personalCalendarIds.add(id)
                                    if (displayName.isNotBlank()) {
                                        personalCalendarNames.add(displayName.trim().lowercase())
                                    }
                                    Log.d("EventViewModel", "Found synced identity/personal calendar: ID=$id, Name=$displayName, Account=$accountName, Primary=$isPrimary")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("EventViewModel", "Failed to query calendars table: ${e.message}")
                    }

                    val uri = CalendarContract.Events.CONTENT_URI
                    val projection = arrayOf(
                        CalendarContract.Events._ID,
                        CalendarContract.Events.TITLE,
                        CalendarContract.Events.DESCRIPTION,
                        CalendarContract.Events.DTSTART,
                        CalendarContract.Events.CALENDAR_DISPLAY_NAME,
                        CalendarContract.Events.EVENT_LOCATION,
                        CalendarContract.Events.ORGANIZER,
                        CalendarContract.Events.CALENDAR_ID
                    )
                    
                    val now = System.currentTimeMillis()
                    // Fetch upcoming events starting from now up to 3 weeks (21 days) in the future
                    val startMillis = now
                    val endMillis = now + 3L * 7 * 24 * 60 * 60 * 1000
                    
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
                        val locCol = c.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                        val orgCol = c.getColumnIndex(CalendarContract.Events.ORGANIZER)
                        val calIdCol = c.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
                        
                        val currentEvents = repository.getAllEventsList()
                        
                        while (c.moveToNext()) {
                            val title = if (titleCol >= 0) c.getString(titleCol) ?: "Untitled Event" else "Untitled Event"
                            val desc = if (descCol >= 0) c.getString(descCol) ?: "" else ""
                            val dtStart = if (dtStartCol >= 0) c.getLong(dtStartCol) else 0L
                            val calName = if (calNameCol >= 0) c.getString(calNameCol) ?: "" else ""
                            val location = if (locCol >= 0) c.getString(locCol) ?: "" else ""
                            val organizer = if (orgCol >= 0) c.getString(orgCol) ?: "" else ""
                            val calId = if (calIdCol >= 0) c.getLong(calIdCol) else -1L
                            
                            if (dtStart < now) continue // Skip passed events completely
                            
                            // Filtering out company-wide events that are not related to the user's email or specific calendar
                            val calendarDisplayName = calName.trim()
                            
                            // Explicitly filter out Birthdays and Tasks calendars
                            val isBirthdaysCalendar = calendarDisplayName.equals("Birthdays", ignoreCase = true) || 
                                                      calendarDisplayName.contains("birthday", ignoreCase = true)
                            val isTasksCalendar = calendarDisplayName.equals("Tasks", ignoreCase = true)
                            
                            val isPersonalCalendar = personalCalendarIds.contains(calId) ||
                                    personalCalendarNames.contains(calendarDisplayName.lowercase()) ||
                                    (currentUserName.isNotBlank() && calendarDisplayName.equals(currentUserName, ignoreCase = true)) ||
                                    calendarDisplayName.contains("personal", ignoreCase = true) ||
                                    calendarDisplayName.contains("private", ignoreCase = true) ||
                                    calendarDisplayName.contains("my calendar", ignoreCase = true) ||
                                    (currentUserEmail.isNotBlank() && calendarDisplayName.contains(currentUserEmail, ignoreCase = true)) ||
                                    (emailPrefix.isNotBlank() && calendarDisplayName.contains(emailPrefix, ignoreCase = true)) ||
                                    (firstName.isNotBlank() && calendarDisplayName.contains(firstName, ignoreCase = true))
                            
                            val isRelatedToUserText = (currentUserEmail.isNotBlank() && (
                                    organizer.contains(currentUserEmail, ignoreCase = true) ||
                                    title.contains(currentUserEmail, ignoreCase = true) ||
                                    desc.contains(currentUserEmail, ignoreCase = true) ||
                                    location.contains(currentUserEmail, ignoreCase = true)
                                )) || (firstName.isNotBlank() && (
                                    title.contains(firstName, ignoreCase = true) ||
                                    desc.contains(firstName, ignoreCase = true) ||
                                    location.contains(firstName, ignoreCase = true)
                                ))
                            
                            // Check if the user is an attendee of this event
                            val eventId = if (idCol >= 0) c.getLong(idCol) else -1L
                            val isUserAttendee = if (eventId != -1L && currentUserEmail.isNotBlank()) {
                                var found = false
                                try {
                                    val attendeesUri = CalendarContract.Attendees.CONTENT_URI
                                    val attendeesProjection = arrayOf(
                                        CalendarContract.Attendees.ATTENDEE_EMAIL,
                                        CalendarContract.Attendees.ATTENDEE_NAME
                                    )
                                    val selection = "${CalendarContract.Attendees.EVENT_ID} = ?"
                                    val selectionArgs = arrayOf(eventId.toString())
                                    context.contentResolver.query(
                                        attendeesUri,
                                        attendeesProjection,
                                        selection,
                                        selectionArgs,
                                        null
                                    )?.use { attendeeCursor ->
                                        val emailIdx = attendeeCursor.getColumnIndex(CalendarContract.Attendees.ATTENDEE_EMAIL)
                                        val nameIdx = attendeeCursor.getColumnIndex(CalendarContract.Attendees.ATTENDEE_NAME)
                                        while (attendeeCursor.moveToNext()) {
                                            val attEmail = if (emailIdx >= 0) attendeeCursor.getString(emailIdx) ?: "" else ""
                                            val attName = if (nameIdx >= 0) attendeeCursor.getString(nameIdx) ?: "" else ""
                                            if (attEmail.equals(currentUserEmail, ignoreCase = true) ||
                                                (emailPrefix.isNotBlank() && attEmail.contains(emailPrefix, ignoreCase = true)) ||
                                                (currentUserName.isNotBlank() && attName.contains(currentUserName, ignoreCase = true))
                                            ) {
                                                found = true
                                                break
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("EventViewModel", "Failed to check attendees for event $eventId: ${e.message}")
                                }
                                found
                            } else {
                                false
                            }
                            
                            // Filter out vacations / out-of-office events of other people
                            val isOtherPersonVacation = (title.contains("OOO", ignoreCase = true) || 
                                                         title.contains("vacation", ignoreCase = true) || 
                                                         title.contains("out of office", ignoreCase = true) || 
                                                         title.contains("leave", ignoreCase = true)) && 
                                                        !isPersonalCalendar && !isUserAttendee && !organizer.equals(currentUserEmail, ignoreCase = true)
                            
                            val shouldImport = !isBirthdaysCalendar && !isTasksCalendar && !isOtherPersonVacation && (
                                isPersonalCalendar || isUserAttendee || organizer.equals(currentUserEmail, ignoreCase = true) || isRelatedToUserText
                            )
                            
                            // If it's not a personal calendar AND not specifically related to their email/identity, skip importing
                            if (!shouldImport) {
                                Log.d("EventViewModel", "Skipping unrelated company event, holiday/birthday, or other vacation: Title=$title, Calendar=$calName")
                                continue
                            }
                            
                            // Check if it already exists by title and dateTimeMillis
                            val alreadyExists = currentEvents.any { 
                                it.title == title && Math.abs(it.dateTimeMillis - dtStart) < 5000 
                            }
                            
                            if (!alreadyExists) {
                                // Find out if it's a workday (Sunday to Thursday)
                                val calendar = Calendar.getInstance().apply { timeInMillis = dtStart }
                                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                                val isWorkdayDay = dayOfWeek != Calendar.FRIDAY && dayOfWeek != Calendar.SATURDAY
                                
                                // Format the description to preserve Google Meet links and indicate the calendar source
                                var finalDesc = desc
                                if (location.isNotBlank() && !finalDesc.contains(location, ignoreCase = true)) {
                                    finalDesc = if (finalDesc.isBlank()) location else "$finalDesc\n\nLocation: $location"
                                }
                                if (location.contains("meet.google.com", ignoreCase = true) && !finalDesc.contains("meet.google.com", ignoreCase = true)) {
                                    finalDesc = "$finalDesc\n\nGoogle Meet: $location"
                                }
                                finalDesc = if (finalDesc.isNotBlank()) {
                                    "$finalDesc\n\n[Calendar: $calName]"
                                } else {
                                    "Synced from calendar: $calName"
                                }
                                
                                val newEvent = Event(
                                    title = title,
                                    description = finalDesc,
                                    dateTimeMillis = dtStart,
                                    isWorkday = isWorkdayDay,
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
     * Attempts to read the first Google Calendar account/owner email address on the device
     * and sets it as the default email if the user's stored email is blank.
     */
    fun tryLoadDefaultEmailFromCalendar(context: Context) {
        viewModelScope.launch {
            if (userEmailState.value.isBlank()) {
                val email = withContext(Dispatchers.IO) {
                    try {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_CALENDAR
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            val uri = CalendarContract.Calendars.CONTENT_URI
                            val projection = arrayOf(
                                CalendarContract.Calendars.ACCOUNT_NAME,
                                CalendarContract.Calendars.OWNER_ACCOUNT,
                                CalendarContract.Calendars.IS_PRIMARY
                            )
                            val sortOrder = "${CalendarContract.Calendars.IS_PRIMARY} DESC"
                            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                                while (cursor.moveToNext()) {
                                    val accountName = cursor.getString(0) ?: ""
                                    val ownerAccount = cursor.getString(1) ?: ""
                                    if (accountName.contains("@")) return@withContext accountName
                                    if (ownerAccount.contains("@")) return@withContext ownerAccount
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("EventViewModel", "Error fetching calendar email: ${e.message}")
                    }
                    null
                }
                if (!email.isNullOrBlank()) {
                    saveUserIdentity(email, userNameState.value)
                }
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
