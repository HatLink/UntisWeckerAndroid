package com.example.untiswecker

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import android.util.Log
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.text.input.KeyboardType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.example.untiswecker.ui.theme.UntisWeckerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UntisWeckerTheme {
                val context = LocalContext.current
                val sessionManager = remember { SessionManager(context) }
                var isLoggedIn by remember { mutableStateOf(sessionManager.isLoggedIn()) }

                if (isLoggedIn) {
                    PermissionHandler {
                        UntisWeckerApp(onLogout = {
                            sessionManager.logout()
                            isLoggedIn = false
                        })
                    }
                } else {
                    LoginScreen(onLoginSuccess = { server, school, user, pass, pId, pType ->
                        sessionManager.saveSession(server, school, user, pass, pId, pType)
                        isLoggedIn = true
                    })
                }
            }
        }
    }
}

@Composable
fun PermissionHandler(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasNotificationPermission = isGranted }
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Also check for exact alarm permission on Android 13+
    var hasExactAlarmPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.canScheduleExactAlarms()
            } else true
        )
    }

    if (!hasExactAlarmPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("This app needs permission to schedule exact alarms to wake you up on time.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                context.startActivity(intent)
            }) {
                Text("Grant Permission")
            }
            TextButton(onClick = { hasExactAlarmPermission = true }) {
                Text("I'll do it later")
            }
        }
    } else {
        content()
    }
}

