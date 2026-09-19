package com.example.eboneadminpanel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject

/**
 * Isolated WebView flow for EBONE.
 *
 * Handles:
 *  - Ebone login (Franchise & Dealer)
 *  - Ebone customer activation / profile fetching
 *  - Ebone dealer top-up (Add Balance)
 *  - Ebone franchise balance check
 *  - Ebone password SUSPEND/ENABLE actions
 */
class EboneWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var loginDone = false
    private var loginAttemptInProgress = false
    private var selectedIsp = "EBONE"
    private var autoActivateCustomerId: String? = null
    private var eboneSubmitClicked = false
    private var eboneSuspendCaptureDone = false
    private var transactionId: String? = null
    private var manualAction: String? = null
    private var eboneOriginalPassword: String? = null
    private var dealerEboneId: String? = null
    private var topupAmount: String? = null
    private var eboneTopupSubmitAttempted = false
    private var eboneTopupSubmitAttempt = 0
    private var eboneTopupVerificationAttempt = 0
    private var eboneTopupSuccessConfirmed = false
    private var eboneBalanceCheckAttempted = false
    private var dealerInternalId: String? = null
    private var dealerDisplayName: String? = null
    private var targetZone: String = "Okara"
    private var sourceTransactionId: String? = null
    private var debugTapInspectorEnabled = false
    private var forceAccountName: String? = null
    private var forcedDealerUsername: String? = null
    private var forcedDealerPassword: String? = null
    private var customerUrlOverride: String? = null
    private var complaintIdToResolve: String? = null
    private var eboneDetailsFetchDone = false

    companion object {
        private const val EBONE_SUSPEND_PASSWORD = "Tetra9"
        private const val EBONE_ENABLE_PASSWORD = "1001"
        private const val PREFS_NAME = "ebill_accounts"
        private const val KEY_ACCOUNTS = "accounts_json"
        private const val KEY_ACTIVE = "active_account"
        private const val ISP_SESSION_PREFS = "isp_session_cookies"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_login)

        window.statusBarColor = Color.parseColor("#1B5E20")
        window.decorView.systemUiVisibility = 0

        autoActivateCustomerId = intent.getStringExtra("auto_activate_customer_id")
        transactionId = intent.getStringExtra("transaction_id")
        manualAction = intent.getStringExtra("manual_action")
        dealerEboneId = intent.getStringExtra("dealer_ebone_id")
        topupAmount = intent.getStringExtra("topup_amount")
        dealerInternalId = intent.getStringExtra("dealer_internal_id")
        dealerDisplayName = intent.getStringExtra("dealer_display_name")
        sourceTransactionId = intent.getStringExtra("source_transaction_id")
        targetZone = intent.getStringExtra("target_zone")?.ifBlank { null } ?: "Okara"
        complaintIdToResolve = intent.getStringExtra("complaint_id_to_resolve")
        forceAccountName = intent.getStringExtra("dealer_account_name")
        customerUrlOverride = intent.getStringExtra("customer_url")?.trim()?.takeIf { it.isNotBlank() }
        debugTapInspectorEnabled = intent.getBooleanExtra("debug_tap_inspector", false)

        if (manualAction == "DEALER_TOPUP" && forceAccountName.isNullOrBlank()) {
            forceAccountName = dealerDisplayName?.trim()?.takeIf { it.isNotBlank() }
        }

        webView = findViewById(R.id.loginWebView)

        findViewById<Button>(R.id.accountSwitchButton).setOnClickListener {
            // Placeholder: Ebone specific account switching if needed
            Toast.makeText(this, "Switching Ebone accounts not implemented here.", Toast.LENGTH_SHORT).show()
        }

        configureWebView()
        loadInitialPage()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.saveFormData = true
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 6.0) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

        if (debugTapInspectorEnabled) {
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onTap(info: String) {
                    runOnUiThread { Log.d("TapInspector", info) }
                }
            }, "TapInspector")
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                CookieManager.getInstance().flush()
                
                if (debugTapInspectorEnabled) {
                    webView.evaluateJavascript(
                        "(function(){" +
                                "  if (window.__tapInspectorInstalled) return;" +
                                "  window.__tapInspectorInstalled = true;" +
                                "  document.addEventListener('click', function(e){" +
                                "    var el = e.target;" +
                                "    var info = 'TAG: ' + el.tagName +" +
                                "      ' | id=' + (el.id || '-') +" +
                                "      ' | name=' + (el.getAttribute('name') || '-') +" +
                                "      ' | class=' + (el.className || '-') +" +
                                "      ' | text=' + (el.innerText ? el.innerText.substring(0,40) : '-');" +
                                "    if (window.TapInspector) window.TapInspector.onTap(info);" +
                                "  }, true);" +
                                "})()", null
                    )
                }

                handlePageLoaded(url)

                if (!loginDone && !loginAttemptInProgress) {
                    webView.postDelayed({
                        webView.evaluateJavascript(
                            "(function(){" +
                                    "  var p = document.querySelector('input[type=password]');" +
                                    "  return p ? 'has_password_field' : 'no';" +
                                    "})()"
                        ) { hasPasswordField ->
                            if (hasPasswordField.trim().removeSurrounding("\"") == "has_password_field" && !loginDone && !loginAttemptInProgress) {
                                tryAutoLogin()
                            }
                        }
                    }, 100)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.contains("/clients/client/") && autoActivateCustomerId == null) {
                    webView.postDelayed({ fetchEboneCustomerDetails() }, 1500)
                } else if (url.contains("/clients/clientStats/")) {
                    webView.postDelayed({ clickEboneSubmitButton() }, 1500)
                }
                return false
            }
        }
    }

    private fun handlePageLoaded(url: String) {
        when {
            url.contains("logincheck") || url.contains("login") -> {
                loginDone = false; loginAttemptInProgress = false; tryAutoLogin()
            }
            url.contains("/clients/clientChange/") && manualAction != null -> {
                prepareEbonePasswordAction()
            }
            url.contains("/payments/addbalance/") && manualAction == "DEALER_TOPUP" -> {
                if (eboneTopupSubmitAttempted && !eboneTopupSuccessConfirmed) {
                    verifyEboneDealerTopupResult(topupAmount ?: "", eboneTopupVerificationAttempt)
                } else if (!eboneTopupSubmitAttempted && !eboneTopupSuccessConfirmed) {
                    fillDealerTopupAmountAndSubmit()
                }
            }
            url.contains("/clients/clientStats/") -> {
                clickEboneSubmitButton()
            }
            url.contains("/clients/client/") && autoActivateCustomerId != null -> {
                val urlCustomerId = url.substringAfterLast("/clients/client/").substringBefore("?").trim().trimEnd('/')
                if (urlCustomerId != autoActivateCustomerId) {
                    Log.e("EboneWebView", "Customer ID mismatch — expected $autoActivateCustomerId, got $urlCustomerId.")
                } else if (manualAction == "SUSPEND") {
                    if (!eboneSuspendCaptureDone) {
                        eboneSuspendCaptureDone = true
                        captureEbonePasswordFromProfileThenChange(urlCustomerId)
                    }
                } else if (manualAction == "RELIEF_VIEW") {
                    webView.postDelayed({
                        setResult(RESULT_OK, Intent().apply { putExtra("activation_success", true) })
                        finish()
                    }, 1500)
                } else if (eboneSubmitClicked) {
                    fetchEboneExpiryAndFinish()
                } else {
                    clickEboneActiveLink()
                }
            }
            url.contains("/clients/client/") -> {
                fetchEboneCustomerDetails()
            }
            url.contains("partner.ebill.pk") &&
                    !url.contains("/login") &&
                    !url.contains("/clients/client/") && !url.contains("/clients/clientStats/") &&
                    !url.contains("/clients/clientChange/") &&
                    !url.contains("/payments/addbalance/") -> {
                loginDone = true
                saveCookieForCurrentAccount("https://partner.ebill.pk")
                cacheIspSessionCookieIfApplicable("EBONE", "https://partner.ebill.pk")
                webView.evaluateJavascript(
                    "document.querySelectorAll('.modal,.modal-backdrop,.popup').forEach(function(el){el.style.display='none';});document.body.classList.remove('modal-open');", null
                )
                if (manualAction == "RESOLVE_MONITOR" && autoActivateCustomerId != null) {
                    if (!url.contains("/clients")) {
                        webView.loadUrl("https://partner.ebill.pk/clients")
                    } else {
                        performVisualAutoResolve("input[aria-controls=\"example1\"]", "Online Customers")
                    }
                } else if (manualAction == "DEALER_TOPUP" && !dealerEboneId.isNullOrBlank()) {
                    webView.postDelayed({
                        webView.loadUrl("https://partner.ebill.pk/payments/addbalance/${dealerEboneId}")
                    }, 300)
                } else if (manualAction == "CHECK_BALANCE") {
                    if (!eboneBalanceCheckAttempted) {
                        eboneBalanceCheckAttempted = true
                        webView.postDelayed({ readEboneFranchiseBalance() }, 1000)
                    }
                } else if (!url.contains("/clients")) {
                    webView.postDelayed({ webView.loadUrl("https://partner.ebill.pk/clients") }, 300)
                } else if (autoActivateCustomerId != null) {
                    webView.postDelayed({ searchEboneCustomer(autoActivateCustomerId!!) }, 300)
                }
            }
        }
    }

    private fun loadInitialPage() {
        val ispUsername = IspPanelSettingsActivity.getSavedUsername(this, "EBONE", targetZone)
        if (!ispUsername.isNullOrEmpty()) {
            val savedCookie = securePrefs(ISP_SESSION_PREFS).getString("EBONE_$targetZone", "") ?: ""
            if (savedCookie.isNotEmpty()) {
                savedCookie.split(";").forEach {
                    CookieManager.getInstance().setCookie("https://partner.ebill.pk", it.trim())
                }
                CookieManager.getInstance().flush()
                webView.loadUrl("https://partner.ebill.pk/clients")
            } else {
                webView.loadUrl("https://partner.ebill.pk/logincheck")
            }
            return
        }

        val rawAccounts = securePrefs(PREFS_NAME).getString(KEY_ACCOUNTS, "") ?: ""
        val accounts = if (rawAccounts.isEmpty()) JSONObject() else JSONObject(rawAccounts)
        val active = securePrefs(PREFS_NAME).getString(KEY_ACTIVE, "") ?: ""
        
        if (active.isNotEmpty() && accounts.has(active)) {
            val acc = accounts.getJSONObject(active)
            val cookie = acc.optString("cookie", "")
            if (cookie.isNotEmpty()) {
                CookieManager.getInstance().setCookie("https://partner.ebill.pk", cookie)
                CookieManager.getInstance().flush()
                webView.loadUrl("https://partner.ebill.pk/clients")
            } else {
                webView.loadUrl("https://partner.ebill.pk/logincheck")
            }
        } else {
            webView.loadUrl("https://partner.ebill.pk/logincheck")
        }
    }

    private fun tryAutoLogin() {
        if (loginAttemptInProgress) return
        loginAttemptInProgress = true

        if (!forceAccountName.isNullOrBlank()) {
            val dealerUsername = IspPanelSettingsActivity.getDealerUsername(this, "EBONE", targetZone, forceAccountName!!)
            val dealerPassword = IspPanelSettingsActivity.getDealerPassword(this, "EBONE", targetZone, forceAccountName!!)
            if (!dealerUsername.isNullOrEmpty() && !dealerPassword.isNullOrEmpty()) {
                doLoginWith(dealerUsername, dealerPassword)
                return
            }
        }

        val ispUsername = IspPanelSettingsActivity.getSavedUsername(this, "EBONE", targetZone)
        val ispPassword = IspPanelSettingsActivity.getSavedPassword(this, "EBONE", targetZone)
        if (!ispUsername.isNullOrEmpty() && !ispPassword.isNullOrEmpty()) {
            doLoginWith(ispUsername, ispPassword)
            return
        }
        
        val rawAccounts = securePrefs(PREFS_NAME).getString(KEY_ACCOUNTS, "") ?: ""
        val accounts = if (rawAccounts.isEmpty()) JSONObject() else JSONObject(rawAccounts)
        val active = securePrefs(PREFS_NAME).getString(KEY_ACTIVE, "") ?: ""
        if (active.isNotEmpty() && accounts.has(active)) {
            val acc = accounts.getJSONObject(active)
            val username = acc.optString("username", "")
            val password = acc.optString("password", "")
            if (username.isNotEmpty() && password.isNotEmpty()) {
                doLoginWith(username, password)
                return
            }
        }
        loginAttemptInProgress = false
    }

    private fun doLoginWith(username: String, password: String, attempt: Int = 1) {
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var u = document.querySelector('input[type=text],input[name=username],input[name=email],#username,#email');" +
                        "  var p = document.querySelector('input[type=password],#password');" +
                        "  var b = document.querySelector('#send,button[type=submit],input[type=submit],.btn-login,#login-btn');" +
                        "  if(!u || !p){ return 'fields_not_ready'; }" +
                        "  u.value='" + username + "';" +
                        "  u.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "  u.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "  p.value='" + password + "';" +
                        "  p.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "  p.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "  if(b){ b.click(); return 'submitted'; }" +
                        "  return 'submitted_no_button';" +
                        "})()"
            ) { resultRaw ->
                val result = resultRaw.trim().removeSurrounding("\"")
                if (result == "fields_not_ready" && attempt < 6) {
                    doLoginWith(username, password, attempt + 1)
                } else {
                    loginDone = (result == "submitted" || result == "submitted_no_button")
                    loginAttemptInProgress = false
                }
            }
        }, 300L)
    }

    private fun saveCookieForCurrentAccount(domain: String) {
        val cookie = CookieManager.getInstance().getCookie(domain)
        val active = securePrefs(PREFS_NAME).getString(KEY_ACTIVE, "") ?: ""
        if (cookie != null && active.isNotEmpty()) {
            val rawAccounts = securePrefs(PREFS_NAME).getString(KEY_ACCOUNTS, "") ?: ""
            val accounts = if (rawAccounts.isEmpty()) JSONObject() else JSONObject(rawAccounts)
            val acc = if (accounts.has(active)) accounts.getJSONObject(active) else JSONObject()
            acc.put("cookie", cookie)
            accounts.put(active, acc)
            securePrefs(PREFS_NAME).edit().putString(KEY_ACCOUNTS, accounts.toString()).apply()
        }
    }

    private fun cacheIspSessionCookieIfApplicable(isp: String, domain: String) {
        val ispUsername = IspPanelSettingsActivity.getSavedUsername(this, isp, targetZone)
        if (!ispUsername.isNullOrEmpty()) {
            val cookie = CookieManager.getInstance().getCookie(domain)
            if (!cookie.isNullOrEmpty()) {
                securePrefs(ISP_SESSION_PREFS).edit().putString("${isp}_$targetZone", cookie).apply()
            }
        }
    }

    private fun securePrefs(name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return try {
            EncryptedSharedPreferences.create(
                this, name, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            getSharedPreferences(name, MODE_PRIVATE).edit().clear().commit()
            deleteSharedPreferences(name)
            EncryptedSharedPreferences.create(
                this, name, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private fun prepareEbonePasswordAction() {
        val custId = autoActivateCustomerId?.trim().orEmpty()
        if (custId.isEmpty()) { finishManualActionFailure("EBONE Customer ID is missing"); return }

        if (manualAction == "ENABLE" || manualAction != "SUSPEND" || !eboneOriginalPassword.isNullOrBlank()) {
            fillEbonePasswordAndSubmit(); return
        }

        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var inputs = document.querySelectorAll('input[type=password]');" +
                        "  var values = [];" +
                        "  for (var i=0;i<inputs.length;i++){ var v = (inputs[i].value || '').trim(); if(v){ values.push(v); } }" +
                        "  return JSON.stringify({count:values.length,values:values});" +
                        "})()"
            ) { raw ->
                val clean = raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                val valuesRaw = Regex("\"values\":\\[(.*?)\\]").find(clean)?.groupValues?.get(1).orEmpty()
                val values = Regex("\"(.*?)\"").findAll(valuesRaw).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
                val original = values.firstOrNull()

                if (original.isNullOrBlank()) {
                    finishManualActionFailure("EBONE Inspector page did not expose the current password for $custId.")
                    return@evaluateJavascript
                }

                eboneOriginalPassword = original
                db.collection("customers").document(custId)
                    .update(mapOf("eboneOriginalPassword" to original, "eboneOriginalPasswordSavedAt" to FieldValue.serverTimestamp()))
                    .addOnSuccessListener { fillEbonePasswordAndSubmit() }
                    .addOnFailureListener { finishManualActionFailure("Could not save original EBONE password: ${it.message}") }
            }
        }, 700)
    }

    private fun fillEbonePasswordAndSubmit() {
        val newPassword = if (manualAction == "SUSPEND") EBONE_SUSPEND_PASSWORD else eboneOriginalPassword?.trim().orEmpty()
        if (newPassword.isEmpty()) { finishManualActionFailure("No original EBONE password available"); return }
        
        webView.settings.saveFormData = false
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var inputs = document.querySelectorAll('input[type=password]');" +
                        "  var filled = 0;" +
                        "  for (var i=0;i<inputs.length;i++){" +
                        "    inputs[i].value = '$newPassword';" +
                        "    inputs[i].dispatchEvent(new Event('input',{bubbles:true}));" +
                        "    inputs[i].dispatchEvent(new Event('change',{bubbles:true}));" +
                        "    filled++;" +
                        "  }" +
                        "  return filled + '';" +
                        "})()"
            ) { result ->
                if ((result.trim().removeSurrounding("\"").toIntOrNull() ?: 0) == 0) {
                    finishManualActionFailure("Password field not found"); return@evaluateJavascript
                }
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){" +
                                "  var form = document.querySelector('form[action*=\"clientChange\"]') || document.querySelector('form');" +
                                "  if(form){ form.submit(); return 'submitted'; }" +
                                "  var b = document.querySelector('button[type=submit]');" +
                                "  if(b){ b.click(); return 'submitted'; }" +
                                "  return 'not found';" +
                                "})()"
                    ) { res ->
                        if (res.trim().removeSurrounding("\"") == "submitted") {
                            webView.postDelayed({ finishManualActionSuccess() }, 2000)
                        } else {
                            finishManualActionFailure("Submit button not found")
                        }
                    }
                }, 800)
            }
        }, 700)
    }

    private fun captureEbonePasswordFromProfileThenChange(customerId: String) {
        webView.evaluateJavascript(
            "(function(){" +
                    "  var rows = document.querySelectorAll('table tbody tr');" +
                    "  for (var i=0;i<rows.length;i++){" +
                    "    var th = rows[i].querySelector('th');" +
                    "    if (!th || th.textContent.indexOf('Password') === -1) continue;" +
                    "    var tds = rows[i].querySelectorAll('td');" +
                    "    if (tds.length < 2) continue;" +
                    "    return tds[1].textContent.replace(/Change\\s*$/, '').trim();" +
                    "  }" +
                    "  return '';" +
                    "})()"
        ) { raw ->
            val original = raw.trim().removeSurrounding("\"")
            if (original.isBlank()) { finishManualActionFailure("EBONE profile page did not expose password"); return@evaluateJavascript }

            eboneOriginalPassword = original
            db.collection("customers").document(customerId)
                .update(mapOf("eboneOriginalPassword" to original, "eboneOriginalPasswordSavedAt" to FieldValue.serverTimestamp()))
                .addOnSuccessListener {
                    val base = (customerUrlOverride ?: "https://partner.ebill.pk").trimEnd('/')
                    webView.loadUrl("$base/clients/clientChange/$customerId")
                }
                .addOnFailureListener { finishManualActionFailure("Could not save original password") }
        }
    }

    private fun clickEboneActiveLink() {
        webView.evaluateJavascript("(function(){ var link = document.querySelector('a[href*=\"/clientStats/\"]'); if(link){ link.click(); return 'clicked'; } return 'not found'; })()", null)
    }

    private fun clickEboneSubmitButton() {
        eboneSubmitClicked = true
        webView.postDelayed({
            webView.evaluateJavascript("(function(){ var btn = document.querySelector('button[type=submit].btn-default') || document.querySelector('button[type=submit]'); if(btn){ btn.click(); return 'submitted'; } return 'not found'; })()", null)
        }, 800)
    }

    private fun fetchEboneExpiryAndFinish() {
        webView.evaluateJavascript("(function(){ var expiry = ''; var rows = document.querySelectorAll('table.table-hover tbody tr'); for (var i=0; i<rows.length; i++){ var th = rows[i].querySelector('th'); if (th && th.innerText.indexOf('Expiry') > -1){ var cells = rows[i].querySelectorAll('td'); if (cells.length > 0){ expiry = (cells[cells.length - 1].textContent || '').trim(); } break; } } return JSON.stringify({expiry:expiry}); })()") { result ->
            try {
                val clean = result.removeSurrounding("\"").replace("\\\"", "\"")
                val expiry = Regex("\"expiry\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                writeActivationResultToFirestore(expiry.isNotEmpty(), expiry)
                setResult(RESULT_OK, Intent().apply { putExtra("activation_success", expiry.isNotEmpty()); putExtra("new_expiry_date", expiry) })
            } catch (e: Exception) { setResult(RESULT_OK, Intent().apply { putExtra("activation_success", false) }) }
            finally { finish() }
        }
    }

    private fun searchEboneCustomer(customerId: String) {
        val base = (customerUrlOverride ?: "https://partner.ebill.pk").trimEnd('/')
        if (manualAction == "ENABLE") {
            db.collection("customers").document(customerId.trim()).get()
                .addOnSuccessListener {
                    eboneOriginalPassword = it.getString("eboneOriginalPassword")?.trim()
                    if (eboneOriginalPassword.isNullOrBlank()) finishManualActionFailure("Original password not found")
                    else webView.loadUrl("$base/clients/clientChange/${customerId.trim()}")
                }
                .addOnFailureListener { finishManualActionFailure("Could not read saved password") }
            return
        }
        eboneOriginalPassword = null
        webView.loadUrl("$base/clients/client/$customerId")
    }

    private fun fetchEboneCustomerDetails(attempt: Int = 1) {
        if (eboneDetailsFetchDone) return
        val expectedId = autoActivateCustomerId
        if (expectedId != null) {
            val urlId = (webView.url ?: "").substringAfterLast("/clients/client/").substringBefore("?").trim().trimEnd('/')
            if (urlId != expectedId) {
                if (attempt < 3) webView.postDelayed({ fetchEboneCustomerDetails(attempt + 1) }, 1000)
                else finishManualActionFailure("Customer ID mismatch")
                return
            }
        }
        val script = "(function(){ var userId = '', address = '', phone = ''; var rows = document.querySelectorAll('table.table-hover tbody tr'); for (var i=0; i<rows.length; i++){ var th = rows[i].querySelector('th'); if (!th) continue; var label = th.textContent.trim(); var tds = rows[i].querySelectorAll('td'); if (label.indexOf('UserID') > -1) { if (tds[0]) userId = (tds[0].textContent || '').trim(); } else if (label.indexOf('Address') > -1 && label.indexOf('Email') === -1) { if (tds[0]) address = (tds[0].textContent || '').trim(); if (tds[1]) { var rawPhone = (tds[1].textContent || '').trim().replace(/^\\/+/, ''); var parts = rawPhone.split('/').filter(function(p){ return p.trim().length > 0; }); phone = parts.length > 0 ? parts[0].trim() : rawPhone.trim(); } } } return JSON.stringify({userId:userId, address:address, phone:phone}); })()"
        webView.evaluateJavascript(script) { result ->
            try {
                val clean = result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                val userId = Regex("\"userId\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val address = Regex("\"address\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val phone = normalizePakPhone(Regex("\"phone\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: "")
                if (expectedId != null && userId.isNotEmpty() && !userId.equals(expectedId, ignoreCase = true)) {
                    if (attempt < 4) webView.postDelayed({ fetchEboneCustomerDetails(attempt + 1) }, 1000)
                    else finishManualActionFailure("Customer data mismatch")
                    return@evaluateJavascript
                }
                if (!eboneDetailsFetchDone && (userId.isNotEmpty() || address.isNotEmpty() || phone.isNotEmpty())) {
                    eboneDetailsFetchDone = true
                    setResult(RESULT_OK, Intent().apply { putExtra("fetched_user_id", userId); putExtra("fetched_address", address); putExtra("fetched_phone", phone) })
                    finish()
                } else if (attempt < 4) { webView.postDelayed({ fetchEboneCustomerDetails(attempt + 1) }, 1000) }
            } catch (e: Exception) {}
        }
    }

    private fun fillDealerTopupAmountAndSubmit() {
        val amount = topupAmount
        if (amount.isNullOrBlank()) { finishManualActionFailure("No amount provided"); return }
        if (eboneTopupSuccessConfirmed) return
        if (eboneTopupSubmitAttempted) { verifyEboneDealerTopupResult(amount, eboneTopupVerificationAttempt); return }
        if (eboneTopupSubmitAttempt >= 3) { finishManualActionFailure("Form not ready after 3 checks"); return }

        eboneTopupSubmitAttempt++
        webView.postDelayed({
            webView.evaluateJavascript("(function(){ var inp = document.querySelector('input[name=\\\"PaidAmt\\\"]'); if(!inp) return 'no_amount_field'; if(inp.disabled || inp.readOnly) return 'disabled'; inp.value = '$amount'; inp.dispatchEvent(new Event('input',{bubbles:true})); var form = inp.form || document.querySelector('form[action*=\\\"addbalance\\\"]'); if(!form) return 'no_form'; var btn = form.querySelector('button[type=submit],input[type=submit],#send'); if(btn && (btn.disabled || btn.offsetParent === null)) return 'submit_not_ready'; return 'ready'; })()") { raw ->
                if (raw.trim().removeSurrounding("\"") != "ready") { webView.postDelayed({ fillDealerTopupAmountAndSubmit() }, 1200); return@evaluateJavascript }
                webView.evaluateJavascript("(function(){ var inp = document.querySelector('input[name=\\\"PaidAmt\\\"]'); var form = inp ? (inp.form || document.querySelector('form[action*=\\\"addbalance\\\"]')) : null; var btn = form ? (form.querySelector('button[type=submit],input[type=submit],#send') || document.querySelector('#send')) : null; if(btn && !btn.disabled && btn.offsetParent !== null){ btn.click(); return 'clicked'; } return 'submit_not_ready'; })()") { res ->
                    if (res.trim().removeSurrounding("\"") == "clicked") {
                        eboneTopupSubmitAttempted = true; eboneTopupVerificationAttempt = 0
                        verifyEboneDealerTopupResult(amount, 0)
                    } else { webView.postDelayed({ fillDealerTopupAmountAndSubmit() }, 1200) }
                }
            }
        }, 900)
    }

    private fun verifyEboneDealerTopupResult(amount: String, attempt: Int) {
        if (eboneTopupSuccessConfirmed) return
        if (attempt >= 3) { finishManualActionFailure("Submit clicked but success not confirmed"); return }

        eboneTopupVerificationAttempt = attempt
        webView.postDelayed({
            webView.evaluateJavascript("(function(){ var body=((document.body&&document.body.innerText)||'').replace(/\\\\s+/g,' ').trim(); var url=window.location.href||''; var success=/(payment|balance|credit|transaction)[^\\\\n]{0,80}(success|successful|completed|added|updated)|successfully[^\\\\n]{0,80}(payment|added|updated|credited)|payment[^\\\\n]{0,80}successful/i.test(body); var error=/(error|failed|failure|invalid|insufficient|unable|cannot|not found)/i.test(body); return JSON.stringify({url:url,success:success,error:error,body:body.substring(0,800)}); })()") { raw ->
                val clean = raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                val success = clean.contains("\"success\":true")
                if (success) { eboneTopupSuccessConfirmed = true; captureDealerTopupResult(); return@evaluateJavascript }
                if (attempt + 1 < 3 && !clean.contains("\"error\":true")) { webView.postDelayed({ verifyEboneDealerTopupResult(amount, attempt + 1) }, 850) }
                else { markSubmittedButUnconfirmed() }
            }
        }, 1800)
    }

    private fun markSubmittedButUnconfirmed() {
        eboneTopupSuccessConfirmed = true
        sourceTransactionId?.let { if (it.isNotBlank()) db.collection("dealerTransactions").document(it).update(mapOf("transferStatus" to "AUTO_FAILED", "transferError" to "Submit clicked once but success not confirmed")) }
        finishManualActionFailure("Submit clicked once but success not confirmed. Check panel manually.")
    }

    private fun captureDealerTopupResult() {
        webView.evaluateJavascript("(function(){ var b = document.querySelector('.box-body'); var text = (b ? b.innerText : document.body.innerText) || ''; var dealerBalance = ''; var labels = document.querySelectorAll('h5.card-title'); for (var i=0;i<labels.length;i++){ if (labels[i].innerText.trim() === 'Dealer Balance'){ var valEl = labels[i].parentElement ? labels[i].parentElement.querySelector('span.h2') : null; if (valEl) dealerBalance = valEl.innerText.trim(); break; } } return JSON.stringify({text: text.substring(0, 800), url: window.location.href, dealerBalance: dealerBalance}); })()") { raw ->
            val clean = raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            val balance = Regex("\"dealerBalance\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
            val amount = topupAmount?.toDoubleOrNull() ?: 0.0
            val logEntry = mapOf("dealerId" to (dealerInternalId ?: ""), "dealerName" to (dealerDisplayName ?: ""), "panel" to "EBONE", "ispDealerId" to (dealerEboneId ?: ""), "amount" to amount, "submittedAt" to System.currentTimeMillis(), "resultText" to clean.take(500), "dealerBalanceAfter" to balance, "sourceTransactionId" to (sourceTransactionId ?: ""))
            db.collection("dealerPayments").add(logEntry)

            val smsId = sourceTransactionId?.takeIf { it.isNotBlank() }
            if (smsId != null) {
                db.collection("dealerTransactions").document(smsId).update(mapOf("status" to "COMPLETED", "transferStatus" to "TRANSFERRED", "transferredAt" to System.currentTimeMillis(), "transferResultText" to clean.take(500)))
                    .addOnSuccessListener { manualAction = null; setResult(RESULT_OK, Intent().apply { putExtra("dealer_topup_submitted", true); putExtra("sms_payment_completed", true); putExtra("source_transaction_id", smsId) }); finish() }
                    .addOnFailureListener { continueAfterDealerTopupSuccess() }
            } else {
                setResult(RESULT_OK, Intent().apply { putExtra("dealer_topup_submitted", true) })
                continueAfterDealerTopupSuccess()
            }
        }
    }

    private fun continueAfterDealerTopupSuccess() {
        manualAction = "CHECK_BALANCE"
        eboneBalanceCheckAttempted = false
        webView.postDelayed({ readEboneFranchiseBalance() }, 1000)
    }

    private fun readEboneFranchiseBalance(attempt: Int = 1) {
        webView.evaluateJavascript("(function(){ var footer = document.querySelector('.dropdown-menu li.footer'); var text = footer ? footer.innerText : ''; if (text && text.indexOf('Balance') > -1) { return JSON.stringify({text:text, clicked:false}); } var icon = document.querySelector('i.fa-dollar') || document.querySelector('.fa-dollar'); var link = icon ? icon.closest('a') : null; if (link) { link.click(); } return JSON.stringify({text:'', clicked: !!link}); })()") { resultRaw ->
            try {
                val clean = resultRaw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                val text = Regex("\"text\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val clicked = clean.contains("\"clicked\":true")
                if (text.isNotBlank()) {
                    val balance = Regex("Balance:\\s*(-?[0-9.,]+)").find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
                    if (balance != null) {
                        FranchiseBalanceManager.updateBalance("EBONE", balance, targetZone) { 
                            FranchiseBalanceManager.showUpdateNotification(this, "EBONE", balance, targetZone)
                            FranchiseBalanceManager.checkAndNotifyLowBalance(this, "EBONE", balance, targetZone) 
                        }
                        setResult(RESULT_OK, Intent().apply { putExtra("checked_balance", balance) })
                        webView.postDelayed({ finish() }, 500)
                        return@evaluateJavascript
                    }
                }
                if (attempt < 10) webView.postDelayed({ readEboneFranchiseBalance(attempt + 1) }, if (clicked) 1000L else 1500L)
                else finishManualActionFailure("Could not read Ebone franchise balance")
            } catch (e: Exception) { if (attempt < 10) webView.postDelayed({ readEboneFranchiseBalance(attempt + 1) }, 1000) else finishManualActionFailure("Error reading balance") }
        }
    }

    private fun normalizePakPhone(raw: String): String {
        val p = raw.trim().replace(" ", "").replace("-", "")
        return when { p.startsWith("+92") -> "0" + p.substring(3); p.startsWith("92") && p.length > 10 -> "0" + p.substring(2); else -> p }
    }

    private fun writeActivationResultToFirestore(success: Boolean, expiry: String) {
        val actualSuccess = success && expiry.isNotBlank()
        transactionId?.let { db.collection("transactions").document(it).update("status", if (actualSuccess) "VERIFIED" else "FAILED") }
        if (actualSuccess) autoActivateCustomerId?.let { db.collection("customers").document(it).update(mapOf("activationStatus" to "ACTIVE", "lastPaymentDate" to System.currentTimeMillis(), "ispExpiryDate" to expiry)) }
    }

    private fun finishManualActionSuccess() {
        val custId = autoActivateCustomerId
        if (custId != null) {
            val newStatus = if (manualAction == "SUSPEND") "DISABLED" else "ACTIVE"
            val updates = mutableMapOf<String, Any>("activationStatus" to newStatus)
            if (manualAction == "ENABLE") { updates["lastPaymentDate"] = System.currentTimeMillis(); updates["graceDeadline"] = FieldValue.delete(); updates["eboneOriginalPassword"] = FieldValue.delete(); updates["eboneOriginalPasswordSavedAt"] = FieldValue.delete(); updates["reliefStatus"] = FieldValue.delete(); updates["reliefTestMode"] = FieldValue.delete(); updates["reliefCompany"] = FieldValue.delete() }
            else if (manualAction == "SUSPEND") { updates["reliefStatus"] = "SUSPENDED"; if (!eboneOriginalPassword.isNullOrBlank()) updates["eboneOriginalPasswordSavedAt"] = FieldValue.serverTimestamp() }
            db.collection("customers").document(custId).update(updates)
            if (manualAction == "SUSPEND") ReliefLogRepository.logDeactivation(custId)
        }
        setResult(RESULT_OK, Intent().apply { putExtra("manual_action_success", true) })
        finish()
    }

    private fun finishManualActionFailure(reason: String) {
        setResult(RESULT_OK, Intent().apply { putExtra("manual_action_success", false); putExtra("error_reason", reason) })
        finish()
    }

    private fun performVisualAutoResolve(searchSelector: String, successTag: String) {
        val custId = autoActivateCustomerId ?: return
        webView.evaluateJavascript("(function(){ var searchBox = document.querySelector('$searchSelector'); if(searchBox){ searchBox.value = '$custId'; searchBox.dispatchEvent(new Event('input', { bubbles: true })); searchBox.dispatchEvent(new Event('keyup', { bubbles: true })); return 'searching'; } return 'box_not_found'; })()") { result ->
            if (result.contains("searching")) {
                webView.postDelayed({
                    webView.evaluateJavascript("(function(){ var bodyText = document.body.innerText; var tagFound = '$successTag' === '' || bodyText.indexOf('$successTag') > -1; if(bodyText.indexOf('$custId') > -1 && tagFound){ return 'found_online'; } return 'not_found_yet'; })()") { status ->
                        if (status.contains("found_online")) {
                            complaintIdToResolve?.let { cId ->
                                val fb = FirebaseDatabase.getInstance()
                                val updates = mapOf("status" to "Resolved", "resolvedTime" to System.currentTimeMillis(), "resolvedBy" to "System Visual Monitor", "is_system_resolved" to true)
                                fb.getReference("complaints").child(cId).updateChildren(updates).addOnSuccessListener {
                                    fb.getReference("resolvedComplaints").child(cId).setValue(true)
                                    Toast.makeText(this, "Complaint Resolved Successfully!", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }
                        }
                    }
                }, 4000)
            }
        }
    }
}
