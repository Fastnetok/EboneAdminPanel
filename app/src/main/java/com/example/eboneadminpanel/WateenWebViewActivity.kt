package com.example.eboneadminpanel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject

/**
 * Isolated WebView flow for WATEEN.
 *
 * Handles:
 *  - Wateen login
 *  - Wateen customer activation / profile fetching
 *  - Wateen dealer top-up (including Manual Step Mode)
 *  - Wateen franchise balance check
 */
class WateenWebViewActivity : AppCompatActivity() {

    private val TAG = "WateenWebView"
    private lateinit var webView: WebView
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var loginDone = false
    private var loginAttemptInProgress = false
    private var activeAccountName = ""
    private var autoActivateCustomerId: String? = null
    private var transactionId: String? = null
    private var manualAction: String? = null
    private var dealerEboneId: String? = null
    private var topupAmount: String? = null
    private var targetZone: String = "Okara"
    private var sourceTransactionId: String? = null
    private var debugTapInspectorEnabled = false
    private var wateenDealerListLoadAttempted = false
    private var wateenDealerSearchAttempted = false
    private var wateenTopupSubmitAttempted = false
    private var wateenBalanceCheckAttempted = false
    private var wateenBalanceReadAttempt = 0
    private var wateenDealerSearchAttempt = 0
    private var wateenModalOpenAttempt = 0
    private var wateenManualStepMode = false
    private var wateenManualStepStage = 0
    private var wateenPageLengthSet = false
    private var wateenModalFillAttempt = 0
    private var wateenTopupSubmitClicked = false
    private var wateenTopupSuccessConfirmed = false
    private var wateenTopupVerificationAttempt = 0
    private var dealerInternalId: String? = null
    private var dealerDisplayName: String? = null
    private var dealerSearchName: String? = null
    private var forceAccountName: String? = null
    private var complaintIdToResolve: String? = null
    private var actionStartedAfterLogin = false // NEW: Track if we've already triggered the action

    companion object {
        private const val WATEEN_PREFS = "wateen_accounts"
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
        dealerSearchName = intent.getStringExtra("dealer_search_name")
        targetZone = intent.getStringExtra("target_zone")?.ifBlank { null } ?: "Okara"
        complaintIdToResolve = intent.getStringExtra("complaint_id_to_resolve")
        forceAccountName = intent.getStringExtra("dealer_account_name")
        debugTapInspectorEnabled = intent.getBooleanExtra("debug_tap_inspector", false)

        Log.d(TAG, "onCreate: manualAction=$manualAction, dealerEboneId=$dealerEboneId, topupAmount=$topupAmount")

        if (manualAction == "DEALER_TOPUP" && forceAccountName.isNullOrBlank()) {
            forceAccountName = dealerDisplayName?.trim()?.takeIf { it.isNotBlank() }
        }

        wateenManualStepMode = manualAction == "DEALER_TOPUP" && intent.getBooleanExtra("wateen_manual_step_mode", false)

        webView = findViewById(R.id.loginWebView)

        val switchButton = findViewById<Button>(R.id.accountSwitchButton)
        if (wateenManualStepMode) {
            switchButton.text = "Next Step ▶"
            switchButton.setOnClickListener { advanceWateenManualStep() }
        } else {
            switchButton.setOnClickListener { showAccountListDialog() }
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
        
        // FIX (WATEEN only): every HTML inspect we've been building
        // selectors from was taken from a real DESKTOP browser.
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                Log.d(TAG, "onPageFinished: $url")
                CookieManager.getInstance().flush()
                handlePageLoaded(url)

                if (!loginDone && !loginAttemptInProgress) {
                    webView.evaluateJavascript(
                        "(function(){ var p = document.querySelector('input[type=password]'); return p ? 'has_password_field' : 'no'; })()"
                    ) { hasPasswordField ->
                        if (hasPasswordField.trim().removeSurrounding("\"") == "has_password_field" && !loginDone && !loginAttemptInProgress) {
                            Log.d(TAG, "Password field detected, starting tryAutoLogin")
                            tryAutoLogin()
                        }
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.contains("/user/user/view/")) {
                    if (autoActivateCustomerId != null) {
                        webView.postDelayed({ onWateenProfileOpened() }, 1500)
                    } else {
                        webView.postDelayed({ fetchWateenCustomerDetails() }, 1500)
                    }
                }
                return false
            }
        }
    }

