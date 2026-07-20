package com.example.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.Event
import com.example.data.EventRepository
import com.example.receiver.AlarmScheduler
import com.example.receiver.EventWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        Log.d(TAG, "SyncWorker triggered in background")

        // 1. Check if auto sync is enabled in settings
        val sharedPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val isSyncEnabled = sharedPrefs.getBoolean("gcal_sync_enabled", false)
        if (!isSyncEnabled) {
            Log.d(TAG, "Auto-sync is disabled in settings. Skipping background sync.")
            return@withContext Result.success()
        }

        // 2. Check permissions
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Read calendar permission is not granted. Cannot run background sync.")
            return@withContext Result.failure()
        }

        // 3. Perform Google Calendar synchronization
        try {
            val db = AppDatabase.getDatabase(context)
            val repository = EventRepository(db.eventDao())
            val alarmScheduler = AlarmScheduler(context)

            // Get user credentials for personal calendar filter
            val currentUserEmail = sharedPrefs.getString("user_email", "")?.trim() ?: ""
            val currentUserName = sharedPrefs.getString("user_name", "")?.trim() ?: ""
            val emailPrefix = if (currentUserEmail.contains("@")) currentUserEmail.substringBefore("@") else ""
            val firstName = if (currentUserName.contains(" ")) currentUserName.substringBefore(" ") else currentUserName

            val personalCalendarIds = mutableSetOf<Long>()
            val personalCalendarNames = mutableSetOf<String>()

            // Query Calendars
            val contentResolver = context.contentResolver
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
                    }
                }
            }

            // Query Events
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
            val startMillis = now
            val endMillis = now + 3L * 7 * 24 * 60 * 60 * 1000 // 3 weeks

            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} = 0"
            val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())

            val cursor = contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )

            var addedCount = 0
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

                    if (dtStart < now) continue

                    val calendarDisplayName = calName.trim()
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

                    val eventId = if (idCol >= 0) c.getLong(idCol) else -1L
                    val isUserAttendee = if (eventId != -1L && currentUserEmail.isNotBlank()) {
                        var found = false
                        try {
                            val attendeesUri = CalendarContract.Attendees.CONTENT_URI
                            val attendeesProjection = arrayOf(
                                CalendarContract.Attendees.ATTENDEE_EMAIL,
                                CalendarContract.Attendees.ATTENDEE_NAME
                            )
                            val attendeeSelection = "${CalendarContract.Attendees.EVENT_ID} = ?"
                            val attendeeSelectionArgs = arrayOf(eventId.toString())
                            context.contentResolver.query(
                                attendeesUri,
                                attendeesProjection,
                                attendeeSelection,
                                attendeeSelectionArgs,
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
                            Log.e(TAG, "Failed to check attendees for event $eventId: ${e.message}")
                        }
                        found
                    } else {
                        false
                    }

                    val isOtherPersonVacation = (title.contains("OOO", ignoreCase = true) ||
                                                 title.contains("vacation", ignoreCase = true) ||
                                                 title.contains("out of office", ignoreCase = true) ||
                                                 title.contains("leave", ignoreCase = true)) &&
                                                !isPersonalCalendar && !isUserAttendee && !organizer.equals(currentUserEmail, ignoreCase = true)

                    val shouldImport = !isBirthdaysCalendar && !isTasksCalendar && !isOtherPersonVacation && (
                        isPersonalCalendar || isUserAttendee || organizer.equals(currentUserEmail, ignoreCase = true) || isRelatedToUserText
                    )

                    if (!shouldImport) continue

                    val alreadyExists = currentEvents.any {
                        it.title == title && Math.abs(it.dateTimeMillis - dtStart) < 5000
                    }

                    if (!alreadyExists) {
                        val calendar = Calendar.getInstance().apply { timeInMillis = dtStart }
                        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                        val isWorkdayDay = dayOfWeek != Calendar.FRIDAY && dayOfWeek != Calendar.SATURDAY

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
                        addedCount++
                    }
                }
            }

            if (addedCount > 0) {
                EventWidgetProvider.triggerUpdate(context)
                Log.d(TAG, "Background sync successfully added $addedCount new events.")
            } else {
                Log.d(TAG, "Background sync completed. No new events found.")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in background sync: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val UNIQUE_WORK_NAME = "calendar_sync_work"

        fun scheduleSync(context: Context) {
            val sharedPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val isSyncEnabled = sharedPrefs.getBoolean("gcal_sync_enabled", false)
            val isWorkMode = sharedPrefs.getBoolean("is_work_environment", false)

            val workManager = WorkManager.getInstance(context)

            if (!isSyncEnabled) {
                // Cancel background sync if disabled
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                Log.d(TAG, "Cancelled periodic background sync since auto-sync is disabled.")
                return
            }

            // Determine interval based on mode (Work mode: 1 hour, Personal mode: 12 hours)
            val intervalMinutes = if (isWorkMode) {
                60L // 1 hour (as requested/confirmed by user)
            } else {
                12L * 60L // 12 hours
            }

            Log.d(TAG, "Scheduling background sync every $intervalMinutes minutes. WorkMode=$isWorkMode")

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMinutes, TimeUnit.MINUTES,
                15, TimeUnit.MINUTES // 15 mins flex period is the minimum required by WorkManager
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE, // Safely replace the old periodic work to update the frequency
                syncRequest
            )
        }
    }
}
