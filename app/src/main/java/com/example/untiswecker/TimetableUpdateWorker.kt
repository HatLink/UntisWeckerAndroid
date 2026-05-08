package com.example.untiswecker

import android.content.Context
import androidx.work.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class TimetableUpdateWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val sessionManager = SessionManager(applicationContext)
        val sessionData = sessionManager.getSessionData()
        
        if (sessionData.user.isEmpty() || sessionData.pass.isEmpty()) {
            return Result.failure()
        }

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

                val scheduler = AlarmScheduler(applicationContext)
                scheduler.scheduleNextAlarm(hydratedTimetable)
                return Result.success()
            }
        }
        
        return Result.retry()
    }

    companion object {
        private const val WORK_NAME = "timetable_update_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<TimetableUpdateWorker>(3, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