    private fun handlePageLoaded(url: String) {
        when {
            url.contains("auth.html") -> {
                Log.d(TAG, "Auth page loaded. loginDone=false")
                loginDone = false
                loginAttemptInProgress = false
                actionStartedAfterLogin = false
                tryAutoLogin()
            }
            url.contains("panel.wateen.com") && url.contains("/user/user/view/") && autoActivateCustomerId != null -> {
                onWateenProfileOpened()
            }
            url.contains("panel.wateen.com") && url.contains("/user/user/view/") -> {
                fetchWateenCustomerDetails()
            }
            url.contains("panel.wateen.com") && !url.contains("auth.html") && !url.contains("/user/user/view/") && !url.contains("/dealer/dealer/") -> {
                Log.d(TAG, "Dashboard detected. Setting loginDone=true")
                loginDone = true
                saveCookieForCurrentAccount("https://panel.wateen.com")
                cacheIspSessionCookieIfApplicable("WATEEN", "https://panel.wateen.com")
                
                if (manualAction == "DEALER_TOPUP" && !dealerEboneId.isNullOrBlank()) {
                    if (!wateenDealerListLoadAttempted) {
                        wateenDealerListLoadAttempted = true
                        Log.d(TAG, "Loading all dealers list")
                        webView.postDelayed({ webView.loadUrl("https://panel.wateen.com/dealer/dealer/all") }, 800)
                    }
                } else if (manualAction == "FETCH_DEALER_ID" && !dealerSearchName.isNullOrBlank()) {
                    if (!wateenDealerListLoadAttempted) {
                        wateenDealerListLoadAttempted = true
                        Log.d(TAG, "Loading all dealers list")
                        webView.postDelayed({ webView.loadUrl("https://panel.wateen.com/dealer/dealer/all") }, 800)
                    }
                } else if (manualAction == "FETCH_DEALER_ID" && !dealerSearchName.isNullOrBlank()) {
                    if (!wateenDealerListLoadAttempted) {
                        wateenDealerListLoadAttempted = true
                        webView.postDelayed({ webView.loadUrl("https://panel.wateen.com/dealer/dealer/all") }, 800)
                    }
                } else if (manualAction == "RESOLVE_MONITOR" && autoActivateCustomerId != null) {
                    if (!url.contains("/user/user/online")) {
                        webView.loadUrl("https://panel.wateen.com/user/user/online")
                    } else {
                        performVisualAutoResolve("input[aria-controls=\"allonlineUsers\"]", "")
                    }
                } else if (manualAction == "CHECK_BALANCE") {
                    if (!url.contains("/user/user/") && !url.contains("/dealer/dealer/")) {
                        if (!wateenBalanceCheckAttempted) {
                            wateenBalanceCheckAttempted = true
                            wateenBalanceReadAttempt = 1
                            Log.d(TAG, "Checking balance")
                            webView.postDelayed({ readWateenFranchiseBalance(1) }, 450)
                        }
                    } else if (!wateenBalanceCheckAttempted) {
                        webView.postDelayed({ webView.loadUrl("https://panel.wateen.com/") }, 800)
                    }
                } else if (!url.contains("/user/user/all")) {
                    webView.postDelayed({ webView.loadUrl("https://panel.wateen.com/user/user/all") }, 800)
                } else {
                    autoActivateCustomerId?.let { id -> 
                        webView.postDelayed({ searchWateenCustomer(id) }, 800)
                    }
                }
            }
            url.contains("/dealer/dealer/all") && (manualAction == "DEALER_TOPUP" || manualAction == "FETCH_DEALER_ID") -> {
                if (!wateenDealerSearchAttempted) {
                    wateenDealerSearchAttempted = true
                    val searchTerm = if (manualAction == "FETCH_DEALER_ID") dealerSearchName else dealerEboneId
                    if (!wateenManualStepMode) {
                        Log.d(TAG, "Starting searchWateenDealer for $searchTerm")
                        webView.postDelayed({ searchWateenDealer(searchTerm ?: "") }, 900)
                    }
                }
            }
            url.contains("/dealer/dealer/view/") && manualAction == "DEALER_TOPUP" -> {
                if (wateenTopupSubmitClicked && !wateenTopupSuccessConfirmed) {
                    verifyWateenDealerTopupResult(wateenTopupVerificationAttempt)
                } else if (!wateenTopupSubmitClicked && !wateenTopupSuccessConfirmed && !wateenTopupSubmitAttempted) {
                    wateenTopupSubmitAttempted = true
                    Log.d(TAG, "Opening payment modal on profile")
                    webView.postDelayed({ openWateenPaymentModalAndSubmit() }, 1800)
                }
            }
        }
    }

    private fun loadInitialPage() {
        // Purge any stale cookies from other ISP / Zone sessions
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        val ispUsername = IspPanelSettingsActivity.getSavedUsername(this, "WATEEN", targetZone)
        if (!ispUsername.isNullOrEmpty()) {
            val savedCookie = securePrefs(ISP_SESSION_PREFS).getString("WATEEN_$targetZone", "") ?: ""
            if (savedCookie.isNotEmpty()) {
                Log.d(TAG, "Loading with saved ISP session cookie for Wateen / $targetZone")
                savedCookie.split(";").forEach {
                    if (it.isNotBlank()) CookieManager.getInstance().setCookie("https://panel.wateen.com", it.trim())
                }
                CookieManager.getInstance().flush()
                webView.loadUrl("https://panel.wateen.com/user/user/all")
            } else {
                webView.loadUrl("https://panel.wateen.com/auth.html")
            }
            return
        }

        val rawAccounts = securePrefs(WATEEN_PREFS).getString(KEY_ACCOUNTS, "") ?: ""
        val accounts = if (rawAccounts.isEmpty()) JSONObject() else JSONObject(rawAccounts)
        val active = securePrefs(WATEEN_PREFS).getString(KEY_ACTIVE, "") ?: ""
        if (active.isNotEmpty() && accounts.has(active)) {
            activeAccountName = active
            val acc = accounts.getJSONObject(active)
            val cookie = acc.optString("cookie", "")
            if (cookie.isNotEmpty()) {
                Log.d(TAG, "Loading with saved account cookie: $active")
                CookieManager.getInstance().setCookie("https://panel.wateen.com", cookie)
                CookieManager.getInstance().flush()
                webView.loadUrl("https://panel.wateen.com/user/user/all")
            } else {
                webView.loadUrl("https://panel.wateen.com/auth.html")
            }
        } else {
            webView.loadUrl("https://panel.wateen.com/auth.html")
        }
    }

    private fun tryAutoLogin() {
        if (loginAttemptInProgress) return
        loginAttemptInProgress = true

        Log.d(TAG, "tryAutoLogin started")

        if (!forceAccountName.isNullOrBlank()) {
            val du = IspPanelSettingsActivity.getDealerUsername(this, "WATEEN", targetZone, forceAccountName!!)
            val dp = IspPanelSettingsActivity.getDealerPassword(this, "WATEEN", targetZone, forceAccountName!!)
            if (!du.isNullOrEmpty() && !dp.isNullOrEmpty()) {
                Log.d(TAG, "Logging in with forced account: $forceAccountName")
                doLoginWith(du, dp)
                return
            }
        }

        val iu = IspPanelSettingsActivity.getSavedUsername(this, "WATEEN", targetZone)
        val ip = IspPanelSettingsActivity.getSavedPassword(this, "WATEEN", targetZone)
        if (!iu.isNullOrEmpty() && !ip.isNullOrEmpty()) {
            Log.d(TAG, "Logging in with saved ISP credentials")
            doLoginWith(iu, ip)
            return
        }

        if (activeAccountName.isNotEmpty()) {
            val rawAccounts = securePrefs(WATEEN_PREFS).getString(KEY_ACCOUNTS, "") ?: ""
            val accounts = if (rawAccounts.isEmpty()) JSONObject() else JSONObject(rawAccounts)
            if (accounts.has(activeAccountName)) {
                val acc = accounts.getJSONObject(activeAccountName)
                val u = acc.optString("username", "")
                val p = acc.optString("password", "")
                if (u.isNotEmpty() && p.isNotEmpty()) {
                    Log.d(TAG, "Logging in with active account credentials: $activeAccountName")
                    doLoginWith(u, p)
                    return
                }
            }
        }
        Log.d(TAG, "No credentials found for auto-login")
        loginAttemptInProgress = false
    }

