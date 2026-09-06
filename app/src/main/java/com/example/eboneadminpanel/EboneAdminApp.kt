package com.example.eboneadminpanel

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore

class EboneAdminApp : Application() {

    private var db: FirebaseDatabase? = null
    private var leaveListener: ChildEventListener? = null
    private var foregroundActivity: Activity? = null
    private val pendingPopups = mutableListOf<Triple<String, String, String>>()
    // name, reason, key

    // ============================================================
    // NEW: foreground-only automatic relief-expiry disable checker.
    //
    // WHY THIS EXISTS:
    // GraceDeadlineWorker (WorkManager) already checks for expired
    // relief customers in the background, but Android enforces a hard
    // minimum of ~15 minutes between periodic background runs, AND a
    // background process is not allowed to silently start a new
    // Activity (the real ISP panel login only works inside a visible
    // WebView) — so that path can only ever raise a notification and
    // wait for a manual tap.
    //
    // The admin explicitly asked: whenever the app itself is open and
    // visible (any screen — not a specific one), expired relief
    // customers should be disabled completely automatically, with no
    // tap required. Starting a new Activity from the foreground IS
    // allowed by Android, so this is done here with a lightweight
    // in-process timer that only ever acts while foregroundActivity is
    // non-null (i.e. the app is actually on screen right now).
    //
    // This does NOT replace GraceDeadlineWorker — that still exists
    // exactly as before for whenever the app is closed/backgrounded.
    // This is a second, additive layer that only runs while the app is
    // actually open, giving the "100% automatic while I'm using the
    // app" behaviour that was asked for.
    // ============================================================

    private val foregroundCheckHandler = Handler(Looper.getMainLooper())

    // Only one auto-disable WebView is ever launched at a time. This
    // timestamp prevents a second one from being launched again within
    // the cooldown window, even if two poll cycles somehow overlap.
    private var lastAutoDisableLaunchAt = 0L

    companion object {
        private const val AUTO_DISABLE_POLL_INTERVAL_MS = 45_000L
        private const val AUTO_DISABLE_COOLDOWN_MS = 20_000L
    }

    private val foregroundCheckRunnable = object : Runnable {
        override fun run() {
            if (foregroundActivity != null) {
                checkAndAutoDisableExpiredRelief()
            }
            // Always reschedule — this is a no-op cost when the app is
            // backgrounded (foregroundActivity == null), since the body
            // above is skipped entirely in that case.
            foregroundCheckHandler.postDelayed(this, AUTO_DISABLE_POLL_INTERVAL_MS)
        }
    }

    /**
     * Looks for exactly ONE relief customer whose graceDeadline has
     * already passed and whose reliefStatus is still "ACTIVE", and — if
     * found — launches the exact same SUSPEND flow used everywhere else
     * in the app (WebViewLoginActivity captures the real password, saves
     * it, then changes it), with NO notification and NO tap required,
     * since we are already running from a visible, foreground context.
     *
     * Only one customer is processed per poll (limit(1)) so multiple
     * simultaneous expiries are handled one at a time, roughly 45
     * seconds apart, rather than opening several WebViews at once.
     */
    private fun checkAndAutoDisableExpiredRelief() {
        val now = System.currentTimeMillis()
        if (now - lastAutoDisableLaunchAt < AUTO_DISABLE_COOLDOWN_MS) return

        FirebaseFirestore.getInstance()
            .collection("customers")
            .whereEqualTo("activationStatus", "ACTIVE")
            .whereEqualTo("reliefStatus", "ACTIVE")
            .whereLessThan("graceDeadline", now)
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                val doc = snapshot.documents.firstOrNull() ?: return@addOnSuccessListener
                val customerId = doc.getString("customerId") ?: doc.id
                val isp = (doc.getString("ispProvider") ?: "EBONE").uppercase()

                lastAutoDisableLaunchAt = System.currentTimeMillis()

                val intent = Intent(this, WebViewLoginActivity::class.java).apply {
                    putExtra("selected_isp", isp)
                    putExtra("auto_activate_customer_id", customerId)
                    putExtra("manual_action", "SUSPEND")
                    putExtra("target_zone", "Okara")
                    if (isp == "EBONE") {
                        putExtra("use_dealer_account", true)
                        putExtra("dealer_account_name", "Akmal")
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                android.util.Log.d(
                    "EboneAdminApp",
                    "Foreground auto-disable: launching SUSPEND for $customerId ($isp) — grace deadline already passed."
                )

                startActivity(intent)
            }
            .addOnFailureListener { e ->
                android.util.Log.e(
                    "EboneAdminApp",
                    "Foreground auto-disable check failed: ${e.message}"
                )
            }
    }

    override fun onCreate() {
        super.onCreate()
        com.google.firebase.FirebaseApp.initializeApp(this)
        db = FirebaseDatabase.getInstance()
        createNotificationChannel()

        // NEW: start the foreground auto-disable poll loop. It self
        // -reschedules forever; the body only does real work while
        // foregroundActivity is non-null (app actually on screen).
        foregroundCheckHandler.post(foregroundCheckRunnable)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val wasBackground = (foregroundActivity == null)
                foregroundActivity = activity

                // FIX: previously, re-opening the app after it had been
                // closed/backgrounded only picked up expired relief
                // customers on the NEXT 45-second poll — meaning a
                // customer whose grace deadline passed while the app was
                // closed stayed "Active" until that poll happened to
                // fire. Now, the moment the app genuinely comes back to
                // the foreground (was backgrounded, now isn't), an
                // immediate check runs right away instead of waiting.
                if (wasBackground) {
                    checkAndAutoDisableExpiredRelief()
                }

                val name = activity.javaClass.simpleName
                // Only show popup on main working screens — not login/unlock/pin
                val isMainScreen = name == "MenuActivity" || name == "MainActivity" ||
                        name == "BiometricAttendanceActivity" ||
                        name == "OfficeTimmingsActivity"
                if (pendingPopups.isNotEmpty() && isMainScreen) {
                    val copy = pendingPopups.toList()
                    pendingPopups.clear()
                    copy.forEach { (eName, reason, key) ->
                        showLeavePopup(eName, reason, key)
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

    private fun showOrQueue(empName: String, reason: String, key: String) {
        val activity = foregroundActivity
        val screenName = activity?.javaClass?.simpleName ?: ""
        // Show only on main working screens
        val isMainScreen = screenName == "MenuActivity" || screenName == "MainActivity" ||
                screenName == "BiometricAttendanceActivity" ||
                screenName == "OfficeTimmingsActivity"
        if (activity != null && !activity.isFinishing && isMainScreen) {
            showLeavePopup(empName, reason, key)
        } else {
            // Queue — will show when admin reaches main screen
            if (pendingPopups.none { it.third == key }) {
                pendingPopups.add(Triple(empName, reason, key))
            }
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