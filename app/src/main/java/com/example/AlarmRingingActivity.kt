package com.example

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppDatabase
import com.example.data.Event
import com.example.service.AlarmService
import com.example.ui.theme.LocalPolishColors
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AlarmRingingActivity : ComponentActivity() {
    private var eventId: Int = -1
    private var reminderLabel: String = "Event Reminder"
    private var eventTitle: String = "Calendar Event"
    private var isWorkday: Boolean = false
    private var isMeeting: Boolean = false
    private var initialSilenced: Boolean = false

    companion object {
        private const val TAG = "AlarmRingingActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Show over lockscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, null)
        }

        // Parse extras
        eventId = intent.getIntExtra("EVENT_ID", -1)
        reminderLabel = intent.getStringExtra("REMINDER_LABEL") ?: "Event Reminder"
        eventTitle = intent.getStringExtra("EVENT_TITLE") ?: "Calendar Event"
        isWorkday = intent.getBooleanExtra("IS_WORKDAY", false)
        isMeeting = intent.getBooleanExtra("IS_MEETING", false)
        initialSilenced = intent.getBooleanExtra("SILENCED", false)

        Log.d(TAG, "onCreate: eventId=$eventId, title=$eventTitle, isWorkday=$isWorkday, isMeeting=$isMeeting, silenced=$initialSilenced")

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlarmRingingScreen(
                        initEventId = eventId,
                        initTitle = eventTitle,
                        initLabel = reminderLabel,
                        initWorkday = isWorkday,
                        initMeeting = isMeeting,
                        initSilenced = initialSilenced,
                        onActionTriggered = { action ->
                            sendServiceAction(action)
                            if (action != AlarmService.ACTION_SILENCE) {
                                finish()
                            }
                        },
                        onCloseActivity = {
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun sendServiceAction(action: String) {
        val serviceIntent = Intent(this, AlarmService::class.java).apply {
            this.action = action
            putExtra("EVENT_ID", eventId)
            putExtra("REMINDER_LABEL", reminderLabel)
            putExtra("EVENT_TITLE", eventTitle)
            putExtra("IS_WORKDAY", isWorkday)
            putExtra("IS_MEETING", isMeeting)
        }
        try {
            startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send action $action to service: ${e.message}", e)
        }
    }
}

@Composable
fun AlarmRingingScreen(
    initEventId: Int,
    initTitle: String,
    initLabel: String,
    initWorkday: Boolean,
    initMeeting: Boolean,
    initSilenced: Boolean,
    onActionTriggered: (String) -> Unit,
    onCloseActivity: () -> Unit
) {
    val context = LocalContext.current
    val polishColors = LocalPolishColors.current

    // Live clock state
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        while (true) {
            currentTime = timeFormat.format(Date())
            delay(1000)
        }
    }

    // Load full event details from database
    var dbEvent by remember { mutableStateOf<Event?>(null) }
    LaunchedEffect(initEventId) {
        if (initEventId != -1) {
            try {
                val db = AppDatabase.getDatabase(context)
                dbEvent = db.eventDao().getEventById(initEventId)
            } catch (e: Exception) {
                Log.e("AlarmRingingScreen", "Error loading event details: ${e.message}", e)
            }
        }
    }

    // Resolve details
    val eventTitle = dbEvent?.title ?: initTitle
    val description = dbEvent?.description ?: ""
    val isWorkday = dbEvent?.isWorkday ?: initWorkday
    val isMeeting = dbEvent?.isMeeting ?: initMeeting
    val isImportant = dbEvent?.isImportant ?: false

    // Silenced status state
    var isSilenced by remember { mutableStateOf(initSilenced) }

    // Pulsing animations for active alarm
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // TOP HEADER: Mode Protocol
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                color = if (isWorkday) polishColors.primary.copy(alpha = 0.15f) else polishColors.alarmBg,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    if (isWorkday) polishColors.primary.copy(alpha = 0.4f) else polishColors.alarmBorder
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isWorkday) Icons.Default.Work else Icons.Default.Home,
                        contentDescription = null,
                        tint = if (isWorkday) polishColors.primary else polishColors.alarmText,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isWorkday) "💼 WORK MODE PROTOCOL" else "🏡 PERSONAL MODE PROTOCOL",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isWorkday) polishColors.primary else polishColors.alarmText,
                        letterSpacing = 1.2.sp
                    )
                }
            }

            // Live digital clock with beautiful clear display
            Text(
                text = currentTime,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 32.sp
                ),
                color = polishColors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(
                text = initLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = polishColors.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }

        // MIDDLE AREA: Pulsing visual alert & Big Event Details
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 24.dp)
        ) {
            // Pulse Ring (Only when ringing)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(150.dp)
            ) {
                if (!isSilenced) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .scale(pulseScale)
                            .background(
                                color = (if (isWorkday) polishColors.primary else polishColors.alarmText).copy(
                                    alpha = pulseAlpha
                                ),
                                shape = CircleShape
                            )
                    )
                }

                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(
                            color = if (isSilenced) {
                                polishColors.onSurfaceVariant.copy(alpha = 0.1f)
                            } else if (isWorkday) {
                                polishColors.primary.copy(alpha = 0.2f)
                            } else {
                                polishColors.alarmBg
                            },
                            shape = CircleShape
                        )
                        .border(
                            width = 2.dp,
                            color = if (isSilenced) {
                                polishColors.onSurfaceVariant.copy(alpha = 0.3f)
                            } else if (isWorkday) {
                                polishColors.primary
                            } else {
                                polishColors.alarmBorder
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSilenced) {
                            Icons.Default.VolumeOff
                        } else if (isMeeting) {
                            Icons.Default.Call
                        } else if (isWorkday) {
                            Icons.Default.Email
                        } else {
                            Icons.Default.Alarm
                        },
                        contentDescription = "Alarm Status",
                        tint = if (isSilenced) {
                            polishColors.onSurfaceVariant
                        } else if (isWorkday) {
                            polishColors.primary
                        } else {
                            polishColors.alarmText
                        },
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Giant Event Title
            Text(
                text = eventTitle,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp,
                    lineHeight = 34.sp
                ),
                color = polishColors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("ringing_event_title")
            )

            // Description / Context details
            if (description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = polishColors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                )
            }

            // Warning instruction box based on state
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSilenced) {
                        polishColors.primary.copy(alpha = 0.08f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    }
                ),
                border = BorderStroke(1.dp, polishColors.border.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isSilenced) Icons.Default.Info else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isSilenced) polishColors.primary else polishColors.alarmText,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isSilenced) "🔇 Alarm Silenced — Action Pending" else "⏰ Active Alarm Ringing",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSilenced) polishColors.primary else polishColors.alarmText
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val instructions = if (isWorkday) {
                        "🛡️ Workday Protocol Active:\nYou must mark the email to your manager as sent to completely dismiss the alarm and sticky notification."
                    } else if (isMeeting) {
                        "🤝 Meeting Protocol Active:\nYou must join or mark this meeting as joined to completely dismiss the alarm and sticky notification."
                    } else {
                        "🏡 Personal Event Active:\nYou must mark this event as completed to completely dismiss the alarm."
                    }
                    Text(
                        text = instructions,
                        style = MaterialTheme.typography.bodySmall,
                        color = polishColors.onSurfaceVariant.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // BOTTOM ACTION CENTER: Big Buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main Action (Mark Complete / Mark Sent / Join Meeting)
            val mainBtnLabel = if (isWorkday) {
                "Mark Email as Sent"
            } else if (isMeeting) {
                "Join Meeting"
            } else {
                "Mark Event Completed"
            }
            val mainBtnIcon = if (isWorkday) {
                Icons.Default.Email
            } else if (isMeeting) {
                Icons.Default.Call
            } else {
                Icons.Default.CheckCircle
            }

            Button(
                onClick = {
                    if (isMeeting) {
                        onActionTriggered(AlarmService.ACTION_JOIN_MEETING)
                    } else {
                        onActionTriggered(AlarmService.ACTION_MARK_SENT)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSilenced) polishColors.primary else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .testTag("ringing_main_action_btn"),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = mainBtnIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = mainBtnLabel,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = Color.White
                    )
                }
            }

            // Secondary Actions Row: Silence and Snooze
            if (!isSilenced) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Snooze Button (Saves 5 more mins)
                    Button(
                        onClick = {
                            onActionTriggered(AlarmService.ACTION_SNOOZE)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        border = BorderStroke(1.dp, polishColors.border.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("ringing_snooze_btn"),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Snooze,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Snooze",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Silence Button
                    Button(
                        onClick = {
                            onActionTriggered(AlarmService.ACTION_SILENCE)
                            isSilenced = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, polishColors.border),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("ringing_silence_btn"),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeOff,
                                contentDescription = null,
                                tint = polishColors.text,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Silence",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = polishColors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // If it is ALREADY silenced, we don't show Snooze/Silence.
                // Instead, we can show a Dismiss/Close screen button to dismiss only the screen but keep the notification sticky.
                OutlinedButton(
                    onClick = {
                        onCloseActivity()
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, polishColors.border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("ringing_close_btn")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = polishColors.text,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Minimize Screen (Keep Sticky Notification)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = polishColors.text
                        )
                    }
                }
            }
        }
    }
}
