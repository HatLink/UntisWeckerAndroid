package com.example.untiswecker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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

        val nextAlarmTime = findNextAlarmDateTime(offsets, timetable)
        if (nextAlarmTime == null) {
            Log.d("AlarmScheduler", "No future lessons found to schedule an alarm.")
            return
        }

        val intent = Intent(context, AlarmReceiver::class.java)
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

    private fun findNextAlarmDateTime(offsets: List<Int>, timetable: List<TimetableEntry>): LocalDateTime? {
        val now = LocalDateTime.now()
        
        // We look ahead for today and tomorrow
        val daysToPixels = listOf(0, 1, 2, 3, 4, 5, 6, 7)
        
        for (dayOffset in daysToPixels) {
            val date = now.plusDays(dayOffset.toLong()).toLocalDate()
            val dateInt = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
            
            val firstLesson = timetable
                .filter { it.date == dateInt && it.code != "cancelled" }
                .minByOrNull { it.startTime }
            
            if (firstLesson != null) {
                val lessonTime = LocalTime.of(firstLesson.startTime / 100, firstLesson.startTime % 100)
                val lessonDateTime = LocalDateTime.of(date, lessonTime)
                
                // For this day, find the earliest alarm that is still in the future
                val earliestAlarmThisDay = offsets.map { lessonDateTime.minusMinutes(it.toLong()) }
                    .filter { it.isAfter(now) }
                    .minByOrNull { it }
                
                if (earliestAlarmThisDay != null) {
                    return earliestAlarmThisDay
                }
            }
        }
        
        return null
    }
}
