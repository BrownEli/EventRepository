package com.example

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.Event
import com.example.ui.EventViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color


import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Proactively refresh home screen widget on app startup
        com.example.receiver.EventWidgetProvider.triggerUpdate(this)
        setContent {
            MyApplicationTheme {
                MainScreen(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: EventViewModel = viewModel(
        factory = EventViewModel.Factory(LocalContext.current.applicationContext as Application)
    )
) {
    val context = LocalContext.current
    val events by viewModel.allEvents.collectAsStateWithLifecycle()

    // Handle runtime permissions (Notifications & Calendar)
    val permissionsToRequest = remember {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        list.add(android.Manifest.permission.READ_CALENDAR)
        list.add(android.Manifest.permission.WRITE_CALENDAR)
        list.toTypedArray()
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val calendarGranted = results[android.Manifest.permission.READ_CALENDAR] ?: false
        if (calendarGranted) {
            viewModel.syncGoogleCalendar(context) { count ->
                if (count > 0) {
                    Toast.makeText(context, "Successfully synced $count new calendar events!", Toast.LENGTH_LONG).show()
                } else if (count == 0) {
                    Toast.makeText(context, "Calendar synced. No new events found.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results[android.Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true
        }
        if (!notifGranted) {
            Toast.makeText(context, "Warning: Alarms require notification permissions to display.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        permissionsLauncher.launch(permissionsToRequest)
        
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_CALENDAR
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.syncGoogleCalendar(context) { count ->
                if (count > 0) {
                    Toast.makeText(context, "Automatically synced $count new events from Google Calendar!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val polishColors = LocalPolishColors.current
    var selectedTab by remember { mutableStateOf(1) }
    var workTabSelection by remember { mutableStateOf(0) } // 0 = Meets, 1 = Others
    val now = System.currentTimeMillis()
    
    val isWorkEnvironment by viewModel.isWorkEnvironment.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val isQuietHoursEnabled by viewModel.isQuietHoursEnabled.collectAsStateWithLifecycle()
    val quietHoursStart by viewModel.quietHoursStart.collectAsStateWithLifecycle()
    val quietHoursEnd by viewModel.quietHoursEnd.collectAsStateWithLifecycle()
    val isQuietHoursChargingRequired by viewModel.isQuietHoursChargingRequired.collectAsStateWithLifecycle()

    val filteredEvents = remember(events, isWorkEnvironment) {
        val nowTime = System.currentTimeMillis()
        val limit = if (isWorkEnvironment) 7L * 24 * 60 * 60 * 1000 else 3L * 7 * 24 * 60 * 60 * 1000
        events.filter { it.dateTimeMillis >= nowTime && it.dateTimeMillis <= nowTime + limit }
    }

    val meetsEvents = remember(filteredEvents) {
        filteredEvents.filter { event ->
            event.isMeeting || 
            event.description.contains("meet.google.com", ignoreCase = true) || 
            event.description.contains("google.com", ignoreCase = true) || 
            event.title.contains("Google Meet", ignoreCase = true)
        }
    }

    val otherEvents = remember(filteredEvents) {
        filteredEvents.filter { event ->
            !(event.isMeeting || 
              event.description.contains("meet.google.com", ignoreCase = true) || 
              event.description.contains("google.com", ignoreCase = true) || 
              event.title.contains("Google Meet", ignoreCase = true))
        }
    }

    val eventsToShow = remember(filteredEvents, isWorkEnvironment, workTabSelection, meetsEvents, otherEvents) {
        if (isWorkEnvironment) {
            if (workTabSelection == 0) meetsEvents else otherEvents
        } else {
            filteredEvents
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = polishColors.surface,
                contentColor = polishColors.primary,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.AddCircleOutline,
                            contentDescription = "Add Event"
                        )
                    },
                    label = {
                        Text(
                            text = "Add Event",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = polishColors.primary,
                        selectedTextColor = polishColors.primary,
                        unselectedIconColor = polishColors.onSurfaceVariant.copy(alpha = 0.7f),
                        unselectedTextColor = polishColors.onSurfaceVariant.copy(alpha = 0.7f),
                        indicatorColor = polishColors.primary.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("nav_enter_event")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Event,
                            contentDescription = "Upcoming"
                        )
                    },
                    label = {
                        Text(
                            text = "Upcoming",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = polishColors.primary,
                        selectedTextColor = polishColors.primary,
                        unselectedIconColor = polishColors.onSurfaceVariant.copy(alpha = 0.7f),
                        unselectedTextColor = polishColors.onSurfaceVariant.copy(alpha = 0.7f),
                        indicatorColor = polishColors.primary.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("nav_upcoming_events")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    },
                    label = {
                        Text(
                            text = "Settings",
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = polishColors.primary,
                        selectedTextColor = polishColors.primary,
                        unselectedIconColor = polishColors.onSurfaceVariant.copy(alpha = 0.7f),
                        unselectedTextColor = polishColors.onSurfaceVariant.copy(alpha = 0.7f),
                        indicatorColor = polishColors.primary.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.testTag("nav_settings")
                )
            }
        },
        containerColor = polishColors.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .statusBarsPadding()
        ) {
            when (selectedTab) {
                0 -> {
                    // TAB 0: Enter / Create Event & Instant Alarm Test Playground
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            // Create Event Card Form
                            CreateEventCard(
                                isWorkEnvironment = isWorkEnvironment,
                                onAddEvent = { title, desc, time, isWorkday, isImportant ->
                                    viewModel.addEvent(title, desc, time, isWorkday, isImportant)
                                    
                                    // Trigger Google Calendar Insert Intent directly!
                                    val calendarIntent = Intent(Intent.ACTION_INSERT).apply {
                                        data = CalendarContract.Events.CONTENT_URI
                                        putExtra(CalendarContract.Events.TITLE, title)
                                        putExtra(CalendarContract.Events.DESCRIPTION, desc + "\n\n[Reminders configured in Alarm Me app]")
                                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, time)
                                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, time + 60 * 60 * 1000) // 1 hour
                                        putExtra(CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.ACCESS_PRIVATE)
                                    }
                                    try {
                                        context.startActivity(calendarIntent)
                                        Toast.makeText(context, "Saved locally & opening Google Calendar!", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Saved locally! Google Calendar application not found.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }

                        // Section: Instant Test Alarm Playground
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = polishColors.alarmBg
                                ),
                                border = BorderStroke(1.dp, polishColors.alarmBorder),
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Center alarm clock icon with ring details
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .background(polishColors.background, RoundedCornerShape(32.dp))
                                            .padding(4.dp)
                                            .background(polishColors.alarmBorder.copy(alpha = 0.3f), RoundedCornerShape(28.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Alarm,
                                            contentDescription = "Active Alarm",
                                            tint = polishColors.alarmText,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Red Badge
                                    Box(
                                        modifier = Modifier
                                            .background(polishColors.alarmText, RoundedCornerShape(12.dp))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "PLAYGROUND PREVIEW",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp
                                            )
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = "Instant Test Alarm Playground",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = polishColors.alarmText,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = "Set a 5-second delay test alarm and lock your screen or go home to experience the alarm ringtone and sticky email checklist.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = polishColors.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 16.sp
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    var testTitle by remember { mutableStateOf("Quick Workday Sync Task") }
                                    var testIsWorkday by remember { mutableStateOf(true) }

                                    OutlinedTextField(
                                        value = testTitle,
                                        onValueChange = { testTitle = it },
                                        label = { Text("Test Event Title", color = polishColors.onSurfaceVariant) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = polishColors.alarmText,
                                            unfocusedBorderColor = polishColors.alarmBorder,
                                            focusedLabelColor = polishColors.alarmText,
                                            cursorColor = polishColors.alarmText
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Workday Event? (Sticky Email)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = polishColors.text
                                        )
                                        Switch(
                                            checked = testIsWorkday,
                                            onCheckedChange = { testIsWorkday = it },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.White,
                                                checkedTrackColor = polishColors.alarmText,
                                                uncheckedThumbColor = polishColors.alarmBorder,
                                                uncheckedTrackColor = polishColors.background
                                            )
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                viewModel.triggerInstantTestAlarm(testTitle, testIsWorkday)
                                                Toast.makeText(context, "Test alarm set for 5s from now!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier
                                                .weight(1.2f)
                                                .testTag("trigger_test_alarm_button"),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = polishColors.alarmText,
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(24.dp),
                                            contentPadding = PaddingValues(vertical = 12.dp)
                                        ) {
                                            Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Start Alarm")
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                viewModel.stopActiveAlarmService()
                                                Toast.makeText(context, "Alarm service stopped.", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = polishColors.alarmText
                                            ),
                                            border = BorderStroke(1.dp, polishColors.alarmBorder),
                                            shape = RoundedCornerShape(24.dp),
                                            contentPadding = PaddingValues(vertical = 12.dp)
                                        ) {
                                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Stop")
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                }
                1 -> {
                    // TAB 1: Upcoming Events (next 3 weeks)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Cosmic Banner
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.img_alarm_header),
                                    contentDescription = "Cosmic Alarm Clock Banner",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                                colors = listOf(
                                                    androidx.compose.ui.graphics.Color.Transparent,
                                                    androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f)
                                                )
                                            )
                                        )
                                )
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = "Alarm Me",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            color = androidx.compose.ui.graphics.Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 22.sp
                                        )
                                    )
                                    Text(
                                        text = "Ring-based alarms for appointments and deadlines",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                                            fontSize = 12.sp
                                        )
                                    )
                                }
                            }
                        }

                        // Section header with Active Badge
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 0.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Scheduled Alarms List",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = polishColors.primary
                                        )
                                        Text(
                                            text = if (isWorkEnvironment) {
                                                "Work Environment: Alarms scheduled for current week."
                                            } else {
                                                "Personal Environment: Alarms scheduled for next 3 weeks."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = polishColors.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        var localIsSyncing by remember { mutableStateOf(false) }
                                        IconButton(
                                            onClick = {
                                                if (!localIsSyncing) {
                                                    localIsSyncing = true
                                                    viewModel.syncGoogleCalendar(context) { count ->
                                                        localIsSyncing = false
                                                        if (count > 0) {
                                                            Toast.makeText(context, "Successfully synced $count new calendar events!", Toast.LENGTH_LONG).show()
                                                        } else if (count == 0) {
                                                            Toast.makeText(context, "Calendar synced. No new events found.", Toast.LENGTH_SHORT).show()
                                                        } else if (count == -1) {
                                                            Toast.makeText(context, "Please grant Calendar permission to sync.", Toast.LENGTH_LONG).show()
                                                        } else {
                                                            Toast.makeText(context, "Calendar sync failed.", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(36.dp).testTag("sync_now_button")
                                        ) {
                                            if (localIsSyncing) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                    color = polishColors.primary
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Sync,
                                                    contentDescription = "Sync Now",
                                                    tint = polishColors.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Badge(
                                            containerColor = polishColors.primary.copy(alpha = 0.15f),
                                            contentColor = polishColors.primary
                                        ) {
                                            Text(
                                                text = "${eventsToShow.size} Events",
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Sub-tabs for Work Mode
                        if (isWorkEnvironment) {
                            item {
                                TabRow(
                                    selectedTabIndex = workTabSelection,
                                    containerColor = Color.Transparent,
                                    contentColor = polishColors.primary,
                                    indicator = { tabPositions ->
                                        TabRowDefaults.SecondaryIndicator(
                                            Modifier.tabIndicatorOffset(tabPositions[workTabSelection]),
                                            color = polishColors.primary
                                        )
                                    },
                                    modifier = Modifier.padding(vertical = 4.dp).testTag("work_mode_sub_tabs")
                                ) {
                                    Tab(
                                        selected = workTabSelection == 0,
                                        onClick = { workTabSelection = 0 },
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Videocam,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = "Meets (${meetsEvents.size})",
                                                    fontWeight = if (workTabSelection == 0) FontWeight.Bold else FontWeight.Normal,
                                                    style = MaterialTheme.typography.labelLarge
                                                )
                                            }
                                        },
                                        selectedContentColor = polishColors.primary,
                                        unselectedContentColor = polishColors.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.testTag("tab_meets")
                                    )
                                    Tab(
                                        selected = workTabSelection == 1,
                                        onClick = { workTabSelection = 1 },
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Event,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = "Others (${otherEvents.size})",
                                                    fontWeight = if (workTabSelection == 1) FontWeight.Bold else FontWeight.Normal,
                                                    style = MaterialTheme.typography.labelLarge
                                                )
                                            }
                                        },
                                        selectedContentColor = polishColors.primary,
                                        unselectedContentColor = polishColors.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.testTag("tab_others")
                                    )
                                }
                            }
                        }

                        // Event Cards List
                        if (eventsToShow.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = polishColors.surface.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                    border = BorderStroke(1.dp, polishColors.border.copy(alpha = 0.3f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.EventNote,
                                            contentDescription = "No events icon",
                                            tint = polishColors.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = if (isWorkEnvironment) {
                                                if (workTabSelection == 0) "No Meeting Events" else "No Other Events"
                                            } else {
                                                "No Scheduled Alarms"
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = polishColors.text
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = if (isWorkEnvironment) {
                                                if (workTabSelection == 0) "No events with Google Meets or Google Links attached for this week." else "No other events found for this week."
                                            } else {
                                                "Add an event in 'Enter Event' tab, or sync from your Google Calendar automatically."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = polishColors.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        } else {
                            items(eventsToShow, key = { it.id }) { event ->
                                EventItemCard(
                                    event = event,
                                    onDelete = { viewModel.deleteEvent(event) },
                                    onToggleEmail = { viewModel.toggleEmailSent(event) },
                                    onToggleMode = { viewModel.toggleEventMode(event, isWorkEnvironment) },
                                    onSyncCalendar = {
                                        val calendarIntent = Intent(Intent.ACTION_INSERT).apply {
                                            data = CalendarContract.Events.CONTENT_URI
                                            putExtra(CalendarContract.Events.TITLE, event.title)
                                            putExtra(CalendarContract.Events.DESCRIPTION, event.description)
                                            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.dateTimeMillis)
                                            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.dateTimeMillis + 60 * 60 * 1000)
                                        }
                                        try {
                                            context.startActivity(calendarIntent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Google Calendar app not found.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    isWorkEnvironment = isWorkEnvironment
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                }
                2 -> {
                    // TAB 2: Settings Screen
                    val formatHour24 = { hour: Int ->
                        when {
                            hour == 0 -> "12 AM"
                            hour == 12 -> "12 PM"
                            hour > 12 -> "${hour - 12} PM"
                            else -> "$hour AM"
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Settings & Preferences",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = polishColors.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        // 1. Sync Identity Profile as is
                        item {
                            var isEditingIdentity by remember { mutableStateOf(false) }
                            val currentUserEmail by viewModel.userEmail.collectAsStateWithLifecycle()
                            val currentUserName by viewModel.userName.collectAsStateWithLifecycle()
                            
                            var emailInput by remember(currentUserEmail) { mutableStateOf(currentUserEmail) }
                            var nameInput by remember(currentUserName) { mutableStateOf(currentUserName) }
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                ),
                                border = BorderStroke(1.dp, polishColors.border.copy(alpha = 0.2f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { isEditingIdentity = !isEditingIdentity },
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .background(
                                                        polishColors.primary.copy(alpha = 0.15f),
                                                        RoundedCornerShape(18.dp)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Person,
                                                    contentDescription = null,
                                                    tint = polishColors.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "Sync Identity Profile",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = polishColors.primary
                                                )
                                                Text(
                                                    text = if (currentUserEmail.isNotBlank()) "$currentUserName ($currentUserEmail)" else "Not Configured",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = polishColors.onSurfaceVariant.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = if (isEditingIdentity) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = "Toggle Identity Settings",
                                            tint = polishColors.onSurfaceVariant
                                        )
                                    }
                                    
                                    AnimatedVisibility(
                                        visible = isEditingIdentity,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            HorizontalDivider(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = polishColors.border.copy(alpha = 0.2f)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            
                                            // Email Field
                                            OutlinedTextField(
                                                value = emailInput,
                                                onValueChange = { emailInput = it },
                                                label = { Text("User Email") },
                                                placeholder = { Text("e.g. elibrown62@gmail.com") },
                                                modifier = Modifier.fillMaxWidth().testTag("identity_email_input"),
                                                singleLine = true,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            
                                            // Name Field
                                            OutlinedTextField(
                                                value = nameInput,
                                                onValueChange = { nameInput = it },
                                                label = { Text("Display Name") },
                                                placeholder = { Text("e.g. Jane Doe") },
                                                modifier = Modifier.fillMaxWidth().testTag("identity_name_input"),
                                                singleLine = true,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            
                                            Spacer(modifier = Modifier.height(4.dp))
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                TextButton(
                                                    onClick = {
                                                        emailInput = currentUserEmail
                                                        nameInput = currentUserName
                                                        isEditingIdentity = false
                                                    }
                                                ) {
                                                    Text("Cancel")
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Button(
                                                    onClick = {
                                                        viewModel.saveUserIdentity(emailInput, nameInput)
                                                        isEditingIdentity = false
                                                        Toast.makeText(context, "Identity updated!", Toast.LENGTH_SHORT).show()
                                                    },
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = polishColors.primary,
                                                        contentColor = Color.White
                                                    )
                                                ) {
                                                    Text("Save Profile")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Sync with Google Calendar as a toggle button
                        item {
                            var isSyncing by remember { mutableStateOf(false) }
                            val isGcalSyncEnabled by viewModel.isGcalSyncEnabled.collectAsStateWithLifecycle()

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isGcalSyncEnabled) {
                                        polishColors.primary.copy(alpha = 0.08f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                    }
                                ),
                                border = BorderStroke(1.dp, polishColors.border.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(
                                                    if (isGcalSyncEnabled) polishColors.primary.copy(alpha = 0.15f)
                                                    else polishColors.onSurfaceVariant.copy(alpha = 0.1f),
                                                    RoundedCornerShape(18.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Sync,
                                                contentDescription = null,
                                                tint = if (isGcalSyncEnabled) polishColors.primary else polishColors.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Sync with Google Calendar",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isGcalSyncEnabled) polishColors.primary else polishColors.onSurfaceVariant
                                            )
                                            Text(
                                                text = if (isSyncing) "Syncing..." else if (isGcalSyncEnabled) "Auto-sync enabled & connected" else "Tap to enable Google Calendar sync",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = polishColors.onSurfaceVariant.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = isGcalSyncEnabled,
                                        onCheckedChange = { checked ->
                                            isSyncing = true
                                            viewModel.toggleGcalSync(context) { count ->
                                                isSyncing = false
                                                if (checked) {
                                                    if (count > 0) {
                                                        Toast.makeText(context, "Successfully synced $count new calendar events!", Toast.LENGTH_LONG).show()
                                                    } else if (count == 0) {
                                                        Toast.makeText(context, "Calendar synced. No new events found.", Toast.LENGTH_SHORT).show()
                                                    } else if (count == -1) {
                                                        Toast.makeText(context, "Please grant Calendar permission to sync.", Toast.LENGTH_LONG).show()
                                                    } else {
                                                        Toast.makeText(context, "Calendar sync failed.", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.testTag("gcal_sync_switch"),
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = polishColors.primary,
                                            checkedTrackColor = polishColors.primary.copy(alpha = 0.4f)
                                        )
                                    )
                                }
                            }
                        }

                        // 3. Personal Mode Active as is
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isWorkEnvironment) {
                                        polishColors.primary.copy(alpha = 0.08f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                    }
                                ),
                                border = BorderStroke(1.dp, polishColors.border.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(
                                                    if (isWorkEnvironment) MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                                    else polishColors.primary.copy(alpha = 0.15f),
                                                    RoundedCornerShape(18.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isWorkEnvironment) Icons.Default.Work else Icons.Default.Home,
                                                contentDescription = null,
                                                tint = if (isWorkEnvironment) MaterialTheme.colorScheme.secondary else polishColors.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = if (isWorkEnvironment) "💼 Work Mode Active" else "🏡 Personal Mode Active",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isWorkEnvironment) MaterialTheme.colorScheme.secondary else polishColors.primary
                                            )
                                            Text(
                                                text = if (isWorkEnvironment) {
                                                    "Shows current week. Alarms: 8am list, 5-min, 1-min sticky."
                                                } else {
                                                    "Shows next 3 weeks. Normal workday & personal alarm triggers."
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = polishColors.onSurfaceVariant.copy(alpha = 0.8f)
                                              )
                                        }
                                    }
                                    Switch(
                                        checked = isWorkEnvironment,
                                        onCheckedChange = { viewModel.toggleWorkEnvironment() },
                                        modifier = Modifier.testTag("environment_mode_switch"),
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.secondary,
                                            checkedTrackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
                                            uncheckedThumbColor = polishColors.primary,
                                            uncheckedTrackColor = polishColors.primary.copy(alpha = 0.4f)
                                        )
                                    )
                                }
                            }
                        }

                        // Quiet Sleep Hours Settings Card
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isQuietHoursEnabled) {
                                        polishColors.primary.copy(alpha = 0.08f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                    }
                                ),
                                border = BorderStroke(1.dp, polishColors.border.copy(alpha = 0.4f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    // Header with switch
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .background(
                                                        if (isQuietHoursEnabled) polishColors.primary.copy(alpha = 0.15f)
                                                        else polishColors.onSurfaceVariant.copy(alpha = 0.1f),
                                                        RoundedCornerShape(18.dp)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.NightsStay,
                                                    contentDescription = null,
                                                    tint = if (isQuietHoursEnabled) polishColors.primary else polishColors.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "Quiet Sleep Hours",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isQuietHoursEnabled) polishColors.primary else polishColors.onSurfaceVariant
                                                )
                                                Text(
                                                    text = if (isQuietHoursEnabled) "Silences alarms during designated hours" else "Quiet hours are disabled",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = polishColors.onSurfaceVariant.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = isQuietHoursEnabled,
                                            onCheckedChange = { viewModel.toggleQuietHoursEnabled() },
                                            modifier = Modifier.testTag("quiet_hours_enabled_switch"),
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = polishColors.primary,
                                                checkedTrackColor = polishColors.primary.copy(alpha = 0.4f)
                                            )
                                        )
                                    }

                                    // Detailed config with smooth slide/fade animation
                                    AnimatedVisibility(
                                        visible = isQuietHoursEnabled,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            HorizontalDivider(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = polishColors.border.copy(alpha = 0.2f)
                                            )

                                            // Start Hour Selector
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "Start Quiet Hours",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = polishColors.text
                                                    )
                                                    Text(
                                                        text = "When quiet sleep mode begins",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = polishColors.onSurfaceVariant.copy(alpha = 0.7f)
                                                    )
                                                }
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    IconButton(
                                                        onClick = {
                                                            val newHour = (quietHoursStart + 23) % 24
                                                            viewModel.setQuietHoursStart(newHour)
                                                        },
                                                        modifier = Modifier.size(36.dp).testTag("quiet_start_minus")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Remove,
                                                            contentDescription = "Decrease Start Hour",
                                                            tint = polishColors.primary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                    Text(
                                                        text = formatHour24(quietHoursStart),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = polishColors.text,
                                                        modifier = Modifier.widthIn(min = 60.dp),
                                                        textAlign = TextAlign.Center
                                                    )
                                                    IconButton(
                                                        onClick = {
                                                            val newHour = (quietHoursStart + 1) % 24
                                                            viewModel.setQuietHoursStart(newHour)
                                                        },
                                                        modifier = Modifier.size(36.dp).testTag("quiet_start_plus")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Add,
                                                            contentDescription = "Increase Start Hour",
                                                            tint = polishColors.primary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // End Hour Selector
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "End Quiet Hours",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = polishColors.text
                                                    )
                                                    Text(
                                                        text = "When quiet sleep mode stops",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = polishColors.onSurfaceVariant.copy(alpha = 0.7f)
                                                    )
                                                }
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    IconButton(
                                                        onClick = {
                                                            val newHour = (quietHoursEnd + 23) % 24
                                                            viewModel.setQuietHoursEnd(newHour)
                                                        },
                                                        modifier = Modifier.size(36.dp).testTag("quiet_end_minus")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Remove,
                                                            contentDescription = "Decrease End Hour",
                                                            tint = polishColors.primary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                    Text(
                                                        text = formatHour24(quietHoursEnd),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = polishColors.text,
                                                        modifier = Modifier.widthIn(min = 60.dp),
                                                        textAlign = TextAlign.Center
                                                    )
                                                    IconButton(
                                                        onClick = {
                                                            val newHour = (quietHoursEnd + 1) % 24
                                                            viewModel.setQuietHoursEnd(newHour)
                                                        },
                                                        modifier = Modifier.size(36.dp).testTag("quiet_end_plus")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Add,
                                                            contentDescription = "Increase End Hour",
                                                            tint = polishColors.primary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            HorizontalDivider(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = polishColors.border.copy(alpha = 0.2f)
                                            )

                                            // Require charging option
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Bolt,
                                                        contentDescription = null,
                                                        tint = if (isQuietHoursChargingRequired) polishColors.primary else polishColors.onSurfaceVariant.copy(alpha = 0.6f),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column {
                                                        Text(
                                                            text = "Only when charging",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Medium,
                                                            color = polishColors.text
                                                        )
                                                        Text(
                                                            text = "Requires device to be plugged into a charger",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = polishColors.onSurfaceVariant.copy(alpha = 0.7f)
                                                        )
                                                    }
                                                }
                                                Switch(
                                                    checked = isQuietHoursChargingRequired,
                                                    onCheckedChange = { viewModel.toggleQuietHoursChargingRequired() },
                                                    modifier = Modifier.testTag("quiet_charging_required_switch"),
                                                    colors = SwitchDefaults.colors(
                                                        checkedThumbColor = polishColors.primary,
                                                        checkedTrackColor = polishColors.primary.copy(alpha = 0.4f)
                                                    )
                                                )
                                            }

                                            // Final Summary Box/Banner
                                            val summaryText = if (isQuietHoursChargingRequired) {
                                                "🌙 Alarms scheduled between ${formatHour24(quietHoursStart)} and ${formatHour24(quietHoursEnd)} will be completely silenced ONLY when the phone is plugged into a charger."
                                            } else {
                                                "🌙 Alarms scheduled between ${formatHour24(quietHoursStart)} and ${formatHour24(quietHoursEnd)} will be completely silenced."
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        polishColors.primary.copy(alpha = 0.05f),
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .border(
                                                        1.dp,
                                                        polishColors.primary.copy(alpha = 0.15f),
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(10.dp)
                                            ) {
                                                Text(
                                                    text = summaryText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = polishColors.primary,
                                                    lineHeight = 15.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. App Version and Build Info Card
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                                ),
                                border = BorderStroke(1.dp, polishColors.border.copy(alpha = 0.15f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                polishColors.primary.copy(alpha = 0.12f),
                                                RoundedCornerShape(18.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = polishColors.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "App Information",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = polishColors.text
                                        )
                                        Text(
                                            text = "Version Name: ${com.example.BuildConfig.VERSION_NAME}\nBuild Code: ${com.example.BuildConfig.VERSION_CODE}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = polishColors.onSurfaceVariant.copy(alpha = 0.8f),
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateEventCard(
    isWorkEnvironment: Boolean,
    onAddEvent: (String, String, Long, Boolean, Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isImportant by remember { mutableStateOf(false) }

    val calendar = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableStateOf(calendar.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(calendar.get(Calendar.MONTH)) }
    var selectedDay by remember { mutableStateOf(calendar.get(Calendar.DAY_OF_MONTH)) }
    var selectedHour by remember { mutableStateOf(10) } // Default 10 AM
    var selectedMinute by remember { mutableStateOf(0) }

    var isWorkday by remember {
        val todayDow = calendar.get(Calendar.DAY_OF_WEEK)
        mutableStateOf(todayDow != Calendar.FRIDAY && todayDow != Calendar.SATURDAY)
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val context = LocalContext.current

    if (showDatePicker) {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                selectedYear = year
                selectedMonth = month
                selectedDay = dayOfMonth
                showDatePicker = false
                
                // Auto-toggle isWorkday based on Sunday-Thursday workday rules
                val tempCal = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                }
                val dow = tempCal.get(Calendar.DAY_OF_WEEK)
                isWorkday = dow != Calendar.FRIDAY && dow != Calendar.SATURDAY
            },
            selectedYear,
            selectedMonth,
            selectedDay
        ).apply {
            setOnCancelListener { showDatePicker = false }
            show()
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                selectedHour = hourOfDay
                selectedMinute = minute
                showTimePicker = false
            },
            selectedHour,
            selectedMinute,
            false
        ).apply {
            setOnCancelListener { showTimePicker = false }
            show()
        }
    }

    val selectedDateTimeMillis = remember(selectedYear, selectedMonth, selectedDay, selectedHour, selectedMinute) {
        val cal = Calendar.getInstance()
        cal.set(selectedYear, selectedMonth, selectedDay, selectedHour, selectedMinute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    val formattedDate = remember(selectedDateTimeMillis) {
        val sdf = SimpleDateFormat("EEE, MMM dd, yyyy", Locale.getDefault())
        sdf.format(Date(selectedDateTimeMillis))
    }

    val formattedTime = remember(selectedHour, selectedMinute) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, selectedHour)
            set(Calendar.MINUTE, selectedMinute)
        }
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        sdf.format(cal.time)
    }

    val polishColors = LocalPolishColors.current

    Card(
        colors = CardDefaults.cardColors(
            containerColor = polishColors.surface
        ),
        border = BorderStroke(1.dp, polishColors.border),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AddBox,
                    contentDescription = "Add Alarm",
                    tint = polishColors.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "New Event & Reminders",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = polishColors.text
                )
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Event / Appointment Title") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("event_title_input"),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Title, contentDescription = null, tint = polishColors.primary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = polishColors.primary,
                    unfocusedBorderColor = polishColors.border,
                    focusedLabelColor = polishColors.primary,
                    cursorColor = polishColors.primary
                )
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description / Tasks") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null, tint = polishColors.primary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = polishColors.primary,
                    unfocusedBorderColor = polishColors.border,
                    focusedLabelColor = polishColors.primary,
                    cursorColor = polishColors.primary
                )
            )

            // Date and Time selection pickers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = polishColors.secondaryContainer,
                        contentColor = polishColors.primary
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = formattedDate, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = polishColors.secondaryContainer,
                        contentColor = polishColors.primary
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = formattedTime, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Conditional toggles based on the active mode (Personal vs. Work)
            val hasGoogleMeet = title.contains("Google Meet", ignoreCase = true) || description.contains("meet.google.com", ignoreCase = true)
            val isImportantEffective = hasGoogleMeet || isImportant

            Surface(
                color = polishColors.background,
                border = BorderStroke(1.dp, polishColors.border.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (!isWorkEnvironment) {
                        // PERSONAL MODE: "Is this during the day?" switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.WbSunny,
                                    contentDescription = null,
                                    tint = polishColors.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Is this during the day?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = polishColors.text
                                )
                            }
                            Switch(
                                checked = isWorkday,
                                onCheckedChange = { isWorkday = it },
                                modifier = Modifier.testTag("is_workday_switch"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = polishColors.primary,
                                    uncheckedThumbColor = polishColors.border,
                                    uncheckedTrackColor = polishColors.background
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isWorkday) {
                                "☀️ Marked as occurring during the day. Activates the workday exception protocol, requiring email confirmation to dismiss."
                            } else {
                                "🌙 Marked as not during the day. Alarms will be sticky and ring, but no email confirmation is required to dismiss."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = polishColors.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    } else {
                        // WORK MODE: "Is this event important?" switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PriorityHigh,
                                    contentDescription = null,
                                    tint = polishColors.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Is this event important?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = polishColors.text
                                )
                            }
                            Switch(
                                checked = isImportantEffective,
                                onCheckedChange = { isImportant = it },
                                enabled = !hasGoogleMeet,
                                modifier = Modifier.testTag("is_important_switch"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = polishColors.primary,
                                    uncheckedThumbColor = polishColors.border,
                                    uncheckedTrackColor = polishColors.background
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (hasGoogleMeet) {
                                "🔒 Google Meet links are important by default. Alarms will ring 1 day and 1 hour before, with a sticky alarm 2 minutes before."
                            } else if (isImportant) {
                                "🔥 Marked as important. Alarms will ring 1 day and 1 hour before, with a sticky alarm 2 minutes before."
                            } else {
                                "⏱️ Standard importance. Alarms will trigger as standard default notifications with no sticky alarms."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = polishColors.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (title.isBlank()) {
                        Toast.makeText(context, "Please enter an event title.", Toast.LENGTH_SHORT).show()
                    } else if (selectedDateTimeMillis <= System.currentTimeMillis()) {
                        Toast.makeText(context, "Please select a date and time in the future.", Toast.LENGTH_SHORT).show()
                    } else {
                        onAddEvent(title, description, selectedDateTimeMillis, isWorkday, isImportantEffective)
                        // Reset form fields
                        title = ""
                        description = ""
                        isImportant = false
                        val todayDow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                        isWorkday = todayDow != Calendar.FRIDAY && todayDow != Calendar.SATURDAY
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("add_event_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = polishColors.primary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Icon(Icons.Default.AddAlarm, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Schedule Event & Alarms", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun EventItemCard(
    event: Event,
    onDelete: () -> Unit,
    onToggleEmail: () -> Unit,
    onSyncCalendar: () -> Unit,
    isWorkEnvironment: Boolean = false,
    onToggleMode: () -> Unit
) {
    val currentTime = System.currentTimeMillis()
    val eventDate = Date(event.dateTimeMillis)
    val formattedDate = remember(event.dateTimeMillis) {
        SimpleDateFormat("EEE, MMM dd, yyyy - hh:mm a", Locale.getDefault()).format(eventDate)
    }

    val polishColors = LocalPolishColors.current
    val isDark = isSystemInDarkTheme()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = polishColors.surface
        ),
        border = BorderStroke(1.dp, polishColors.border),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                            fontWeight = FontWeight.Bold,
                            color = polishColors.text,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        
                        // Dynamic ALARM ACTIVE / QUEUED Badge depending on the 2-week window
                        val isWithinTwoWeeks = event.dateTimeMillis <= currentTime + 14L * 24 * 60 * 60 * 1000
                        val badgeBg = if (isWithinTwoWeeks) {
                            polishColors.primary.copy(alpha = 0.15f)
                        } else {
                            polishColors.border.copy(alpha = 0.5f)
                        }
                        val badgeTextColor = if (isWithinTwoWeeks) {
                            polishColors.primary
                        } else {
                            polishColors.onSurfaceVariant.copy(alpha = 0.8f)
                        }
                        val badgeLabel = if (isWithinTwoWeeks) "ALARM ACTIVE" else "QUEUED"
                        
                        Box(
                            modifier = Modifier
                                        .background(badgeBg, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = badgeTextColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = polishColors.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_event_button_${event.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Event",
                        tint = polishColors.alarmText.copy(alpha = 0.8f)
                    )
                }
            }

            if (event.description.isNotBlank()) {
                Text(
                    text = event.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = polishColors.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }

            // Next Alarm Info Tag
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(polishColors.background, RoundedCornerShape(12.dp))
                    .border(BorderStroke(0.5.dp, polishColors.border.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AccessAlarms,
                    contentDescription = null,
                    tint = polishColors.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Next Alarm: ${event.getNextReminderText(currentTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = polishColors.text,
                    fontWeight = FontWeight.Medium
                )
            }

            // Interactive mode switch for Personal and Work Mode non-meet links
            val showModeSwitch = if (isWorkEnvironment) {
                !event.isMeeting
            } else {
                true
            }

            if (showModeSwitch) {
                val switchLabel = if (isWorkEnvironment) "Is this event important?" else "Is this during the day?"
                val switchChecked = if (isWorkEnvironment) event.isImportant else event.isWorkday
                val switchIcon = if (isWorkEnvironment) Icons.Default.PriorityHigh else Icons.Default.WbSunny
                val switchDesc = if (isWorkEnvironment) {
                    if (event.isImportant) {
                        "🔥 Important event. Alarms 1 day, 1 hour, and sticky 2m before."
                    } else {
                        "⏱️ Standard event. Standard default notifications."
                    }
                } else {
                    if (event.isWorkday) {
                        "☀️ During the day. Alarms require email confirmation to dismiss."
                    } else {
                        "🌙 Not during the day. Sticky alarms with no email required."
                    }
                }

                Surface(
                    color = polishColors.background,
                    border = BorderStroke(0.5.dp, polishColors.border.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = switchIcon,
                                    contentDescription = null,
                                    tint = polishColors.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = switchLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = polishColors.text
                                )
                            }
                            Switch(
                                checked = switchChecked,
                                onCheckedChange = { onToggleMode() },
                                modifier = Modifier.testTag("event_card_switch_${event.id}"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = polishColors.primary,
                                    uncheckedThumbColor = polishColors.border,
                                    uncheckedTrackColor = polishColors.background
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = switchDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = polishColors.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Workday or Work Environment checklist controls
            val showChecklist = isWorkEnvironment || event.isWorkday
            if (showChecklist) {
                val isMeeting = event.isMeeting
                val actionLabel = if (isWorkEnvironment) {
                    if (isMeeting) "Joined Meeting" else "Work Event Completed"
                } else {
                    "Work Email Sent"
                }
                val statusLabel = if (isWorkEnvironment) {
                    if (isMeeting) "MEETING JOINED" else "EVENT COMPLETED"
                } else {
                    "EMAIL SENT"
                }
                val defaultStatusLabel = "STICKY PROMPT"
                val defaultActionLabel = if (isWorkEnvironment) {
                    if (isMeeting) "Mark as Joined" else "Confirm Event Completed"
                } else {
                    "Confirm Work Email Sent"
                }
                val actionBtnLabel = if (isWorkEnvironment) {
                    if (isMeeting) {
                        if (event.isEmailSent) "Reset" else "Join"
                    } else {
                        if (event.isEmailSent) "Reset" else "Complete"
                    }
                } else {
                    if (event.isEmailSent) "Reset" else "Mark Sent"
                }
                val icon = if (isWorkEnvironment) {
                    if (isMeeting) {
                        if (event.isEmailSent) Icons.Default.CheckCircle else Icons.Default.Call
                    } else {
                        if (event.isEmailSent) Icons.Default.CheckCircle else Icons.Default.Check
                    }
                } else {
                    if (event.isEmailSent) Icons.Default.MailOutline else Icons.Default.Mail
                }

                val containerBg = if (event.isEmailSent) {
                    if (isDark) Color(0xFF1B3D21) else Color(0xFFE8F5E9)
                } else {
                    if (isWorkEnvironment) {
                        if (isDark) Color(0xFF1F2B36) else Color(0xFFE3F2FD)
                    } else {
                        polishColors.stickyBg
                    }
                }
                val borderStrokeColor = if (event.isEmailSent) {
                    if (isDark) Color(0xFF2E7D32) else Color(0xFFA5D6A7)
                } else {
                    if (isWorkEnvironment) {
                        if (isDark) Color(0xFF1976D2) else Color(0xFF90CAF9)
                    } else {
                        polishColors.stickyBorder
                    }
                }
                val textColor = if (event.isEmailSent) {
                    if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                } else {
                    if (isWorkEnvironment) {
                        if (isDark) Color(0xFF90CAF9) else Color(0xFF1565C0)
                    } else {
                        polishColors.stickyButton
                    }
                }

                Surface(
                    color = containerBg,
                    border = BorderStroke(1.5.dp, borderStrokeColor),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(if (isDark) Color(0xFF25232A) else Color.White, RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (event.isEmailSent) {
                                        if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                                    } else {
                                        textColor
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (event.isEmailSent) statusLabel else defaultStatusLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = textColor.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = if (event.isEmailSent) actionLabel else defaultActionLabel,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = textColor
                                )
                            }
                        }

                        Button(
                            onClick = onToggleEmail,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (event.isEmailSent) {
                                    if (isDark) Color(0xFF2E7D32) else Color(0xFF2E7D32)
                                } else {
                                    textColor
                                },
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = actionBtnLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Sync with Google Calendar option
            OutlinedButton(
                onClick = onSyncCalendar,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = polishColors.primary
                ),
                border = BorderStroke(1.dp, polishColors.border),
                contentPadding = PaddingValues(vertical = 10.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Google Calendar Entry", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
