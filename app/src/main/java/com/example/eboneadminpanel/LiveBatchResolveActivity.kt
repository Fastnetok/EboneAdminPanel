package com.example.eboneadminpanel

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONObject

class LiveBatchResolveActivity : AppCompatActivity() {

    private lateinit var wvEbone: WebView
    private lateinit var wvZong: WebView
    private lateinit var wvWateen: WebView
    
    private val eboneQueue = mutableListOf<Complaint>()
    private val zongQueue = mutableListOf<Complaint>()
    private val wateenQueue = mutableListOf<Complaint>()

    private var eboneFinished = false
    private var zongFinished = false
    private var wateenFinished = false

    private val fb = FirebaseDatabase.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_batch_resolve)

        wvEbone = findViewById(R.id.wvEbone)
        wvZong = findViewById(R.id.wvZong)
        wvWateen = findViewById(R.id.wvWateen)

        setupWebView(wvEbone)
        setupWebView(wvZong)
        setupWebView(wvWateen)

        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }

        loadQueues()
        startProcessing()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private fun loadQueues() {
        val complaintsJson = intent.getStringExtra("complaints_list") ?: return
        try {
            val arr = org.json.JSONArray(complaintsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val c = Complaint(
                    complaintId = obj.getString("id"),
                    userId = obj.getString("userId"),
                    company = obj.getString("isp")
                )
                when (c.company) {
                    "EBONE" -> eboneQueue.add(c)
                    "ZONG" -> zongQueue.add(c)
                    "WATEEN" -> wateenQueue.add(c)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startProcessing() {
        if (eboneQueue.isNotEmpty()) processEbone(0) else { eboneFinished = true; checkAllFinished() }
        if (zongQueue.isNotEmpty()) processZong(0) else { zongFinished = true; checkAllFinished() }
        if (wateenQueue.isNotEmpty()) processWateen(0) else { wateenFinished = true; checkAllFinished() }
    }

    private fun checkAllFinished() {
        if (eboneFinished && zongFinished && wateenFinished) {
            Toast.makeText(this, "All Monitoring Tasks Completed!", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun processEbone(index: Int) {
        if (index >= eboneQueue.size) {
            eboneFinished = true
            wvEbone.loadUrl("about:blank")
            checkAllFinished()
            return
        }
        val complaint = eboneQueue[index]
        val user = IspPanelSettingsActivity.getDealerUsername(this, "EBONE", "Okara", "Akmal") ?: ""
        val pass = IspPanelSettingsActivity.getDealerPassword(this, "EBONE", "Okara", "Akmal") ?: ""
        
        wvEbone.webViewClient = object : WebViewClient() {
            var loggedIn = false
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                if (url.contains("login")) {
                    view?.evaluateJavascript("(function(){ document.querySelector('input[name=username]').value='$user'; document.querySelector('input[name=password]').value='$pass'; document.querySelector('button[type=submit]').click(); })()", null)
                } else if (url.contains("partner.ebill.pk")) {
                    if (!url.contains("/clients")) {
                        view?.loadUrl("https://partner.ebill.pk/clients")
                    } else {
                        val script = """
                            (function(){
                                var box = document.querySelector('input[aria-controls="example1"]');
                                if(box){
                                    box.value = '${complaint.userId}';
                                    box.dispatchEvent(new Event('input', {bubbles:true}));
                                    box.dispatchEvent(new Event('keyup', {bubbles:true}));
                                    setTimeout(function(){
                                        if(document.body.innerText.indexOf('Online Customers') > -1){
                                            window.location.href = "resolve://success";
                                        } else {
                                            window.location.href = "resolve://next";
                                        }
                                    }, 4000);
                                }
                            })()
                        """.trimIndent()
                        view?.evaluateJavascript(script, null)
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == "resolve://success") {
                    resolveInFirebase(complaint)
                    processEbone(index + 1)
                    return true
                } else if (url == "resolve://next") {
                    processEbone(index + 1)
                    return true
                }
                return false
            }
        }
        wvEbone.loadUrl("https://partner.ebill.pk/logincheck")
    }

    private fun processZong(index: Int) {
        if (index >= zongQueue.size) {
            zongFinished = true
            wvZong.loadUrl("about:blank")
            checkAllFinished()
            return
        }
        val complaint = zongQueue[index]
        val user = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", "Okara") ?: ""
        val pass = IspPanelSettingsActivity.getSavedPassword(this, "ZONG", "Okara") ?: ""

        wvZong.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                if (url.contains("login.php")) {
                    view?.evaluateJavascript("(function(){ document.querySelector('input[name=username]').value='$user'; document.querySelector('input[name=password]').value='$pass'; document.querySelector('button[type=submit]').click(); })()", null)
                } else if (url.contains("zong.com.pk")) {
                    if (!url.contains("radius_online_customers.php")) {
                        view?.loadUrl("https://turbonet.zong.com.pk/radius_online_customers.php")
                    } else {
                        val script = """
                            (function(){
                                var box = document.querySelector('input[aria-controls="onlinecustomers"]');
                                if(box){
                                    box.value = '${complaint.userId}';
                                    box.dispatchEvent(new Event('input', {bubbles:true}));
                                    box.dispatchEvent(new Event('keyup', {bubbles:true}));
                                    setTimeout(function(){
                                        if(document.body.innerText.indexOf('${complaint.userId}') > -1){
                                            window.location.href = "resolve://success";
                                        } else {
                                            window.location.href = "resolve://next";
                                        }
                                    }, 4000);
                                }
                            })()
                        """.trimIndent()
                        view?.evaluateJavascript(script, null)
                    }
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == "resolve://success") {
                    resolveInFirebase(complaint)
                    processZong(index + 1)
                    return true
                } else if (url == "resolve://next") {
                    processZong(index + 1)
                    return true
                }
                return false
            }
        }
        wvZong.loadUrl("https://turbonet.zong.com.pk/login.php")
    }

    private fun processWateen(index: Int) {
        if (index >= wateenQueue.size) {
            wateenFinished = true
            wvWateen.loadUrl("about:blank")
            checkAllFinished()
            return
        }
        val complaint = wateenQueue[index]
        val user = IspPanelSettingsActivity.getSavedUsername(this, "WATEEN", "Okara") ?: ""
        val pass = IspPanelSettingsActivity.getSavedPassword(this, "WATEEN", "Okara") ?: ""

        wvWateen.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                if (url.contains("auth.html")) {
                    view?.evaluateJavascript("(function(){ document.querySelector('input[name=username]').value='$user'; document.querySelector('input[name=password]').value='$pass'; document.querySelector('button[type=submit]').click(); })()", null)
                } else if (url.contains("wateen.com")) {
                    if (!url.contains("/user/user/online")) {
                        view?.loadUrl("https://panel.wateen.com/user/user/online")
                    } else {
                        val script = """
                            (function(){
                                var box = document.querySelector('input[aria-controls="allonlineUsers"]');
                                if(box){
                                    box.value = '${complaint.userId}';
                                    box.dispatchEvent(new Event('input', {bubbles:true}));
                                    box.dispatchEvent(new Event('keyup', {bubbles:true}));
                                    setTimeout(function(){
                                        if(document.body.innerText.indexOf('${complaint.userId}') > -1){
                                            window.location.href = "resolve://success";
                                        } else {
                                            window.location.href = "resolve://next";
                                        }
                                    }, 4000);
                                }
                            })()
                        """.trimIndent()
                        view?.evaluateJavascript(script, null)
                    }
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == "resolve://success") {
                    resolveInFirebase(complaint)
                    processWateen(index + 1)
                    return true
                } else if (url == "resolve://next") {
                    processWateen(index + 1)
                    return true
                }
                return false
            }
        }
        wvWateen.loadUrl("https://panel.wateen.com/auth.html")
    }

    private fun resolveInFirebase(complaint: Complaint) {
        val updates = mapOf(
            "status" to "Resolved",
            "resolvedTime" to System.currentTimeMillis(),
            "resolvedBy" to "System Batch Monitor",
            "is_system_resolved" to true
        )
        fb.getReference("complaints").child(complaint.complaintId).updateChildren(updates)
            .addOnSuccessListener {
                fb.getReference("resolvedComplaints").child(complaint.complaintId).setValue(true)
            }
    }
}
