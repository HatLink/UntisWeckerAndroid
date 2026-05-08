package com.example.untiswecker

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "untis_session",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveSession(server: String, school: String, user: String, pass: String, personId: Int? = null, personType: Int? = null) {
        prefs.edit().apply {
            putString("server", server)
            putString("school", school)
            putString("user", user)
            putString("pass", pass)
            personId?.let { putInt("personId", it) }
            personType?.let { putInt("personType", it) }
            apply()
        }
    }

    fun getSessionData(): SessionData {
        val pId = prefs.getInt("personId", 0)
        val pType = prefs.getInt("personType", 0)
        return SessionData(
            server = prefs.getString("server", "") ?: "",
            school = prefs.getString("school", "") ?: "",
            user = prefs.getString("user", "") ?: "",
            pass = prefs.getString("pass", "") ?: "",
            personId = if (pId != 0) pId else null,
            personType = if (pType != 0) pType else null
        )
    }

    fun saveAlarms(alarms: List<Int>) {
        val alarmString = alarms.joinToString(",")
        prefs.edit().putString("alarms", alarmString).apply()
    }

    fun getAlarms(): List<Int> {
        val alarmString = prefs.getString("alarms", "") ?: ""
        if (alarmString.isEmpty()) return emptyList()
        return alarmString.split(",").mapNotNull { it.toIntOrNull() }
    }

    fun isLoggedIn(): Boolean {
        return prefs.getString("user", "")?.isNotEmpty() == true
    }

    fun logout() {
        prefs.edit().clear().apply()
    }
}

data class SessionData(
    val server: String,
    val school: String,
    val user: String,
    val pass: String,
    val personId: Int?,
    val personType: Int?
)
