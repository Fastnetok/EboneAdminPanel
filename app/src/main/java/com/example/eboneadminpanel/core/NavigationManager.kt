package com.example.eboneadminpanel.core

import android.app.Activity
import android.content.Intent
import com.example.eboneadminpanel.MainActivity
import com.example.eboneadminpanel.superadmin.dashboard.SuperDashboardActivity

object NavigationManager {

    fun goToCorrectDashboard(activity: Activity, sessionManager: SessionManager) {
        val intent = if (sessionManager.isSuperAdmin()) {
            Intent(activity, SuperDashboardActivity::class.java)
        } else {
            Intent(activity, MainActivity::class.java)
        }
        activity.startActivity(intent)
        activity.finish()
    }
}