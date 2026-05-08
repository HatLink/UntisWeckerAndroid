package com.example.untiswecker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val sessionManager = SessionManager(context)
            val sessionData = sessionManager.getSessionData()
            
            if (sessionData.user.isNotEmpty() && sessionData.pass.isNotEmpty()) {
                val scheduler = AlarmScheduler(context)
                
                // Fetch timetable and reschedule in background
                CoroutineScope(Dispatchers.IO).launch {
                    val client = UntisClient(sessionData.server, sessionData.school)
                    val authResponse = client.authenticate(sessionData.user, sessionData.pass)
                    if (authResponse.result != null) {
                        val today = LocalDate.now()
                        val todayInt = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
                        val nextWeek = today.plusDays(7)
                        val nextWeekInt = nextWeek.year * 10000 + nextWeek.monthValue * 100 + nextWeek.dayOfMonth
                        
                        val response = client.getTimetable(
                            sessionData.personId ?: 0,
                            sessionData.personType ?: 5,
                            todayInt,
                            nextWeekInt
                        )
                        
                        response?.result?.let {
                            scheduler.scheduleNextAlarm(it)
                        }
                    }
                }
            }
        }
    }
}
