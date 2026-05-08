package com.example.untiswecker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val sessionManager = SessionManager(context)

    fun scheduleNextAlarm(timetable: List<TimetableEntry>) {
        val offsets = sessionManager.getAlarms()
        if (offsets.isEmpty()) {
            cancelAllAlarms()
            return
        }

        val result = findNextAlarm(offsets, timetable)
        if (result == null) {
            Log.d("AlarmScheduler", "No future lessons found to schedule an alarm.")
            return
        }

        val (nextAlarmTime, lesson) = result

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("lesson_json", Json.encodeToString(lesson))
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = nextAlarmTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                // Fallback or request permission
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
        
        Log.d("AlarmScheduler", "Scheduled alarm for $nextAlarmTime")
    }

    private fun cancelAllAlarms() {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d("AlarmScheduler", "Cancelled all alarms")
    }

    private fun findNextAlarm(offsets: List<Int>, timetable: List<TimetableEntry>): Pair<LocalDateTime, TimetableEntry>? {
        val now = LocalDateTime.now()
        
        // We look ahead for several days
        val daysToPixels = listOf(0, 1, 2, 3, 4, 5, 6, 7)
        
        val alarms = mutableListOf<Pair<LocalDateTime, TimetableEntry>>()
        
        for (dayOffset in daysToPixels) {
            val date = now.plusDays(dayOffset.toLong()).toLocalDate()
            val dateInt = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
            
            val firstLesson = timetable
                .filter { it.date == dateInt && it.code != "cancelled" }
                .minByOrNull { it.startTime }
            
            if (firstLesson != null) {
                val lessonTime = LocalTime.of(firstLesson.startTime / 100, firstLesson.startTime % 100)
                val lessonDateTime = LocalDateTime.of(date, lessonTime)
                
                offsets.forEach { offset ->
                    val alarmTime = lessonDateTime.minusMinutes(offset.toLong())
                    if (alarmTime.isAfter(now)) {
                        alarms.add(alarmTime to firstLesson)
                    }
                }
            }
        }
        
        return alarms.minByOrNull { it.first }
    }
}
