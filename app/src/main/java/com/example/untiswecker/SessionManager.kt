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

    fun saveSession(server: String, school: String, user: String, pass: String) {
        prefs.edit().apply {
            putString("server", server)
            putString("school", school)
            putString("user", user)
            putString("pass", pass)
            apply()
        }
    }

    fun isLoggedIn(): Boolean {
        return prefs.getString("user", null) != null
    }

    fun getCredentials(): Map<String, String?> {
        return mapOf(
            "server" to prefs.getString("server", ""),
            "school" to prefs.getString("school", ""),
            "user" to prefs.getString("user", ""),
            "pass" to prefs.getString("pass", "")
        )
    }

    fun logout() {
        prefs.edit().clear().apply()
    }
}
