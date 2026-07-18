package com.example.receiver

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.R
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (appWidgetId in appWidgetIds) {
                    updateAppWidgetSynchronously(context, appWidgetManager, appWidgetId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult?.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, EventWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    for (appWidgetId in appWidgetIds) {
                        updateAppWidgetSynchronously(context, appWidgetManager, appWidgetId)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pendingResult?.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_WIDGET = "com.example.receiver.ACTION_UPDATE_WIDGET"

        fun triggerUpdate(context: Context) {
            val intent = Intent(context, EventWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            context.sendBroadcast(intent)
        }

        private suspend fun updateAppWidgetSynchronously(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.event_widget)

            try {
                val db = AppDatabase.getDatabase(context)
                val now = System.currentTimeMillis()
                val allEvents = db.eventDao().getAllEventsList()
                val upcomingEvents = allEvents
                    .filter { it.dateTimeMillis > now }
                    .sortedBy { it.dateTimeMillis }

                val count = upcomingEvents.size
                if (count == 0) {
                    views.setViewVisibility(R.id.event_item_1, View.GONE)
                    views.setViewVisibility(R.id.event_item_2, View.GONE)
                    views.setViewVisibility(R.id.widget_empty_text, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.widget_empty_text, View.GONE)

                    val event1 = upcomingEvents[0]
                    views.setViewVisibility(R.id.event_item_1, View.VISIBLE)
                    views.setTextViewText(R.id.event_title_1, event1.title)
                    views.setTextViewText(R.id.event_time_1, formatTime(event1.dateTimeMillis))
                    views.setViewVisibility(R.id.event_badge_1, if (event1.isWorkday) View.VISIBLE else View.GONE)

                    if (count > 1) {
                        val event2 = upcomingEvents[1]
                        views.setViewVisibility(R.id.event_item_2, View.VISIBLE)
                        views.setTextViewText(R.id.event_title_2, event2.title)
                        views.setTextViewText(R.id.event_time_2, formatTime(event2.dateTimeMillis))
                        views.setViewVisibility(R.id.event_badge_2, if (event2.isWorkday) View.VISIBLE else View.GONE)
                    } else {
                        views.setViewVisibility(R.id.event_item_2, View.GONE)
                    }
                }

                val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                views.setTextViewText(R.id.widget_sync_status, "Synced ${timeSdf.format(Date())}")

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                e.printStackTrace()
                views.setTextViewText(R.id.event_title_1, "Error loading alarms")
                views.setViewVisibility(R.id.event_item_1, View.VISIBLE)
                views.setViewVisibility(R.id.event_item_2, View.GONE)
                views.setViewVisibility(R.id.widget_empty_text, View.GONE)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        private fun formatTime(millis: Long): String {
            val sdf = SimpleDateFormat("EEE, MMM dd • hh:mm a", Locale.getDefault())
            return sdf.format(Date(millis))
        }
    }
}
