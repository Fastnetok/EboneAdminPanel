package com.example.eboneadminpanel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class AutoResolveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val fb = FirebaseDatabase.getInstance()
    private val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private val ISP_SESSION_PREFS = "isp_session_cookies"

    private fun getIspSessionCookie(isp: String, zone: String): String {
        return try {
            val masterKey = MasterKey.Builder(applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                applicationContext, ISP_SESSION_PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.getString("${isp}_$zone", "") ?: ""
        } catch (e: Exception) { "" }
    }

    private fun saveIspSessionCookie(isp: String, zone: String, cookie: String) {
        try {
            val masterKey = MasterKey.Builder(applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                applicationContext, ISP_SESSION_PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.edit().putString("${isp}_$zone", cookie).apply()
        } catch (e: Exception) { }
    }

    override suspend fun doWork(): Result {
        val monitorSettings = fb.getReference("officeSettings").child("auto_monitor").get().await()
        val masterEnabled = monitorSettings.child("master_enabled").getValue(Boolean::class.java) ?: false
        if (!masterEnabled) return Result.success()

        val timeoutMinutes = monitorSettings.child("resolve_timeout_minutes").getValue(Int::class.java) ?: 30
        val monitoredEmployees = monitorSettings.child("monitored_employees").children.mapNotNull {
            if (it.getValue(Boolean::class.java) == true) it.key else null
        }.toSet()

        val complaints = fb.getReference("complaints").get().await()
        val attendance = fb.getReference("attendance").get().await()
        val employees = fb.getReference("employees").get().await()

        val employeeNameMap = employees.children.associate { 
            it.child("employeeName").value?.toString() to it.key 
        }

        // Separate queues for parallel/staggered processing
        val eboneQueue = mutableListOf<Pair<Complaint, String>>()
        val zongQueue = mutableListOf<Pair<Complaint, String>>()
        val wateenQueue = mutableListOf<Pair<Complaint, String>>()

        for (compSnap in complaints.children) {
            val complaint = compSnap.getValue(Complaint::class.java) ?: continue
            
            if (complaint.status == "Progress" && complaint.assignedTo.isNotEmpty()) {
                val employeeId = employeeNameMap[complaint.assignedTo] ?: continue
                if (!monitoredEmployees.contains(employeeId)) continue

                val isOnline = attendance.child(employeeId).hasChild(todayKey)
                if (!isOnline) continue

                // Check Time Threshold
                val currentTime = System.currentTimeMillis()
                val diffMinutes = (currentTime - complaint.assignedTime) / (1000 * 60)
                
                // User requirement: Test Now bypasses timer, worker respects it
                val isTestNow = inputData.getBoolean("is_test_now", false)
                if (isTestNow || diffMinutes >= timeoutMinutes) {
                    when (complaint.company) {
                        "EBONE" -> eboneQueue.add(complaint to employeeId)
                        "ZONG" -> zongQueue.add(complaint to employeeId)
                        "WATEEN" -> wateenQueue.add(complaint to employeeId)
                    }
                }
            }
        }

        // Start processing with staggered delays
        Handler(Looper.getMainLooper()).post {
            // EBONE starts immediately
            if (eboneQueue.isNotEmpty()) {
                processIspQueue(eboneQueue, "EBONE")
            } else {
                refreshIspSession("EBONE")
            }

            // ZONG starts after 1 second
            Handler(Looper.getMainLooper()).postDelayed({
                if (zongQueue.isNotEmpty()) {
                    processIspQueue(zongQueue, "ZONG")
                } else {
                    refreshIspSession("ZONG")
                }
            }, 1000)

            // WATEEN starts after 2 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                if (wateenQueue.isNotEmpty()) {
                    processIspQueue(wateenQueue, "WATEEN")
                } else {
                    refreshIspSession("WATEEN")
                }
            }, 2000)
        }

        return Result.success()
    }

    private fun processIspQueue(queue: List<Pair<Complaint, String>>, isp: String, index: Int = 0) {
        if (index >= queue.size) {
            return
        }

        val (complaint, employeeId) = queue[index]
        checkIspOnlineAndResolve(complaint, employeeId) {
            // Process next complaint in THIS ISP's queue
            Handler(Looper.getMainLooper()).postDelayed({
                processIspQueue(queue, isp, index + 1)
            }, 500) // Faster interval between complaints of the same ISP
        }
    }

    private fun refreshIspSession(isp: String) {
        val dummy = Complaint(company = isp, userId = "session_poke")
        checkIspOnlineAndResolve(dummy, "system") {}
    }

    private fun setEmployeeStatus(employeeId: String, checking: Boolean, result: String = "idle") {
        if (employeeId == "system") return
        
        // Before updating, double check if monitoring is still enabled
        Handler(Looper.getMainLooper()).post {
            fb.getReference("officeSettings").child("auto_monitor").get().addOnSuccessListener { monitorSettings ->
                val masterEnabled = monitorSettings.child("master_enabled").getValue(Boolean::class.java) ?: false
                val isMonitored = monitorSettings.child("monitored_employees").child(employeeId).getValue(Boolean::class.java) ?: false
                
                if (masterEnabled && (isMonitored || checking == false)) {
                    val ref = fb.getReference("officeSettings").child("auto_monitor").child("live_status").child(employeeId)
                    val updates = mutableMapOf<String, Any>(
                        "checking" to checking,
                        "result" to result,
                        "timestamp" to System.currentTimeMillis()
                    )
                    ref.updateChildren(updates)
                }
            }
        }
    }

    private fun checkIspOnlineAndResolve(complaint: Complaint, employeeId: String, onFinished: () -> Unit) {
        val isp = complaint.company
        val zone = "Okara"

        if (complaint.userId != "session_poke") {
            setEmployeeStatus(employeeId, true)
        }

        val handler = Handler(Looper.getMainLooper())
        var isFinished = false
        
        val timeoutRunnable = Runnable {
            if (!isFinished) {
                isFinished = true
                if (complaint.userId != "session_poke") {
                    setEmployeeStatus(employeeId, false, "failed")
                }
                onFinished()
            }
        }
        handler.postDelayed(timeoutRunnable, 45000)

        val (username, password) = when (isp) {
            "EBONE" -> Pair(IspPanelSettingsActivity.getDealerUsername(applicationContext, "EBONE", zone, "Akmal"), IspPanelSettingsActivity.getDealerPassword(applicationContext, "EBONE", zone, "Akmal"))
            "WATEEN" -> Pair(IspPanelSettingsActivity.getSavedUsername(applicationContext, "WATEEN", zone), IspPanelSettingsActivity.getSavedPassword(applicationContext, "WATEEN", zone))
            "ZONG" -> Pair(IspPanelSettingsActivity.getSavedUsername(applicationContext, "ZONG", zone), IspPanelSettingsActivity.getSavedPassword(applicationContext, "ZONG", zone))
            else -> Pair(null, null)
        }

        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            isFinished = true
            handler.removeCallbacks(timeoutRunnable)
            onFinished()
            return
        }

        val loginUrl = when (isp) {
            "WATEEN" -> "https://panel.wateen.com/auth.html"
            "ZONG" -> "https://turbonet.zong.com.pk/login.php"
            else -> "https://partner.ebill.pk/logincheck"
        }

        val onlineUrl = when (isp) {
            "WATEEN" -> "https://panel.wateen.com/user/user/online"
            "ZONG" -> "https://turbonet.zong.com.pk/radius_online_customers.php"
            else -> "https://partner.ebill.pk/clients"
        }

        val searchSelector = when (isp) {
            "WATEEN" -> "input[aria-controls=\"allonlineUsers\"]"
            "ZONG" -> "input[aria-controls=\"onlinecustomers\"]"
            else -> "input[aria-controls=\"example1\"]"
        }

        handler.post {
            val webView = WebView(applicationContext)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

            // Restore Cookies
            val savedCookie = getIspSessionCookie(isp, zone)
            if (savedCookie.isNotEmpty()) {
                val domain = when (isp) { "WATEEN" -> "https://panel.wateen.com"; "ZONG" -> "https://turbonet.zong.com.pk"; else -> "https://partner.ebill.pk" }
                savedCookie.split(";").forEach { android.webkit.CookieManager.getInstance().setCookie(domain, it.trim()) }
                android.webkit.CookieManager.getInstance().flush()
            }
            
            webView.webViewClient = object : WebViewClient() {
                var loginAttempted = false
                var searchAttempted = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (isFinished || url == null) return
                    val domain = when (isp) { "WATEEN" -> "https://panel.wateen.com"; "ZONG" -> "https://turbonet.zong.com.pk"; else -> "https://partner.ebill.pk" }
                    val currentCookie = android.webkit.CookieManager.getInstance().getCookie(domain)
                    if (!currentCookie.isNullOrEmpty()) saveIspSessionCookie(isp, zone, currentCookie)

                    if (url.contains("login") || url.contains("auth.html")) {
                        if (!loginAttempted) {
                            loginAttempted = true
                            webView.evaluateJavascript("(function(){ var u=document.querySelector('input[name=username], input[name=email]'); var p=document.querySelector('input[name=password]'); var b=document.querySelector('button[type=submit], input[type=submit], .btn-primary'); if(u && p && b){ u.value='$username'; p.value='$password'; b.click(); } })()", null)
                        }
                    } else if (url.contains("ebill.pk") || url.contains("wateen.com") || url.contains("zong.com.pk")) {
                        if (complaint.userId == "session_poke") { 
                            isFinished = true
                            handler.removeCallbacks(timeoutRunnable)
                            webView.destroy()
                            onFinished()
                            return 
                        }
                        if (url.contains(onlineUrl.substringAfter("://")) || url.endsWith(onlineUrl.substringAfterLast("/"))) {
                            if (!searchAttempted) {
                                searchAttempted = true
                                val successCondition = if (isp == "EBONE") "bodyText.indexOf('${complaint.userId}') > -1 && bodyText.indexOf('Online Customers') > -1" else "bodyText.indexOf('${complaint.userId}') > -1"
                                webView.evaluateJavascript("(function(){ var box=document.querySelector('$searchSelector'); if(box){ box.value='${complaint.userId}'; box.dispatchEvent(new Event('input',{bubbles:true})); box.dispatchEvent(new Event('keyup',{bubbles:true})); setTimeout(function(){ var bodyText=document.body.innerText; if($successCondition){ window.location.href='resolve://success'; } else { window.location.href='resolve://failed'; } }, 3000); } else { window.location.href='$onlineUrl'; } })()", null)
                            }
                        } else { webView.loadUrl(onlineUrl) }
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (isFinished) return false
                    if (url == "resolve://success") {
                        isFinished = true
                        handler.removeCallbacks(timeoutRunnable)
                        resolveComplaintInFirebase(complaint)
                        setEmployeeStatus(employeeId, false, "success")
                        webView.destroy()
                        onFinished()
                        return true
                    } else if (url == "resolve://failed") {
                        isFinished = true
                        handler.removeCallbacks(timeoutRunnable)
                        setEmployeeStatus(employeeId, false, "failed")
                        webView.destroy()
                        onFinished()
                        return true
                    }
                    return false
                }
            }
            webView.loadUrl(loginUrl)
        }
    }

    private fun resolveComplaintInFirebase(complaint: Complaint) {
        val updates = mapOf("status" to "Resolved", "resolvedTime" to System.currentTimeMillis(), "resolvedBy" to "System Auto-Monitor", "is_system_resolved" to true)
        fb.getReference("complaints").child(complaint.complaintId).updateChildren(updates).addOnSuccessListener {
            fb.getReference("resolvedComplaints").child(complaint.complaintId).setValue(true)
        }
    }
}