    private fun doLoginWith(username: String, password: String, attempt: Int = 1) {
        Log.d(TAG, "doLoginWith attempt $attempt for $username")
        webView.postDelayed({
            val script = """
                (function(){
                    var u = document.querySelector('input[type=text],input[name=username],input[name=email],#username,#email');
                    var p = document.querySelector('input[type=password],#password');
                    var b = document.querySelector('#send,button[type=submit],input[type=submit],.btn-login,#login-btn');
                    if(!u || !p){ return 'fields_not_ready'; }
                    u.value = '$username';
                    u.dispatchEvent(new Event('input', {bubbles:true}));
                    p.value = '$password';
                    p.dispatchEvent(new Event('input', {bubbles:true}));
                    if(b){ b.click(); return 'submitted'; }
                    return 'submitted_no_button';
                })()
            """.trimIndent()
            webView.evaluateJavascript(script) { resultRaw ->
                val result = resultRaw.trim().removeSurrounding("\"")
                Log.d(TAG, "doLoginWith result: $result")
                if (result == "fields_not_ready" && attempt < 6) {
                    doLoginWith(username, password, attempt + 1)
                } else {
                    // loginDone will be set in handlePageLoaded when we reach dashboard
                    loginAttemptInProgress = false
                }
            }
        }, 300L)
    }

    private fun saveCookieForCurrentAccount(domain: String) {
        val cookie = CookieManager.getInstance().getCookie(domain)
        if (cookie != null && activeAccountName.isNotEmpty()) {
            val rawAccounts = securePrefs(WATEEN_PREFS).getString(KEY_ACCOUNTS, "") ?: ""
            val accounts = if (rawAccounts.isEmpty()) JSONObject() else JSONObject(rawAccounts)
            val acc = if (accounts.has(activeAccountName)) accounts.getJSONObject(activeAccountName) else JSONObject()
            acc.put("cookie", cookie)
            accounts.put(activeAccountName, acc)
            securePrefs(WATEEN_PREFS).edit().putString(KEY_ACCOUNTS, accounts.toString()).apply()
        }
    }

    private fun cacheIspSessionCookieIfApplicable(isp: String, domain: String) {
        val iu = IspPanelSettingsActivity.getSavedUsername(this, isp, targetZone)
        if (!iu.isNullOrEmpty()) {
            val cookie = CookieManager.getInstance().getCookie(domain)
            if (!cookie.isNullOrEmpty()) {
                securePrefs(ISP_SESSION_PREFS).edit().putString("${isp}_$targetZone", cookie).apply()
            }
        }
    }

    private fun securePrefs(name: String): SharedPreferences {
        val mk = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return try {
            EncryptedSharedPreferences.create(this, name, mk, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (e: Exception) {
            getSharedPreferences(name, MODE_PRIVATE).edit().clear().commit()
            deleteSharedPreferences(name)
            EncryptedSharedPreferences.create(this, name, mk, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }
    }

    private fun showAccountListDialog() {
        val raw = securePrefs(WATEEN_PREFS).getString(KEY_ACCOUNTS, "") ?: ""
        val accounts = if (raw.isEmpty()) JSONObject() else JSONObject(raw)
        val names = accounts.keys().asSequence().toList()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_account_list, null)
        val listView = dialogView.findViewById<ListView>(R.id.accountListView)
        val addButton = dialogView.findViewById<Button>(R.id.addAccountButton)
        val dialog = AlertDialog.Builder(this).setTitle("Wateen Accounts").setView(dialogView).setNegativeButton("Close", null).create()
        val adapter = object : ArrayAdapter<String>(this, R.layout.item_account_row, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_account_row, parent, false)
                val name = names[position]
                view.findViewById<TextView>(R.id.accountNameText).text = name
                view.findViewById<TextView>(R.id.accountStatusText).text = if (name == activeAccountName) "Active" else ""
                view.setOnClickListener { switchToAccount(name); dialog.dismiss() }
                view.findViewById<Button>(R.id.deleteAccountButton).setOnClickListener {
                    val updated = if (raw.isEmpty()) JSONObject() else JSONObject(raw)
                    updated.remove(name)
                    securePrefs(WATEEN_PREFS).edit().putString(KEY_ACCOUNTS, updated.toString()).apply()
                    dialog.dismiss()
                    showAccountListDialog()
                }
                return view
            }
        }
        listView.adapter = adapter
        addButton.setOnClickListener { dialog.dismiss(); showAddAccountDialog() }
        dialog.show()
    }

