package com.dogu.livekit.pref

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("LiveKit", Context.MODE_PRIVATE)

    fun isLoggedIn(): Boolean = prefs.getBoolean("is_logged_in", false)

    fun setLoggedIn(loggedIn: Boolean, identity: String? = null) {
        prefs.edit().apply {
            putBoolean("is_logged_in", loggedIn)
            if (identity != null) {
                putString("current_identity", identity)
            }
            apply()
        }
    }

    fun getCurrentIdentity(): String? = prefs.getString("current_identity", null)

    fun saveRememberMe(identity: String, password: String, remember: Boolean) {
        prefs.edit().apply {
            if (remember) {
                putString("remembered_identity", identity)
                putString("remembered_password", password)
            } else {
                remove("remembered_identity")
                remove("remembered_password")
            }
            apply()
        }
    }

    fun getRememberedIdentity(): String = prefs.getString("remembered_identity", "") ?: ""
    fun getRememberedPassword(): String = prefs.getString("remembered_password", "") ?: ""

    fun logout() {
        prefs.edit().apply {
            putBoolean("is_logged_in", false)
            apply()
        }
    }

    // Tema tercihi: true = Koyu Tema (varsayılan, uygulamanın eski/şu anki hali), false = Açık Tema
    fun isDarkTheme(): Boolean = prefs.getBoolean("is_dark_theme", true)

    fun setDarkTheme(isDark: Boolean) {
        prefs.edit().apply {
            putBoolean("is_dark_theme", isDark)
            apply()
        }
    }
}
