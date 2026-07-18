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
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
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
    var selectedTab by remember { mutableStateOf(0) }
    val now = System.currentTimeMillis()
    val threeWeeksAgo = now - 3L * 7 * 24 * 60 * 60 * 1000
    
    val filteredEvents = remember(events) {
        events.filter { it.dateTimeMillis >= threeWeeksAgo }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(polishColors.background)
    ) {
        // Tab Row at the top with elegant Material 3 styling
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = polishColors.surface,
            contentColor = polishColors.primary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = polishColors.primary
                )
            },
            divider = {
                HorizontalDivider(color = polishColors.border.copy(alpha = 0.5f))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Text(
                        text = "Upcoming Events",
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.titleSmall
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Event,
                        contentDescription = "Upcoming Events",
                        tint = if (selectedTab == 0) polishColors.primary else polishColors.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                modifier = Modifier.testTag("tab_upcoming_events")
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Text(
                        text = "Enter Event",
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.titleSmall
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.AddCircleOutline,
                        contentDescription = "Enter Event",
                        tint = if (selectedTab == 1) polishColors.primary else polishColors.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                modifier = Modifier.testTag("tab_enter_event")
            )
        }

        // Tab Content view container
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            when (selectedTab) {
                0 -> {
                    // TAB 0: Upcoming & Past Events (up to 3 weeks back)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Cosmic Banner
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
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
                                        text = "Calendar Event Alarms",
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

                        // Section header with Sync Controls & Active Badge
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "Scheduled Alarms List",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = polishColors.primary
                                    )
                                    Text(
                                        text = "Showing up to 3 weeks back. Alarms active for future.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = polishColors.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            viewModel.syncGoogleCalendar(context) { count ->
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
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sync,
                                            contentDescription = "Sync with Google Calendar",
                                            tint = polishColors.primary
                                        )
                                    }
                                    Badge(
                                        containerColor = polishColors.primary.copy(alpha = 0.2f),
                                        contentColor = polishColors.primary
                                    ) {
                                        Text(
                                            text = "${filteredEvents.size} Events",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Event Cards List
                        if (filteredEvents.isEmpty()) {
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
                                            text = "No Scheduled Alarms",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = polishColors.text
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Add an event in 'Enter Event' tab, or sync from your Google Calendar automatically.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = polishColors.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        } else {
                            items(filteredEvents, key = { it.id }) { event ->
                                EventItemCard(
                                    event = event,
                                    onDelete = { viewModel.deleteEvent(event) },
                                    onToggleEmail = { viewModel.toggleEmailSent(event) },
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
                                    }
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                }
                1 -> {
                    // TAB 1: Enter / Create Event & Instant Alarm Test Playground
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
                                onAddEvent = { title, desc, time, isWorkday ->
                                    viewModel.addEvent(title, desc, time, isWorkday)
                                    
                                    // Trigger Google Calendar Insert Intent directly!
                                    val calendarIntent = Intent(Intent.ACTION_INSERT).apply {
                                        data = CalendarContract.Events.CONTENT_URI
                                        putExtra(CalendarContract.Events.TITLE, title)
                                        putExtra(CalendarContract.Events.DESCRIPTION, desc + "\n\n[Reminders configured in Calendar Event Alarms app]")
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
            }
        }
    }
}

@Composable
fun CreateEventCard(
    onAddEvent: (String, String, Long, Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isWorkday by remember { mutableStateOf(false) }

    val calendar = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableStateOf(calendar.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(calendar.get(Calendar.MONTH)) }
    var selectedDay by remember { mutableStateOf(calendar.get(Calendar.DAY_OF_MONTH)) }
    var selectedHour by remember { mutableStateOf(10) } // Default 10 AM
    var selectedMinute by remember { mutableStateOf(0) }

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

            // Workday toggles with helpful hint text
            Surface(
                color = polishColors.background,
                border = BorderStroke(1.dp, polishColors.border.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Work,
                                contentDescription = null,
                                tint = polishColors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Is this during a Workday?",
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
                    if (isWorkday) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "If enabled, your alarm triggers a mandatory email reminder check. The ringing sound can be silenced, but the persistent system notification cannot be swiped away until you mark the job email as sent.",
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
                        onAddEvent(title, description, selectedDateTimeMillis, isWorkday)
                        // Reset form fields
                        title = ""
                        description = ""
                        isWorkday = false
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
    onSyncCalendar: () -> Unit
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
                        
                        // Dynamic PASSED / ACTIVE Event Badge
                        val hasPassed = event.dateTimeMillis < currentTime
                        val badgeBg = if (hasPassed) {
                            polishColors.border.copy(alpha = 0.5f)
                        } else {
                            polishColors.primary.copy(alpha = 0.15f)
                        }
                        val badgeTextColor = if (hasPassed) {
                            polishColors.onSurfaceVariant.copy(alpha = 0.8f)
                        } else {
                            polishColors.primary
                        }
                        val badgeLabel = if (hasPassed) "PASSED" else "ACTIVE"
                        
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

            // Workday checklist controls (HTML Sticky Notification Style!)
            if (event.isWorkday) {
                val containerBg = if (event.isEmailSent) {
                    if (isDark) Color(0xFF1B3D21) else Color(0xFFE8F5E9)
                } else {
                    polishColors.stickyBg
                }
                val borderStrokeColor = if (event.isEmailSent) {
                    if (isDark) Color(0xFF2E7D32) else Color(0xFFA5D6A7)
                } else {
                    polishColors.stickyBorder
                }
                val textColor = if (event.isEmailSent) {
                    if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                } else {
                    polishColors.stickyButton
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
                                    imageVector = if (event.isEmailSent) Icons.Default.MailOutline else Icons.Default.Mail,
                                    contentDescription = null,
                                    tint = if (event.isEmailSent) {
                                        if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                                    } else {
                                        polishColors.alarmText
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (event.isEmailSent) "EMAIL SENT" else "STICKY PROMPT",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = textColor.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = if (event.isEmailSent) "Work Email Confirmed" else "Confirm Work Email Sent",
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
                                    polishColors.stickyButton
                                },
                                contentColor = if (event.isEmailSent && isDark) Color.White else Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = if (event.isEmailSent) "Reset" else "Mark Sent",
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
