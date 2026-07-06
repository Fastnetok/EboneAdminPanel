package com.example.eboneadminpanel.core

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)

    fun saveSession(userId: String, role: String, companyId: String? = null) {
        prefs.edit().apply {
            putString(Constants.KEY_USER_ID, userId)
            putString(Constants.KEY_USER_ROLE, role)
            putString(Constants.KEY_COMPANY_ID, companyId)
            putBoolean(Constants.KEY_IS_LOGGED_IN, true)
            apply()
        }
    }

    fun getUserRole(): String? = prefs.getString(Constants.KEY_USER_ROLE, null)
    fun getUserId(): String? = prefs.getString(Constants.KEY_USER_ID, null)
    fun getCompanyId(): String? = prefs.getString(Constants.KEY_COMPANY_ID, null)
    fun isLoggedIn(): Boolean = prefs.getBoolean(Constants.KEY_IS_LOGGED_IN, false)
    fun isSuperAdmin(): Boolean = getUserRole() == Constants.ROLE_SUPER_ADMIN

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    fun saveActiveCompany(companyId: String) {
        prefs.edit().putString("active_company_id", companyId).apply()
    }

    fun getActiveCompanyId(): String? = prefs.getString("active_company_id", null)

    fun clearActiveCompany() {
        prefs.edit().remove("active_company_id").apply()
    }
}