    private fun showAddAccountDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        val ni = EditText(this).apply { hint = "Account name (e.g. Akmal)" }
        val ui = EditText(this).apply { hint = "Wateen username" }
        val pi = EditText(this).apply { hint = "Wateen password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(ni); layout.addView(ui); layout.addView(pi)
        AlertDialog.Builder(this).setTitle("Add new account").setView(layout).setPositiveButton("Save and login") { _, _ ->
            val an = ni.text.toString().trim()
            val u = ui.text.toString().trim()
            val p = pi.text.toString().trim()
            if (an.isEmpty() || u.isEmpty() || p.isEmpty()) return@setPositiveButton
            val raw = securePrefs(WATEEN_PREFS).getString(KEY_ACCOUNTS, "") ?: ""
            val accounts = if (raw.isEmpty()) JSONObject() else JSONObject(raw)
            accounts.put(an, JSONObject().apply { put("username", u); put("password", p); put("cookie", "") })
            securePrefs(WATEEN_PREFS).edit().putString(KEY_ACCOUNTS, accounts.toString()).apply()
            activeAccountName = an
            securePrefs(WATEEN_PREFS).edit().putString(KEY_ACTIVE, an).apply()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            loginDone = false
            webView.loadUrl("https://panel.wateen.com/auth.html")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun switchToAccount(name: String) {
        val raw = securePrefs(WATEEN_PREFS).getString(KEY_ACCOUNTS, "") ?: ""
        val accounts = if (raw.isEmpty()) JSONObject() else JSONObject(raw)
        if (!accounts.has(name)) return
        val acc = accounts.getJSONObject(name)
        val cookie = acc.optString("cookie", "")
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        activeAccountName = name
        securePrefs(WATEEN_PREFS).edit().putString(KEY_ACTIVE, name).apply()
        if (cookie.isNotEmpty()) {
            CookieManager.getInstance().setCookie("https://panel.wateen.com", cookie)
            CookieManager.getInstance().flush()
            loginDone = true
            webView.loadUrl("https://panel.wateen.com/user/user/all")
        } else {
            loginDone = false
            webView.loadUrl("https://panel.wateen.com/auth.html")
        }
    }

    private fun advanceWateenManualStep() {
        if (!wateenManualStepMode) return
        wateenManualStepStage++
        Log.d(TAG, "advanceWateenManualStep: stage=$wateenManualStepStage")
        when (wateenManualStepStage) {
            1 -> {
                Toast.makeText(this, "Step 1: searching dealer + clicking Payment…", Toast.LENGTH_SHORT).show()
                searchWateenDealer(dealerEboneId ?: "", 1)
            }
            2 -> {
                Toast.makeText(this, "Step 2: selecting Cash + filling amount…", Toast.LENGTH_SHORT).show()
                fillWateenPaymentModal(topupAmount ?: "", 1)
            }
            3 -> {
                Toast.makeText(this, "Step 3 (FINAL): clicking Add Payment — real submission!", Toast.LENGTH_LONG).show()
                clickWateenAddPayment(1)
            }
            else -> {
                Toast.makeText(this, "Already submitted — check the Wateen panel directly.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun searchWateenCustomer(customerId: String) {
        Log.d(TAG, "searchWateenCustomer: $customerId")
        webView.evaluateJavascript(
            "(function(){ var inp = document.querySelector('input[aria-controls=\"userListAll\"],.dataTables_filter input'); if(inp){ inp.focus(); inp.value = '$customerId'; inp.dispatchEvent(new Event('input',{bubbles:true})); inp.dispatchEvent(new Event('keyup',{bubbles:true})); } })()",
            null
        )
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){ var smalls = document.querySelectorAll('a small'); for(var i=0;i<smalls.length;i++){ if(smalls[i].innerText.trim() === '$customerId'){ return smalls[i].closest('a').href; } } var link = document.querySelector('#userListAll tbody tr td a'); if(link){ return link.href; } return ''; })()"
            ) { profileUrl ->
                val cleanUrl = profileUrl.trim().removeSurrounding("\"")
                if (cleanUrl.isNotEmpty() && cleanUrl.startsWith("http")) {
                    webView.loadUrl(cleanUrl)
                }
            }
        }, 1500)
    }

