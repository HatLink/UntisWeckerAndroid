package com.example.untiswecker

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.Json
import com.example.untiswecker.ui.theme.UntisWeckerTheme

class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val lessonJson = intent.getStringExtra("lesson_json")
        val lesson = lessonJson?.let {
            try {
                Json.decodeFromString<TimetableEntry>(it)
            } catch (e: Exception) {
                null
            }
        }

        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, null)
        }

        setContent {
            UntisWeckerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "ALARM!",
                            style = MaterialTheme.typography.headlineLarge
                        )

                        if (lesson != null) {
                            val subject = lesson.su?.firstOrNull()?.getDisplayName() ?: lesson.lstext ?: "School"
                            val room = lesson.ro?.firstOrNull()?.getDisplayName(preferLong = false) ?: ""
                            val time = formatUntisTime(lesson.startTime)
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = subject,
                                style = MaterialTheme.typography.titleLarge
                            )
                            if (room.isNotEmpty()) {
                                Text(
                                    text = "Room: $room",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Text(
                                text = "Starts at $time",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Spacer(modifier = Modifier.height(32.dp))
                        Row {
                            Button(onClick = {
                                stopAlarmService()
                                snoozeAlarm(lessonJson)
                                finish()
                            }) {
                                Text("Snooze (9 min)")
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Button(onClick = {
                                stopAlarmService()
                                rescheduleNext()
                                finish()
                            }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatUntisTime(time: Int): String {
        val h = time / 100
        val m = time % 100
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", h, m)
    }

    private fun stopAlarmService() {
        val intent = Intent(this, AlarmService::class.java)
        stopService(intent)
    }

    private fun snoozeAlarm(lessonJson: String?) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = android.content.Intent(this, AlarmReceiver::class.java).apply {
            putExtra("lesson_json", lessonJson)
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAtMillis = System.currentTimeMillis() + 9 * 60 * 1000 // 9 minutes snooze
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun rescheduleNext() {
        val sessionManager = SessionManager(this)
        val sessionData = sessionManager.getSessionData()
        val scheduler = AlarmScheduler(this)
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val client = UntisClient(sessionData.server, sessionData.school)
            val authResponse = client.authenticate(sessionData.user, sessionData.pass)
            if (authResponse.result != null) {
                val result = authResponse.result
                val personId = sessionData.personId ?: result.extractPersonId() ?: 0
                val personType = sessionData.personType ?: result.extractPersonType() ?: 5

                val today = LocalDate.now()
                val todayInt = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
                val nextWeek = today.plusDays(7)
                val nextWeekInt = nextWeek.year * 10000 + nextWeek.monthValue * 100 + nextWeek.dayOfMonth
                
                val response = client.getTimetable(
                    personId,
                    personType,
                    todayInt,
                    nextWeekInt
                )
                if (response?.result != null) {
                    // Fetch master data for hydration
                    val subjectsMap = client.getSubjects()?.result?.filter { it.id != null }?.associateBy { it.id!! } ?: emptyMap()
                    val teachersMap = client.getTeachers()?.result?.filter { it.id != null }?.associateBy { it.id!! } ?: emptyMap()
                    val klassenMap = client.getKlassen()?.result?.filter { it.id != null }?.associateBy { it.id!! } ?: emptyMap()
                    val roomsMap = client.getRooms()?.result?.filter { it.id != null }?.associateBy { it.id!! } ?: emptyMap()

                    val hydratedTimetable = response.result.map { entry ->
                        entry.copy(
                            su = entry.su?.map { s -> subjectsMap[s.id] ?: s },
                            te = entry.te?.map { t -> teachersMap[t.id] ?: t },
                            kl = entry.kl?.map { k -> klassenMap[k.id] ?: k },
                            ro = entry.ro?.map { r -> roomsMap[r.id] ?: r }
                        )
                    }

                    scheduler.scheduleNextAlarm(hydratedTimetable)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