@Composable
fun UntisWeckerApp(onLogout: () -> Unit) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    val sessionData = remember { sessionManager.getSessionData() }
    
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.ALARMS) }
    var alarms by remember { mutableStateOf(sessionManager.getAlarms()) }
    var timetable by remember { mutableStateOf<List<TimetableEntry>>(emptyList()) }
    var isLoadingTimetable by remember { mutableStateOf(false) }
    var timetableError by remember { mutableStateOf<String?>(null) }

    val scheduler = remember { AlarmScheduler(context) }

    LaunchedEffect(Unit) {
        isLoadingTimetable = true
        timetableError = null
        Log.d("UntisWecker", "Fetching timetable for ${sessionData.user} at ${sessionData.server}")
        val client = UntisClient(sessionData.server, sessionData.school)
        
        try {
            // Ensure we are authenticated to get a session for the timetable
            val authResponse = client.authenticate(sessionData.user, sessionData.pass)
            Log.d("UntisWecker", "Auth response: $authResponse")

            if (authResponse.error != null) {
                timetableError = "Auth failed: ${authResponse.error.message}"
            } else {
                // Use extracted IDs from auth if the session data didn't have them yet (though it should)
                val personId = sessionData.personId ?: authResponse.result?.extractPersonId() ?: 0
                val personType = sessionData.personType ?: authResponse.result?.extractPersonType() ?: 5
                
                // If we extracted new IDs, save them for next time
                if (personId != 0 && (sessionData.personId == null || sessionData.personType == null)) {
                    sessionManager.saveSession(
                        sessionData.server, sessionData.school, sessionData.user, sessionData.pass,
                        personId, personType
                    )
                }
                
                val today = LocalDate.now()
                val todayInt = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
                val endDate = today.plusDays(7)
                val endDateInt = endDate.year * 10000 + endDate.monthValue * 100 + endDate.dayOfMonth
                
                Log.d("UntisWecker", "Requesting timetable for personId=$personId, personType=$personType from $todayInt to $endDateInt")
                
                val response = client.getTimetable(
                    personId,
                    personType,
                    todayInt,
                    endDateInt
                )
                Log.d("UntisWecker", "Timetable response: ${response?.result?.size ?: 0} entries")

                if (response?.result != null) {
                    timetable = response.result
                    scheduler.scheduleNextAlarm(timetable)
                    TimetableUpdateWorker.schedule(context)
                } else if (response?.error != null) {
                    timetableError = response.error.message
                } else {
                    timetableError = "Could not fetch timetable"
                }
            }
        } catch (e: Exception) {
            timetableError = e.message ?: "Unknown error occurred"
            Log.e("UntisWecker", "Error fetching timetable", e)
        } finally {
            isLoadingTimetable = false
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            it.icon,
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentDestination) {
                    AppDestinations.ALARMS -> AlarmPage(
                        alarms = alarms,
                        timetable = timetable,
                        isLoading = isLoadingTimetable,
                        error = timetableError,
                        onAddAlarm = { offset ->
                            val newList = alarms + offset
                            alarms = newList
                            sessionManager.saveAlarms(newList)
                            scheduler.scheduleNextAlarm(timetable)
                        },
                        onDeleteAlarm = { index ->
                            val newList = alarms.toMutableList().apply { removeAt(index) }
                            alarms = newList
                            sessionManager.saveAlarms(newList)
                            scheduler.scheduleNextAlarm(timetable)
                        }
                    )
                    AppDestinations.LESSONS -> LessonPage(timetable, isLoadingTimetable, timetableError)
                    AppDestinations.SETTINGS -> SettingsPage(
                        onLogout = onLogout,
                        onDeleteAllAlarms = {
                            alarms = emptyList()
                            sessionManager.saveAlarms(emptyList())
                            scheduler.scheduleNextAlarm(emptyList())
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AlarmPage(
    alarms: List<Int>,
    timetable: List<TimetableEntry>,
    isLoading: Boolean,
    error: String?,
    onAddAlarm: (Int) -> Unit,
    onDeleteAlarm: (Int) -> Unit
) {
    var hoursInput by remember { mutableStateOf("") }
    var minutesInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Add Alarm Offset", style = MaterialTheme.typography.titleMedium)
        Text("Alarms will trigger X minutes before your first lesson.", style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = hoursInput,
                onValueChange = { hoursInput = it },
                label = { Text("Hours") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = minutesInput,
                onValueChange = { minutesInput = it },
                label = { Text("Mins") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                val h = hoursInput.toIntOrNull() ?: 0
                val m = minutesInput.toIntOrNull() ?: 0
                if (h > 0 || m > 0) {
                    onAddAlarm(h * 60 + m)
                    hoursInput = ""
                    minutesInput = ""
                }
            }) {
                Text("Add")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Updating timetable...", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
        }

        if (error != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text(
                    text = "Error: $error",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (!isLoading && timetable.isEmpty() && error == null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text(
                    text = "No lessons found for the next 7 days. Alarms cannot be scheduled.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Text("Active Alarms", style = MaterialTheme.typography.titleMedium)
        
        if (alarms.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No alarm offsets added.\nAdd one above to wake up before school.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(alarms.size) { index ->
                    val offset = alarms[index]
                    AlarmItem(
                        offsetMinutes = offset,
                        timetable = timetable,
                        onDelete = { onDeleteAlarm(index) }
                    )
                }
            }
        }
    }
}

@Composable
fun AlarmItem(offsetMinutes: Int, timetable: List<TimetableEntry>, onDelete: () -> Unit) {
    val realTime = calculateAlarmTime(offsetMinutes, timetable)
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val h = offsetMinutes / 60
                val m = offsetMinutes % 60
                val label = buildString {
                    if (h > 0) append("$h Hour${if (h > 1) "s" else ""}")
                    if (h > 0 && m > 0) append(", ")
                    if (m > 0) append("$m Minute${if (m > 1) "s" else ""}")
                }
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text("→ $realTime", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(androidx.compose.material.icons.Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
fun LessonPage(timetable: List<TimetableEntry>, isLoading: Boolean, error: String?) {
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (error != null) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Failed to load timetable", style = MaterialTheme.typography.titleMedium)
                Text(error, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }

    if (timetable.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.EventBusy, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Text("No lessons found for the next 7 days.", textAlign = TextAlign.Center)
            }
        }
        return
    }

    val today = LocalDate.now()
    val todayInt = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
    val tomorrow = today.plusDays(1)
    val tomorrowInt = tomorrow.year * 10000 + tomorrow.monthValue * 100 + tomorrow.dayOfMonth

    val todayLessons = timetable.filter { it.date == todayInt }.sortedBy { it.startTime }
    val tomorrowLessons = timetable.filter { it.date == tomorrowInt }.sortedBy { it.startTime }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("Today's Lessons", style = MaterialTheme.typography.titleLarge) }
        if (todayLessons.isEmpty()) {
            item { Text("No lessons today", modifier = Modifier.padding(vertical = 8.dp)) }
        } else {
            items(todayLessons) { LessonItem(it) }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
        item { Text("Tomorrow's Lessons", style = MaterialTheme.typography.titleLarge) }
        if (tomorrowLessons.isEmpty()) {
            item { Text("No lessons tomorrow", modifier = Modifier.padding(vertical = 8.dp)) }
        } else {
            items(tomorrowLessons) { LessonItem(it) }
        }
    }
}

@Composable
fun LessonItem(lesson: TimetableEntry) {
    val isCancelled = lesson.code == "cancelled"
    val isExam = lesson.activityType?.contains("exam", ignoreCase = true) == true
    val isIrregular = lesson.code == "irregular"

    val statusColor = when {
        isCancelled -> Color(0xFF9E9E9E) // Gray
        isExam -> Color(0xFF4CAF50) // Green
        isIrregular -> Color(0xFF9C27B0) // Purple
        else -> Color(0xFFFF9800) // Orange
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.1f)
        ),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left bar with status color
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(statusColor, shape = MaterialTheme.shapes.small)
            )
            
            Spacer(modifier = Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatUntisTime(lesson.startTime),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatUntisTime(lesson.endTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                val su = lesson.su?.firstOrNull()
                val subject = su?.longname ?: su?.longName ?: su?.name ?: lesson.lstext ?: 
                             (if (su != null) "Subject ID: ${su.id}" else null) ?: "Unknown"
                
                Text(
                    text = subject,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (isCancelled) TextDecoration.LineThrough else null,
                    color = if (isCancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                
                val teacher = lesson.te?.firstOrNull()?.let { 
                    it.longname ?: it.longName ?: it.name ?: (if (it.id != null) "Teacher ${it.id}" else null) 
                } ?: ""
                val klass = lesson.kl?.firstOrNull()?.let { 
                    it.name ?: (if (it.id != null) "Class ${it.id}" else null) 
                } ?: ""
                
                if (teacher.isNotEmpty() || klass.isNotEmpty()) {
                    Text(
                        text = listOfNotNull(teacher.ifEmpty { null }, klass.ifEmpty { null }).joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!lesson.substText.isNullOrEmpty() || !lesson.info.isNullOrEmpty()) {
                    Text(
                        text = listOfNotNull(lesson.substText, lesson.info).joinToString(": "),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                val room = lesson.ro?.firstOrNull()?.let { 
                    it.name ?: (if (it.id != null) "Room ${it.id}" else null) 
                } ?: ""
                Text(
                    text = room,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                if (isCancelled) {
                    Text(
                        text = "CANCELLED",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                } else if (isExam) {
                    Text(
                        text = "EXAM",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsPage(onLogout: () -> Unit, onDeleteAllAlarms: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDeleteAllAlarms, modifier = Modifier.fillMaxWidth()) {
            Text("Delete All Alarms")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Text("Sign out")
        }
    }
}

private fun formatUntisTime(time: Int): String {
    val h = time / 100
    val m = time % 100
    return String.format(Locale.getDefault(), "%02d:%02d", h, m)
}

private fun calculateAlarmTime(offsetMinutes: Int, timetable: List<TimetableEntry>): String {
    val now = LocalDateTime.now()
    
    for (dayOffset in 0..7) {
        val date = now.plusDays(dayOffset.toLong()).toLocalDate()
        val dateInt = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        
        val firstLesson = timetable
            .filter { it.date == dateInt && it.code != "cancelled" }
            .minByOrNull { it.startTime }
        
        if (firstLesson != null) {
            val lessonTime = LocalTime.of(firstLesson.startTime / 100, firstLesson.startTime % 100)
            val lessonDateTime = LocalDateTime.of(date, lessonTime)
            val alarmDateTime = lessonDateTime.minusMinutes(offsetMinutes.toLong())
            
            if (alarmDateTime.isAfter(now)) {
                val dayLabel = when (dayOffset) {
                    0 -> "Today"
                    1 -> "Tomorrow"
                    else -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
                }
                return "$dayLabel, ${alarmDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
            }
        }
    }

    return "No Upcoming Lessons"
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    ALARMS("Alarms", Icons.Default.Alarm),
    LESSONS("Lessons", Icons.Default.CalendarMonth),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    UntisWeckerTheme {
        Greeting("Android")
    }
}