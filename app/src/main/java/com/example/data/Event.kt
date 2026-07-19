package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val description: String,
    val dateTimeMillis: Long,
    val isWorkday: Boolean, // True if during a workday or breaks a workday
    val isEmailSent: Boolean = false // Track whether user marked email as sent
) {
    val isMeeting: Boolean
        get() = description.contains("meet.google.com", ignoreCase = true) || title.contains("Google Meet", ignoreCase = true)

    // Utility to get next scheduled reminder
    fun getNextReminderText(currentTime: Long): String {
        val reminders = getReminderTimes()
        for ((name, time) in reminders) {
            if (time > currentTime) {
                return "$name on ${formatDate(time)}"
            }
        }
        return "None (Event has passed)"
    }

    fun getReminderTimes(): List<Pair<String, Long>> {
        val list = mutableListOf<Pair<String, Long>>()
        // 2 weeks before
        list.add("2 weeks before" to (dateTimeMillis - 14L * 24 * 60 * 60 * 1000))
        // 1 week before
        list.add("1 week before" to (dateTimeMillis - 7L * 24 * 60 * 60 * 1000))
        // 1 day before
        list.add("1 day before" to (dateTimeMillis - 1L * 24 * 60 * 60 * 1000))
        // 1 minute before
        list.add("1 minute before" to (dateTimeMillis - 1L * 60 * 1000))
        // Day of event
        list.add("Day of the event" to dateTimeMillis)

        // Sort by time ascending
        return list.sortedBy { it.second }
    }

    private fun formatDate(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(millis))
    }
}