    private fun onWateenProfileOpened() {
        Log.d(TAG, "onWateenProfileOpened")
        if (manualAction == "RELIEF_VIEW") {
            webView.postDelayed({
                setResult(RESULT_OK, Intent().apply { putExtra("activation_success", true); putExtra("manual_action_success", true) })
                finish()
            }, 1500)
            return
        }
        if (manualAction != null) {
            if (manualAction == "SUSPEND") {
                Log.d(TAG, "Performing SUSPEND action")
                webView.evaluateJavascript(
                    "(function(){ var link = document.querySelector('a.disable-user-connection'); if(link){ link.click(); return 'clicked'; } return 'not_found'; })()"
                ) { res ->
                    if (res.trim().removeSurrounding("\"") != "clicked") {
                        finishFailure("Disable link not found")
                        return@evaluateJavascript
                    }
                    webView.postDelayed({
                        webView.evaluateJavascript(
                            "(function(){ var buttons = document.querySelectorAll('button.btn-red'); for (var i=0;i<buttons.length;i++){ if (buttons[i].innerText.trim().indexOf('Disable') > -1){ buttons[i].click(); return 'clicked'; } } if (buttons.length === 1){ buttons[0].click(); return 'clicked'; } return 'not_found'; })()"
                        ) { cres ->
                            if (cres.trim().removeSurrounding("\"") == "clicked") {
                                webView.postDelayed({ finishSuccess() }, 2000)
                            } else {
                                finishFailure("Confirm button not found")
                            }
                        }
                    }, 800)
                }
                return
            }
            Log.d(TAG, "Performing ENABLE action")
            webView.evaluateJavascript(
                "(function(){ var link = document.querySelector('a.enable-user-connection'); if(link){ link.click(); return 'clicked'; } return 'not_found'; })()"
            ) { res ->
                if (res.trim().removeSurrounding("\"") != "clicked") {
                    finishFailure("Enable link not found")
                    return@evaluateJavascript
                }
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){ var buttons = document.querySelectorAll('button.btn-green'); for (var i=0;i<buttons.length;i++){ if (buttons[i].innerText.trim().indexOf('Enable') > -1){ buttons[i].click(); return 'clicked'; } } if (buttons.length === 1){ buttons[0].click(); return 'clicked'; } return 'not_found'; })()"
                        ) { cres ->
                        if (cres.trim().removeSurrounding("\"") == "clicked") {
                            webView.postDelayed({ finishSuccess() }, 2000)
                        } else {
                            finishFailure("Confirm button not found")
                        }
                    }
                }, 800)
            }
            return
        }
        Log.d(TAG, "Performing default RENEW action")
        webView.evaluateJavascript("(function(){ var spans = document.querySelectorAll('span.btn-warning'); for (var i=0;i<spans.length;i++){ if (spans[i].innerText.indexOf('Renew') > -1){ spans[i].click(); return 'clicked'; } } return 'not found'; })()", null)
        webView.postDelayed({
            webView.evaluateJavascript("(function(){ var buttons = document.querySelectorAll('button[type=submit]'); for (var i=0;i<buttons.length;i++){ if (buttons[i].innerText.indexOf('Active User') > -1){ buttons[i].click(); return 'clicked'; } } return 'not found'; })()", null)
        }, 1500)
        webView.postDelayed({ fetchWateenExpiryAndFinish() }, 3500)
    }

    private fun fetchWateenExpiryAndFinish() {
        Log.d(TAG, "fetchWateenExpiryAndFinish")
        webView.evaluateJavascript(
            "(function(){ var expiry = ''; var el = document.querySelector('abbr[title=\"Expiry Date/Time\"] strong'); if (el) { expiry = (el.textContent || el.innerText || '').trim(); } return JSON.stringify({expiry:expiry}); })()"
        ) { result ->
            try {
                val clean = result.removeSurrounding("\"").replace("\\\"", "\"")
                val expiry = Regex("\"expiry\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                setResult(RESULT_OK, Intent().apply { 
                    putExtra("activation_success", true)
                    putExtra("manual_action_success", true)
                    putExtra("new_expiry_date", expiry)
                })
            } catch (e: Exception) {
                setResult(RESULT_OK, Intent().apply { 
                    putExtra("activation_success", false)
                    putExtra("manual_action_success", true)
                })
            } finally {
                finish()
            }
        }
    }

    private fun fetchWateenCustomerDetails() {
        Log.d(TAG, "fetchWateenCustomerDetails")
        webView.evaluateJavascript(
            "(function(){ var userId = '', address = '', phone = ''; var usernameEl = document.querySelector('.h5.font-weight-300'); if (usernameEl){ userId = (usernameEl.innerText || '').replace('@','').trim(); } var addressEl = document.querySelector('.h5.mt-4'); if (addressEl){ address = (addressEl.textContent || '').trim().replace(/[\\r\\n\\t]+/g, ' ').replace(/\\s+/g, ' ').trim(); } var listItems = document.querySelectorAll('.list-group-item'); for (var i=0; i<listItems.length; i++){ var icon = listItems[i].querySelector('i'); if (!icon) continue; var text = (listItems[i].innerText || '').trim(); if ((icon.className.indexOf('fa-mobile-alt') > -1 || icon.className.indexOf('fa-phone') > -1) && phone === ''){ phone = text; } } if (userId === ''){ var urlParts = window.location.href.split('/'); for (var j=0; j<urlParts.length; j++){ if (urlParts[j] === 'view' && urlParts[j+1]){ userId = urlParts[j+1].replace(new RegExp('/','g'),''); } } } return JSON.stringify({userId:userId, address:address, phone:phone}); })()"
        ) { result ->
            try {
                val clean = result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                val userId = Regex("\"userId\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val address = Regex("\"address\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val phone = Regex("\"phone\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                if (userId.isNotEmpty() || address.isNotEmpty() || phone.isNotEmpty()) {
                    setResult(RESULT_OK, Intent().apply {
                        putExtra("fetched_user_id", userId)
                        putExtra("fetched_address", address)
                        putExtra("fetched_phone", phone)
                        putExtra("manual_action_success", true)
                    })
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching customer details: ${e.message}")
            }
        }
    }

    private fun searchWateenDealer(searchTerm: String, attempt: Int = 1) {
        Log.d(TAG, "searchWateenDealer: searchTerm=$searchTerm, attempt=$attempt")
        if (searchTerm.isBlank()) {
            markWateenAutoFailed("No search term was provided", false)
            return
        }
        if (wateenTopupSubmitClicked || wateenTopupSuccessConfirmed) return

        // Step 1: Set page length to 50
        if (!wateenPageLengthSet) {
            wateenPageLengthSet = true
            webView.evaluateJavascript(
                "(function(){ var sel = document.querySelector('select[name=\"dtAllDealers_length\"]'); if(sel){ sel.value = '50'; sel.dispatchEvent(new Event('change',{bubbles:true})); return 'set'; } return 'not_found'; })()"
            ) { 
                webView.postDelayed({ searchWateenDealer(searchTerm, attempt) }, 1000)
            }
            return
        }

        // Step 2: Type search term
        val searchBoxTypedValue = if (manualAction == "DEALER_TOPUP") {
            (dealerDisplayName?.takeIf { it.isNotBlank() } ?: searchTerm)
        } else {
            searchTerm
        }

        webView.evaluateJavascript(
            "(function(){" +
                    "  var inp = document.querySelector('input[aria-controls=\"dtAllDealers\"]');" +
                    "  if(inp){" +
                    "    inp.focus();" +
                    "    inp.value = '$searchBoxTypedValue';" +
                    "    inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "    inp.dispatchEvent(new Event('keyup',{bubbles:true}));" +
                    "    return 'search_box_found';" +
                    "  }" +
                    "  return 'search_box_not_found';" +
                    "})()"
        ) { res ->
            val status = res.trim().removeSurrounding("\"")
            if (status != "search_box_found") {
                if (attempt < 5) {
                    webView.postDelayed({ searchWateenDealer(searchTerm, attempt + 1) }, 1500)
                } else {
                    markWateenAutoFailed("Dealer search box not found", false)
                }
                return@evaluateJavascript
            }

            // Step 3: Find and click Payment button (for DEALER_TOPUP)
            if (manualAction == "DEALER_TOPUP") {
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){" +
                                "  var numericId = '$searchTerm';" +
                                "  var btn = document.querySelector('a.user-payment-btn[data-userid=\"' + numericId + '\"]') || document.querySelector('[data-userid=\"' + numericId + '\"]');" +
                                "  if(btn){" +
                                "    btn.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));" +
                                "    return 'clicked_by_id';" +
                                "  }" +
                                "  var rows = document.querySelectorAll('#dtAllDealers tbody tr, #example1 tbody tr');" +
                                "  for(var i=0; i<rows.length; i++){" +
                                "    var row = rows[i];" +
                                "    if(row.style.display === 'none') continue;" +
                                "    var payBtn = row.querySelector('.btn-primary') || row.querySelector('.btn');" +
                                "    if(payBtn && (payBtn.textContent||'').indexOf('Payment') > -1){" +
                                "       payBtn.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));" +
                                "       return 'clicked_by_row';" +
                                "    }" +
                                "  }" +
                                "  return 'not_found';" +
                                "})()"
                    ) { cres ->
                        if (wateenTopupSubmitClicked || wateenTopupSuccessConfirmed) return@evaluateJavascript
                        val clickResult = cres.trim().removeSurrounding("\"")
                        if (clickResult.startsWith("clicked")) {
                            Log.d(TAG, "Payment button clicked: $clickResult")
                            if (wateenManualStepMode) Toast.makeText(this, "Payment clicked — modal should open.", Toast.LENGTH_SHORT).show()
                            else webView.postDelayed({ fillWateenPaymentModal(topupAmount ?: "", 1) }, 1200)
                        } else {
                            if (attempt < 6) webView.postDelayed({ searchWateenDealer(searchTerm, attempt + 1) }, 1500)
                            else markWateenAutoFailed("Payment button not found in list", false)
                        }
                    }
                }, 1000)
            } else {
                // FETCH_DEALER_ID
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){" +
                                "  var links = document.querySelectorAll('a[href*=\"/dealer/dealer/view/\"]');" +
                                "  for (var i=0;i<links.length;i++){" +
                                "    var row = links[i].closest('tr');" +
                                "    var rt = row ? row.innerText : links[i].innerText;" +
                                "    if(rt && rt.toLowerCase().indexOf('$searchTerm'.toLowerCase()) > -1){ return links[i].href; }" +
                                "  }" +
                                "  if (links.length === 1) { return links[0].href; }" +
                                "  return '';" +
                                "})()"
                    ) { href ->
                        val h = href.trim().removeSurrounding("\"")
                        if (h.isEmpty() || !h.startsWith("http")) {
                            if (attempt < 5) webView.postDelayed({ searchWateenDealer(searchTerm, attempt + 1) }, 1500)
                            else markWateenAutoFailed("Dealer link not found", false)
                        } else {
                            val nid = Regex("""/dealer/dealer/view/(\d+)""").find(h)?.groupValues?.get(1)
                            if (nid != null) {
                                setResult(RESULT_OK, Intent().apply { putExtra("fetched_dealer_id", nid); putExtra("manual_action_success", true) })
                                finish()
                            } else finishFailure("Could not read numeric ID")
                        }
                    }
                }, 1000)
            }
        }
    }

    private fun openWateenPaymentModalAndSubmit(attempt: Int = 1) {
        Log.d(TAG, "openWateenPaymentModalAndSubmit attempt $attempt")
        if (wateenTopupSubmitClicked || wateenTopupSuccessConfirmed) return
        val amount = topupAmount ?: ""
        if (amount.isBlank()) {
            markWateenAutoFailed("No amount provided", false)
            return
        }
        
        webView.evaluateJavascript(
            "(function(){" +
                    "  var els = document.querySelectorAll('.btn-primary');" +
                    "  for (var i=0;i<els.length;i++){" +
                    "    if ((els[i].textContent||'').trim() === 'Payment'){ els[i].click(); return 'clicked'; }" +
                    "  }" +
                    "  var allEls = document.querySelectorAll('a,button,b,span,div,li,td');" +
                    "  for (var k=0;k<allEls.length;k++){" +
                    "    var el = allEls[k];" +
                    "    if ((el.textContent||'').trim() !== 'Payment') continue;" +
                    "    var clickable = el.closest('a,button') || el;" +
                    "    clickable.click();" +
                    "    return 'clicked';" +
                    "  }" +
                    "  return 'not_found';" +
                    "})()"
        ) { res ->
            if (wateenTopupSubmitClicked || wateenTopupSuccessConfirmed) return@evaluateJavascript
            val clickResult = res.trim().removeSurrounding("\"")
            Log.d(TAG, "Payment modal click result: $clickResult")
            if (clickResult == "clicked") {
                webView.postDelayed({ fillWateenPaymentModal(amount, 1) }, 1000)
            } else if (attempt < 5) {
                webView.postDelayed({ openWateenPaymentModalAndSubmit(attempt + 1) }, 1500)
            } else {
                markWateenAutoFailed("Payment button not found on profile", false)
            }
        }
    }

    private fun fillWateenPaymentModal(amount: String, attempt: Int) {
        Log.d(TAG, "fillWateenPaymentModal: amount=$amount, attempt=$attempt")
        if (wateenTopupSubmitClicked || wateenTopupSuccessConfirmed) return

        webView.evaluateJavascript(
            "(function(){" +
                    "  var modals = document.querySelectorAll('.payment_modal, .modal');" +
                    "  var modal = null;" +
                    "  for(var m=0;m<modals.length;m++){" +
                    "    var cs=getComputedStyle(modals[m]);" +
                    "    if(cs.display!=='none' && cs.visibility!=='hidden'){" +
                    "      modal=modals[m]; break;" +
                    "    }" +
                    "  }" +
                    "  var root = modal || document;" +
                    "  var sel = root.querySelector('select.paymentmethod[name=\"paymentmethod\"]') || root.querySelector('select[name=\"paymentmethod\"]');" +
                    "  if(!sel) return 'select_not_found|modal=' + (modal?'yes':'no');" +
                    
                    "  var cashOption = null;" +
                    "  for(var i=0;i<sel.options.length;i++){" +
                    "    var txt=(sel.options[i].textContent||'').trim().toLowerCase();" +
                    "    if(txt==='cash'){ cashOption=sel.options[i]; break; }" +
                    "  }" +
                    "  if(!cashOption) return 'cash_option_not_found';" +
                    
                    // Step A: Select Cash
                    "  if(sel.value !== cashOption.value){" +
                    "    sel.value = cashOption.value;" +
                    "    sel.dispatchEvent(new Event('change',{bubbles:true}));" +
                    "    sel.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "    return 'waiting_for_amount_field';" +
                    "  }" +
                    
                    // Step B: Fill Amount
                    "  var amt = root.querySelector('input.amount[name=\"amount\"]') || root.querySelector('input[name=\"amount\"]');" +
                    "  if(!amt) return 'amt_not_found';" +
                    
                    "  amt.focus();" +
                    "  amt.value = '$amount';" +
                    "  amt.setAttribute('value', '$amount');" +
                    "  amt.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "  amt.dispatchEvent(new Event('change',{bubbles:true}));" +
                    
                    "  var note = root.querySelector('input.other[name=\"other\"]') || document.querySelector('input[name=\"other\"]');" +
                    "  if(note){" +
                    "    note.value = 'Online Payment';" +
                    "    note.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "    note.dispatchEvent(new Event('change',{bubbles:true}));" +
                    "  }" +
                    "  return 'filled';" +
                    "})()"
        ) { fillResultRaw ->
            if (wateenTopupSubmitClicked || wateenTopupSuccessConfirmed) return@evaluateJavascript

            val fillResult = fillResultRaw.trim().removeSurrounding("\"")
            Log.d(TAG, "fillWateenPaymentModal result: $fillResult")

            if (fillResult == "filled") {
                if (wateenManualStepMode) {
                    Toast.makeText(this, "Fields filled. Tap 'Next Step ▶' to submit.", Toast.LENGTH_LONG).show()
                } else {
                    webView.postDelayed({ clickWateenAddPayment(1) }, 800)
                }
            } else if (fillResult == "waiting_for_amount_field" || fillResult.startsWith("select_not_found") || fillResult == "amt_not_found") {
                if (attempt < 15) {
                    val delay = if (fillResult == "waiting_for_amount_field") 700L else 1500L
                    webView.postDelayed({ fillWateenPaymentModal(amount, attempt + 1) }, delay)
                } else {
                    markWateenAutoFailed("Could not fill Wateen fields after $attempt attempts: $fillResult", false)
                }
            } else {
                markWateenAutoFailed("Wateen fill error: $fillResult", false)
            }
        }
    }

    private fun clickWateenAddPayment(attempt: Int) {
        if (wateenTopupSubmitClicked || wateenTopupSuccessConfirmed) return

        webView.evaluateJavascript(
            "(function(){" +
                    "  var modals=document.querySelectorAll('.payment_modal, .modal');" +
                    "  var root=document;" +
                    "  for(var m=0;m<modals.length;m++){" +
                    "    var cs=getComputedStyle(modals[m]);" +
                    "    if(cs.display!=='none' && cs.visibility!=='hidden'){ root=modals[m]; break; }" +
                    "  }" +
                    "  var buttons=root.querySelectorAll('button[type=\"submit\"]');" +
                    "  for(var i=0;i<buttons.length;i++){" +
                    "    var t=(buttons[i].textContent||'').replace(/\\s+/g,' ').trim().toLowerCase();" +
                    "    if(t==='add payment' || t.indexOf('add payment')===0){" +
                    "      buttons[i].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));" +
                    "      return 'submitted';" +
                    "    }" +
                    "  }" +
                    "  return 'not_found';" +
                    "})()"
        ) { submitResultRaw ->
            if (wateenTopupSubmitClicked || wateenTopupSuccessConfirmed) return@evaluateJavascript

            val submitResult = submitResultRaw.trim().removeSurrounding("\"")
            Log.d(TAG, "clickWateenAddPayment attempt $attempt: $submitResult")

            if (submitResult == "submitted") {
                wateenTopupSubmitClicked = true
                wateenTopupVerificationAttempt = 0
                webView.postDelayed({ verifyWateenDealerTopupResult(0) }, 2200)
            } else if (attempt < 5) {
                webView.postDelayed({ clickWateenAddPayment(attempt + 1) }, 1000)
            } else {
                markWateenAutoFailed("Wateen Add Payment button not found after $attempt attempts", false)
            }
        }
    }

    private fun verifyWateenDealerTopupResult(attempt: Int) {
        if (wateenTopupSuccessConfirmed) return

        if (attempt >= 6) {
            markWateenAutoFailed(
                "Wateen Add Payment was clicked, but a definite success response was not detected after $attempt inspections",
                clickedAlready = true
            )
            return
        }

        wateenTopupVerificationAttempt = attempt
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var body=((document.body&&document.body.innerText)||'').replace(/\\s+/g,' ').trim();" +
                        "  var successSelectors='.alert-success,.toast-success,.swal2-success,.notification-success';" +
                        "  var successEl=document.querySelector(successSelectors);" +
                        "  var successText=successEl ? ((successEl.innerText||successEl.textContent)||'') : '';" +
                        "  var strongSuccess=/(payment\\s*(added|successful|successfully|completed)|successfully\\s*(added|updated|credited)|amount\\s*(added|credited)|credit\\s*(added|successful))/i.test(body);" +
                        "  var error=/(insufficient\\s*(balance|credit)|invalid\\s*(amount|payment)|payment\\s*(failed|error)|transaction\\s*(failed|error)|unable\\s*to\\s*(add|process)|cannot\\s*(add|process))/i.test(body);" +
                        "  var modal=document.querySelector('.payment_modal, .modal');" +
                        "  var modalOpen=false;" +
                        "  if(modal){ var cs=getComputedStyle(modal); modalOpen=(cs.display!=='none' && cs.visibility!=='hidden'); }" +
                        "  return JSON.stringify({success:!!successEl||strongSuccess,error:error,modalOpen:modalOpen,url:window.location.href,successText:successText.substring(0,300),body:body.substring(0,1200)});" +
                        "})()"
            ) { raw ->
                if (wateenTopupSuccessConfirmed) return@evaluateJavascript

                val clean = raw.removeSurrounding("\"")
                    .replace("\\\"", "\"")
                    .replace("\\n", " ")
                    .replace("\\r", " ")
                    .replace("\\\\", "\\")

                val success = Regex("\"success\":(true|false)").find(clean)?.groupValues?.get(1) == "true"
                val error = Regex("\"error\":(true|false)").find(clean)?.groupValues?.get(1) == "true"
                val modalOpen = Regex("\"modalOpen\":(true|false)").find(clean)?.groupValues?.get(1) == "true"

                Log.d(TAG, "Wateen PAYMENT INSPECT ${attempt + 1}/6: success=$success error=$error modalOpen=$modalOpen")

                if (success) {
                    wateenTopupSuccessConfirmed = true
                    captureDealerTopupResult()
                    return@evaluateJavascript
                }

                if (error) {
                    markWateenAutoFailed("Wateen panel reported a payment error after Add Payment", true)
                    return@evaluateJavascript
                }

                webView.postDelayed({ verifyWateenDealerTopupResult(attempt + 1) }, 1200)
            }
        }, 900)
    }

    private fun captureDealerTopupResult() {
        Log.d(TAG, "captureDealerTopupResult")
        webView.evaluateJavascript(
            "(function(){ var b = document.querySelector('.box-body'); var text = (b ? b.innerText : document.body.innerText) || ''; var dealerBalance = ''; var labels = document.querySelectorAll('h5.card-title'); for (var i=0;i<labels.length;i++){ if (labels[i].innerText.trim() === 'Dealer Balance'){ var valEl = labels[i].parentElement ? labels[i].parentElement.querySelector('span.h2') : null; if (valEl) dealerBalance = valEl.innerText.trim(); break; } } return JSON.stringify({text: text.substring(0, 800), url: window.location.href, dealerBalance: dealerBalance}); })()"
        ) { raw ->
            val clean = raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            val balance = Regex("\"dealerBalance\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
            val amount = topupAmount?.toDoubleOrNull() ?: 0.0
            
            Log.d(TAG, "Top-up Result Captured. Balance After: $balance")
            
            val logEntry = mapOf(
                "dealerId" to (dealerInternalId ?: ""),
                "dealerName" to (dealerDisplayName ?: ""),
                "panel" to "WATEEN",
                "ispDealerId" to (dealerEboneId ?: ""),
                "amount" to amount,
                "submittedAt" to System.currentTimeMillis(),
                "resultText" to clean.take(500),
                "dealerBalanceAfter" to balance,
                "sourceTransactionId" to (sourceTransactionId ?: "")
            )
            db.collection("dealerPayments").add(logEntry)

            val smsId = sourceTransactionId?.takeIf { it.isNotBlank() }
            if (smsId != null) {
                db.collection("dealerTransactions").document(smsId).update(mapOf(
                    "status" to "COMPLETED",
                    "transferStatus" to "TRANSFERRED",
                    "transferredAt" to System.currentTimeMillis(),
                    "transferResultText" to clean.take(500)
                )).addOnCompleteListener {
                    continueAfterDealerTopupSuccess()
                }
            } else {
                setResult(RESULT_OK, Intent().apply { 
                    putExtra("dealer_topup_submitted", true)
                    putExtra("manual_action_success", true)
                })
                continueAfterDealerTopupSuccess()
            }
        }
    }

    private fun continueAfterDealerTopupSuccess() {
        Log.d(TAG, "continueAfterDealerTopupSuccess: checking franchise balance")
        manualAction = "CHECK_BALANCE"
        wateenBalanceCheckAttempted = false
        wateenBalanceReadAttempt = 0
        webView.postDelayed({ webView.loadUrl("https://panel.wateen.com/") }, 1000)
    }

    private fun readWateenFranchiseBalance(attempt: Int = 1) {
        Log.d(TAG, "readWateenFranchiseBalance attempt $attempt")
        webView.evaluateJavascript(
            "(function(){ var result = {found:false,value:'',url:window.location.href}; var links = document.querySelectorAll('a[href*=\"accounting/mybalance\"]'); for(var i=0;i<links.length;i++){ var lt = (links[i].innerText || links[i].textContent || '').trim(); if(lt.toLowerCase().indexOf('my balance') === -1) continue; var card = links[i].closest('.card-stats') || links[i].closest('.card'); var valEl = card ? card.querySelector('span.h2') : null; if(valEl){ var v = (valEl.innerText || valEl.textContent || '').trim(); if(v){ return JSON.stringify({found:true,value:v}); } } } var body = document.body.innerText || document.body.textContent || ''; var m = body.match(/My\\s+Balance[\\s\\S]{0,120}?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)/i); if(m){ return JSON.stringify({found:true,value:m[1]}); } return JSON.stringify(result); })()"
        ) { raw ->
            val clean = raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            val balance = Regex("\"value\":\"(.*?)\"").find(clean)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            
            if (balance != null) {
                Log.d(TAG, "Franchise balance found: $balance")
                FranchiseBalanceManager.updateBalance("WATEEN", balance, targetZone) {
                    FranchiseBalanceManager.showUpdateNotification(this, "WATEEN", balance, targetZone)
                    FranchiseBalanceManager.checkAndNotifyLowBalance(this, "WATEEN", balance, targetZone)
                }
                setResult(RESULT_OK, Intent().apply { 
                    putExtra("checked_balance", balance)
                    putExtra("manual_action_success", true)
                })
                finish()
            } else if (attempt < 10) {
                webView.postDelayed({ readWateenFranchiseBalance(attempt + 1) }, 1000)
            } else {
                finishFailure("Could not find Wateen balance")
            }
        }
    }

    private fun markWateenAutoFailed(reason: String, clickedAlready: Boolean) {
        Log.e(TAG, "markWateenAutoFailed: $reason (clicked=$clickedAlready)")
        if (wateenManualStepMode) {
            wateenManualStepStage = (wateenManualStepStage - 1).coerceAtLeast(0)
            Toast.makeText(this, "Stopped: $reason. Tap 'Next Step' to retry.", Toast.LENGTH_LONG).show()
            return
        }
        wateenTopupSuccessConfirmed = true
        sourceTransactionId?.let { 
            if (it.isNotBlank()) {
                db.collection("dealerTransactions").document(it).update(mapOf(
                    "transferStatus" to "AUTO_FAILED",
                    "transferError" to reason
                ))
            }
        }
        finishFailure(reason)
    }

    private fun performVisualAutoResolve(searchSelector: String, successTag: String) {
        val custId = autoActivateCustomerId ?: return
        Log.d(TAG, "performVisualAutoResolve for $custId")
        webView.evaluateJavascript(
            "(function(){ var sb = document.querySelector('$searchSelector'); if(sb){ sb.value = '$custId'; sb.dispatchEvent(new Event('input',{bubbles:true})); return 'searching'; } return 'not_found'; })()"
        ) { res ->
            if (res.contains("searching")) {
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){ var bt = document.body.innerText; var tf = '$successTag' === '' || bt.indexOf('$successTag') > -1; if(bt.indexOf('$custId') > -1 && tf) return 'found'; return 'not_found'; })()"
                    ) { status ->
                        if (status.contains("found")) {
                            Log.d(TAG, "AutoResolve: customer found online")
                            complaintIdToResolve?.let { cId ->
                                val fb = FirebaseDatabase.getInstance()
                                fb.getReference("complaints").child(cId).updateChildren(mapOf(
                                    "status" to "Resolved",
                                    "resolvedTime" to System.currentTimeMillis(),
                                    "resolvedBy" to "System Visual Monitor",
                                    "is_system_resolved" to true
                                )).addOnSuccessListener {
                                    fb.getReference("resolvedComplaints").child(cId).setValue(true)
                                    finishSuccess()
                                }
                            }
                        }
                    }
                }, 4000)
            }
        }
    }

    private fun finishSuccess() {
        Log.d(TAG, "finishSuccess")
        setResult(RESULT_OK, Intent().apply { putExtra("manual_action_success", true) })
        finish()
    }

    private fun finishFailure(reason: String) {
        Log.e(TAG, "finishFailure: $reason")
        setResult(RESULT_OK, Intent().apply { 
            putExtra("manual_action_success", false)
            putExtra("error_reason", reason)
        })
        finish()
    }
}
