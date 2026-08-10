package com.example.eboneadminpanel

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase

class EboneAdminApp : Application() {

    private var db: FirebaseDatabase? = null
    private var leaveListener: ChildEventListener? = null
    private var foregroundActivity: Activity? = null
    private val pendingPopups = mutableListOf<Triple<String, String, String>>()
    // name, reason, key

    override fun onCreate() {
        super.onCreate()
        com.google.firebase.FirebaseApp.initializeApp(this)
        db = FirebaseDatabase.getInstance()
        createNotificationChannel()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foregroundActivity = activity
                    val copy = pendingPopups.toList()
                    pendingPopups.clear()
                    }
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (foregroundActivity == activity) foregroundActivity = null
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            if (auth.currentUser != null) startLeaveListener()
            else stopLeaveListener()
        }
    }

    private fun startLeaveListener() {
        if (leaveListener != null) return
        leaveListener = object : ChildEventListener {
            override fun onChildAdded(snap: DataSnapshot, prev: String?) {
                handleSnap(snap)
            }
            override fun onChildChanged(snap: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        db?.getReference("earlyLeaveRequests")
            ?.addChildEventListener(leaveListener!!)
    }

    private fun stopLeaveListener() {
        leaveListener?.let {
            db?.getReference("earlyLeaveRequests")?.removeEventListener(it)
        }
        leaveListener = null
        pendingPopups.clear()
    }

    private fun handleSnap(snap: DataSnapshot) {
        val status = snap.child("status").value?.toString() ?: return
        if (status != "PENDING") return
        val key = snap.key ?: return

        // Get name — try employeeName field, then load from employees node
        val savedName = snap.child("employeeName").value?.toString() ?: ""
        val deviceId = snap.child("employeeId").value?.toString() ?: ""
        val reason = snap.child("reason").value?.toString() ?: ""

        if (savedName.isNotEmpty() && savedName != "Unknown") {
            showOrQueue(savedName, reason, key)
        } else if (deviceId.isNotEmpty()) {
            // Load name from employees node
            db?.getReference("employees")?.child(deviceId)
                ?.child("employeeName")?.get()
                ?.addOnSuccessListener { nameSnap ->
                    val realName = nameSnap.value?.toString() ?: "Employee"
                    showOrQueue(realName, reason, key)
                }
        } else {
            showOrQueue("Employee", reason, key)
        }
    }

        val activity = foregroundActivity
        } else {
        }
    }

    private fun showLeavePopup(name: String, reason: String, key: String) {
        val activity = foregroundActivity ?: run {
            pendingPopups.add(Triple(name, reason, key))
            return
        }
        if (activity.isFinishing) return

        activity.runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Early Leave Request")
                .setMessage("Employee: $name\nReason: $reason")
                .setPositiveButton("Approve") { _, _ ->
                    db?.getReference("earlyLeaveRequests")?.child(key)
                        ?.updateChildren(mapOf(
                            "status" to "APPROVED",
                            "respondedAt" to System.currentTimeMillis()
                        ))
                }
                .setNegativeButton("Reject") { _, _ ->
                    db?.getReference("earlyLeaveRequests")?.child(key)
                        ?.updateChildren(mapOf(
                            "status" to "REJECTED",
                            "respondedAt" to System.currentTimeMillis()
                        ))
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "leave_requests", "Leave Requests",
                NotificationManager.IMPORTANCE_HIGH
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}