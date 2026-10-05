package com.example.eboneadminpanel

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class EmployeeComplaintsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: EmployeeComplaintAdapter
    private val complaintList = mutableListOf<Complaint>()

    private val eboneQueue = mutableListOf<Complaint>()
    private val wateenQueue = mutableListOf<Complaint>()
    private val zongQueue = mutableListOf<Complaint>()

    private var eboneChecking = false
    private var wateenChecking = false
    private var zongChecking = false

    private var wvEbone: WebView? = null
    private var wvWateen: WebView? = null
    private var wvZong: WebView? = null

    private val ISP_SESSION_PREFS = "isp_session_cookies"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_employee_complaints)

        val employeeName = intent.getStringExtra("employeeName") ?: ""
        val titleText = findViewById<TextView>(R.id.titleText)
        titleText.text = "$employeeName Complaints"

        recyclerView = findViewById(R.id.recyclerEmployeeComplaints)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = EmployeeComplaintAdapter(complaintList)
        recyclerView.adapter = adapter

        loadComplaints(employeeName)
    }

    private fun loadComplaints(employeeName: String) {
        FirebaseDatabase.getInstance().getReference("complaints")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    complaintList.clear()
                    for (item in snapshot.children) {
                        val complaint = item.getValue(Complaint::class.java) ?: continue
                        if (complaint.assignedTo == employeeName &&
                            !complaint.status.equals("Resolved", ignoreCase = true)
                        ) {
                            complaintList.add(complaint)
                        }
                    }
                    complaintList.sortBy { it.displayOrder }
                    adapter.notifyDataSetChanged()
                    allFinishedBeepPlayed = false
                    startCheckingQueues()
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun getIspSessionCookie(isp: String, zone: String): String {
        return try {
            val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                this, ISP_SESSION_PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.getString("${isp}_$zone", "") ?: ""
        } catch (e: Exception) { "" }
    }

    private fun saveIspSessionCookie(isp: String, zone: String, cookie: String) {
        try {
            val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                this, ISP_SESSION_PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.edit().putString("${isp}_$zone", cookie).apply()
        } catch (e: Exception) { }
    }

    private var allFinishedBeepPlayed = false

    private fun playBeeps(count: Int, intervalMs: Long = 400) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            val handler = Handler(Looper.getMainLooper())
            for (i in 0 until count) {
                handler.postDelayed({
                    try {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                    } catch (_: Exception) {}
                }, (i * intervalMs))
            }
        } catch (_: Exception) {}
    }

    private fun handleStatusResult(complaintId: String, isOnline: Boolean) {
        adapter.updateOnlineStatus(complaintId, isOnline)
        if (isOnline) {
            playBeeps(1) // Green -> 1 beep
        } else {
            playBeeps(2, 550) // Red -> 2 beeps with 550ms gap
        }
        checkAllQueuesFinished()
    }

    private fun checkAllQueuesFinished() {
        if (complaintList.isNotEmpty() && adapter.onlineStatusMap.size >= complaintList.size) {
            if (!allFinishedBeepPlayed) {
                allFinishedBeepPlayed = true
                Handler(Looper.getMainLooper()).postDelayed({
                    playBeeps(3, 300) // All complete -> 3 beeps with 300ms gap
                }, 800)
            }
        }
    }

    private fun startCheckingQueues() {
        val checkedIds = adapter.onlineStatusMap.keys

        // 1. EBONE
        val newEbone = complaintList.filter {
            val company = it.company.trim().uppercase(Locale.US)
            (company == "EBONE" || company == "EBILL" || company == "EBONE (EBILL.PK)") && !checkedIds.contains(it.complaintId)
        }
        for (c in newEbone) {
            if (!eboneQueue.any { it.complaintId == c.complaintId }) {
                eboneQueue.add(c)
            }
        }
        if (!eboneChecking && eboneQueue.isNotEmpty()) {
            eboneChecking = true
            processNextEbone()
        }

        // 2. WATEEN
        val newWateen = complaintList.filter {
            val company = it.company.trim().uppercase(Locale.US)
            (company == "WATEEN" || company == "WATEEN.COM") && !checkedIds.contains(it.complaintId)
        }
        for (c in newWateen) {
            if (!wateenQueue.any { it.complaintId == c.complaintId }) {
                wateenQueue.add(c)
            }
        }
        if (!wateenChecking && wateenQueue.isNotEmpty()) {
            wateenChecking = true
            processNextWateen()
        }

        // 3. ZONG
        val newZong = complaintList.filter {
            val company = it.company.trim().uppercase(Locale.US)
            (company == "ZONG" || company == "TURBONET.ZONG.COM.PK") && !checkedIds.contains(it.complaintId)
        }
        for (c in newZong) {
            if (!zongQueue.any { it.complaintId == c.complaintId }) {
                zongQueue.add(c)
            }
        }
        if (!zongChecking && zongQueue.isNotEmpty()) {
            zongChecking = true
            processNextZong()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateEboneWebView(): WebView {
        if (wvEbone == null) {
            wvEbone = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.blockNetworkImage = true
                settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
        }
        return wvEbone!!
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWateenWebView(): WebView {
        if (wvWateen == null) {
            wvWateen = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
        }
        return wvWateen!!
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateZongWebView(): WebView {
        if (wvZong == null) {
            wvZong = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.databaseEnabled = true
                settings.databaseEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
        }
        return wvZong!!
    }

    private fun processNextEbone() {
        if (eboneQueue.isEmpty()) {
            eboneChecking = false
            return
        }
        val complaint = eboneQueue.removeAt(0)

        if (adapter.onlineStatusMap.containsKey(complaint.complaintId)) {
            processNextEbone()
            return
        }

        val zone = "Okara"
        val user = IspPanelSettingsActivity.getDealerUsername(this, "EBONE", zone, "Akmal") ?: ""
        val pass = IspPanelSettingsActivity.getDealerPassword(this, "EBONE", zone, "Akmal") ?: ""

        if (user.isEmpty() || pass.isEmpty()) {
            adapter.updateOnlineStatus(complaint.complaintId, false)
            processNextEbone()
            return
        }

        val loginUrl = "https://partner.ebill.pk/logincheck"
        val onlineUrl = "https://partner.ebill.pk/online"
        val searchSelector = "input[aria-controls=\"example1\"]"

        val webView = getOrCreateEboneWebView()

        val savedCookie = getIspSessionCookie("EBONE", zone)
        if (savedCookie.isNotEmpty()) {
            savedCookie.split(";").forEach { CookieManager.getInstance().setCookie("https://partner.ebill.pk", it.trim()) }
            CookieManager.getInstance().flush()
        }

        var isFinished = false
        val handler = Handler(Looper.getMainLooper())

        val timeoutRunnable = Runnable {
            if (!isFinished) {
                isFinished = true
                adapter.updateOnlineStatus(complaint.complaintId, false)
                processNextEbone()
            }
        }
        handler.postDelayed(timeoutRunnable, 15000)

        webView.webViewClient = object : WebViewClient() {
            var loginAttempted = false
            var searchAttempted = false

            override fun onPageFinished(view: WebView?, url: String?) {
                if (isFinished || url == null) return

                val currentCookie = CookieManager.getInstance().getCookie("https://partner.ebill.pk")
                if (!currentCookie.isNullOrEmpty()) saveIspSessionCookie("EBONE", zone, currentCookie)

                if (url.contains("login")) {
                    if (!loginAttempted) {
                        loginAttempted = true
                        webView.evaluateJavascript("(function(){ document.querySelector('input[name=username]').value='$user'; document.querySelector('input[name=password]').value='$pass'; document.querySelector('button[type=submit]').click(); })()", null)
                    }
                } else if (url.contains("partner.ebill.pk")) {
                    if (!url.contains("/online")) {
                        webView.loadUrl(onlineUrl)
                    } else {
                        if (!searchAttempted) {
                            searchAttempted = true
                            webView.evaluateJavascript("(function(){ var targetUser = '${complaint.userId}'.toLowerCase().trim(); var box = document.querySelector('$searchSelector'); if(box){ box.value = ''; box.focus(); box.value = targetUser; box.dispatchEvent(new Event('input', {bubbles:true})); box.dispatchEvent(new Event('keyup', {bubbles:true})); setTimeout(function(){ var table = document.getElementById('example1'); var result = 'failed'; if(table){ var emptyRow = table.querySelector('.dataTables_empty'); if(!emptyRow){ var rows = table.querySelectorAll('tbody tr'); for(var i=0; i<rows.length; i++){ var rowText = rows[i].innerText.toLowerCase(); if(rowText.indexOf(targetUser) > -1){ result = 'success'; break; } } } } window.location.href = 'resolve://' + result; }, 800); } else { window.location.href = '$onlineUrl'; } })();", null)
                        }
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (isFinished) return false
                if (url == "resolve://success") {
                    isFinished = true
                    handler.removeCallbacks(timeoutRunnable)
                    handleStatusResult(complaint.complaintId, true)
                    processNextEbone()
                    return true
                } else if (url == "resolve://failed") {
                    isFinished = true
                    handler.removeCallbacks(timeoutRunnable)
                    handleStatusResult(complaint.complaintId, false)
                    processNextEbone()
                    return true
                }
                return false
            }
        }

        webView.loadUrl(loginUrl)
    }

    private fun processNextWateen() {
        if (wateenQueue.isEmpty()) {
            wateenChecking = false
            return
        }
        val complaint = wateenQueue.removeAt(0)

        if (adapter.onlineStatusMap.containsKey(complaint.complaintId)) {
            processNextWateen()
            return
        }

        val zone = "Okara"
        val user = IspPanelSettingsActivity.getSavedUsername(this, "WATEEN", zone) ?: ""
        val pass = IspPanelSettingsActivity.getSavedPassword(this, "WATEEN", zone) ?: ""

        if (user.isEmpty() || pass.isEmpty()) {
            adapter.updateOnlineStatus(complaint.complaintId, false)
            processNextWateen()
            return
        }

        val loginUrl = "https://panel.wateen.com/auth.html"
        val onlineUrl = "https://panel.wateen.com/user/user/online"
        val searchSelector = "input[aria-controls=\"allonlineUsers\"]"

        val webView = getOrCreateWateenWebView()

        val savedCookie = getIspSessionCookie("WATEEN", zone)
        if (savedCookie.isNotEmpty()) {
            savedCookie.split(";").forEach { CookieManager.getInstance().setCookie("https://panel.wateen.com", it.trim()) }
            CookieManager.getInstance().flush()
        }

        var isFinished = false
        val handler = Handler(Looper.getMainLooper())

        val timeoutRunnable = Runnable {
            if (!isFinished) {
                isFinished = true
                adapter.updateOnlineStatus(complaint.complaintId, false)
                processNextWateen()
            }
        }
        handler.postDelayed(timeoutRunnable, 25000)

        webView.webViewClient = object : WebViewClient() {
            var loginAttempted = false
            var searchAttempted = false

            override fun onPageFinished(view: WebView?, url: String?) {
                if (isFinished || url == null) return

                val currentCookie = CookieManager.getInstance().getCookie("https://panel.wateen.com")
                if (!currentCookie.isNullOrEmpty()) saveIspSessionCookie("WATEEN", zone, currentCookie)

                if (url.contains("auth.html") || url.contains("login")) {
                    if (!loginAttempted) {
                        loginAttempted = true
                        webView.evaluateJavascript("(function(){ var u=document.querySelector('input[name=username], input[name=email]'); var p=document.querySelector('input[name=password]'); var b=document.querySelector('button[type=submit], input[type=submit], .btn-primary'); if(u && p && b){ u.value='$user'; p.value='$pass'; b.click(); } })()", null)
                    }
                } else if (url.contains("wateen.com")) {
                    if (!url.contains("/user/user/online")) {
                        webView.loadUrl(onlineUrl)
                    } else {
                        if (!searchAttempted) {
                            searchAttempted = true
                            webView.evaluateJavascript("(function(){ var box = document.querySelector('$searchSelector'); if(box){ box.value = '${complaint.userId}'; box.dispatchEvent(new Event('input', {bubbles:true})); box.dispatchEvent(new Event('keyup', {bubbles:true})); setTimeout(function(){ if(document.body.innerText.indexOf('${complaint.userId}') > -1){ window.location.href = \"resolve://success\"; } else { window.location.href = \"resolve://failed\"; } }, 3000); } else { window.location.href = \"resolve://failed\"; } })()", null)
                        }
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (isFinished) return false
                if (url == "resolve://success") {
                    isFinished = true
                    handler.removeCallbacks(timeoutRunnable)
                    handleStatusResult(complaint.complaintId, true)
                    processNextWateen()
                    return true
                } else if (url == "resolve://failed") {
                    isFinished = true
                    handler.removeCallbacks(timeoutRunnable)
                    handleStatusResult(complaint.complaintId, false)
                    processNextWateen()
                    return true
                }
                return false
            }
        }

        webView.loadUrl(loginUrl)
    }

    private fun processNextZong() {
        if (zongQueue.isEmpty()) {
            zongChecking = false
            return
        }
        val complaint = zongQueue.removeAt(0)

        if (adapter.onlineStatusMap.containsKey(complaint.complaintId)) {
            processNextZong()
            return
        }

        val zone = "Okara"
        val user = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", zone) ?: ""
        val pass = IspPanelSettingsActivity.getSavedPassword(this, "ZONG", zone) ?: ""

        if (user.isEmpty() || pass.isEmpty()) {
            adapter.updateOnlineStatus(complaint.complaintId, false)
            processNextZong()
            return
        }

        val loginUrl = "https://turbonet.zong.com.pk/login.php"
        val onlineUrl = "https://turbonet.zong.com.pk/radius_online_customers.php"

        val webView = getOrCreateZongWebView()

        val savedCookie = getIspSessionCookie("ZONG", zone)
        if (savedCookie.isNotEmpty()) {
            savedCookie.split(";").forEach { CookieManager.getInstance().setCookie("https://turbonet.zong.com.pk", it.trim()) }
            CookieManager.getInstance().flush()
        }

        var isFinished = false
        val handler = Handler(Looper.getMainLooper())

        val timeoutRunnable = Runnable {
            if (!isFinished) {
                isFinished = true
                adapter.updateOnlineStatus(complaint.complaintId, false)
                processNextZong()
            }
        }
        handler.postDelayed(timeoutRunnable, 25000)

        webView.webViewClient = object : WebViewClient() {
            var loginAttempted = false
            var searchAttempted = false

            override fun onPageFinished(view: WebView?, url: String?) {
                if (isFinished || url == null) return

                val currentCookie = CookieManager.getInstance().getCookie("https://turbonet.zong.com.pk")
                if (!currentCookie.isNullOrEmpty()) saveIspSessionCookie("ZONG", zone, currentCookie)

                if (url.contains("login.php")) {
                    if (!loginAttempted) {
                        loginAttempted = true
                        webView.evaluateJavascript("(function(){ var u=document.querySelector('input[name=username], input[name=email]'); var p=document.querySelector('input[name=password]'); var b=document.querySelector('button[type=submit], input[type=submit], .btn-primary'); if(u && p && b){ u.value='$user'; p.value='$pass'; b.click(); } })()", null)
                    }
                } else if (url.contains("zong.com.pk")) {
                    if (!url.contains("/radius_online_customers.php")) {
                        webView.loadUrl(onlineUrl)
                    } else {
                        if (!searchAttempted) {
                            searchAttempted = true
                            webView.evaluateJavascript("(function(){ var targetUser = '${complaint.userId}'.toLowerCase().trim(); var box = document.querySelector('input.form-control.form-control-sm, input[aria-controls=\"onlinecustomers\"]'); if(box){ box.value = ''; box.focus(); box.value = targetUser; box.dispatchEvent(new Event('input', {bubbles:true})); box.dispatchEvent(new Event('keyup', {bubbles:true})); setTimeout(function(){ var table = document.querySelector('table'); var result = 'failed'; if(table){ var emptyRow = table.querySelector('.dataTables_empty'); if(!emptyRow){ var rows = table.querySelectorAll('tbody tr'); for(var i=0; i<rows.length; i++){ var rowText = rows[i].innerText.toLowerCase(); if(rowText.indexOf(targetUser) > -1){ result = 'success'; break; } } } } window.location.href = 'resolve://' + result; }, 3000); } else { window.location.href = 'resolve://failed'; } })();", null)
                        }
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (isFinished) return false
                if (url == "resolve://success") {
                    isFinished = true
                    handler.removeCallbacks(timeoutRunnable)
                    handleStatusResult(complaint.complaintId, true)
                    processNextZong()
                    return true
                } else if (url == "resolve://failed") {
                    isFinished = true
                    handler.removeCallbacks(timeoutRunnable)
                    handleStatusResult(complaint.complaintId, false)
                    processNextZong()
                    return true
                }
                return false
            }
        }

        webView.loadUrl(loginUrl)
    }

    override fun onDestroy() {
        super.onDestroy()
        wvEbone?.destroy()
        wvWateen?.destroy()
        wvZong?.destroy()
    }
}
