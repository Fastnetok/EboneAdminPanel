package com.example.eboneadminpanel

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject

class WebViewLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var loginDone = false
    private var zongDealerMode = false
    private var activeAccountName = ""
    private var selectedIsp = "EBONE"
    private var autoActivateCustomerId: String? = null
    private var eboneSubmitClicked = false
    private var transactionId: String? = null
    private val db by lazy { FirebaseFirestore.getInstance() }

    // NEW: "SUSPEND" | "ENABLE" | null (null = normal package activation,
    // the original SMS/manual-recharge flow this activity already did).
    // Passed in via intent extra "manual_action".
    private var manualAction: String? = null

    companion object {
        // Ebone: suspending/enabling a customer = changing their partner.ebill.pk
        // password to one of these two fixed values (confirmed by admin).
        private const val EBONE_SUSPEND_PASSWORD = "8888"
        private const val EBONE_ENABLE_PASSWORD = "1001"
    }

    private val PREFS_NAME = "ebill_accounts"
    private val KEY_ACCOUNTS = "accounts_json"
    private val KEY_ACTIVE = "active_account"
    private val WATEEN_PREFS = "wateen_accounts"
    private val ZONG_PREFS = "zong_accounts"

    // NEW: session cache specifically for ISP Panel Settings logins.
    // These credentials never had cookie reuse before — every complaint
    // creation re-ran the FULL login form fill, even when a still-valid
    // session existed. This cache fixes that: reuse the cookie until the
    // panel itself tells us the session expired (redirects to its login
    // page), at which point handlePageLoaded() already calls
    // tryAutoLogin() automatically.
    private val ISP_SESSION_PREFS = "isp_session_cookies"

    private fun getIspSessionCookie(isp: String): String {
        return securePrefs(ISP_SESSION_PREFS).getString(isp, "") ?: ""
    }

    private fun saveIspSessionCookie(isp: String, cookie: String) {
        securePrefs(ISP_SESSION_PREFS).edit().putString(isp, cookie).apply()
    }

    /** Called right after a successful login — if this login used ISP
     * Panel Settings credentials (not the old manual account store),
     * cache the resulting cookie so the NEXT complaint reuses the
     * session instead of logging in again. */
    private fun cacheIspSessionCookieIfApplicable(isp: String, domain: String) {
        val ispUsername = IspPanelSettingsActivity.getSavedUsername(this, isp)
        if (!ispUsername.isNullOrEmpty()) {
            val cookie = CookieManager.getInstance().getCookie(domain)
            if (!cookie.isNullOrEmpty()) {
                saveIspSessionCookie(isp, cookie)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) {
            webView.resumeTimers()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SpeechHelper.SPEECH_REQUEST_CODE && resultCode == RESULT_OK) {
            val spokenText = SpeechHelper.getResultFromIntent(data)
            if (spokenText.isNotEmpty()) {
                if (selectedIsp == "EBONE") {
                    webView.evaluateJavascript("""
                        (function(){
                            var form = document.querySelector('form.sidebar-form');
                            if(form){
                                var inp = form.querySelector('input[name="username"]');
                                if(inp){ inp.value = '$spokenText'; form.submit(); }
                            }
                        })()
                    """.trimIndent(), null)
                } else {
                    webView.evaluateJavascript("""
                        (function(){
                            var inp = document.querySelector('.dataTables_filter input');
                            if(inp){
                                inp.focus();
                                inp.value = '$spokenText';
                                inp.dispatchEvent(new Event('input',{bubbles:true}));
                                inp.dispatchEvent(new Event('keyup',{bubbles:true}));
                            }
                        })()
                    """.trimIndent(), null)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_login)

        window.statusBarColor = android.graphics.Color.parseColor("#1B5E20")
        window.decorView.systemUiVisibility = 0

        selectedIsp = intent.getStringExtra("selected_isp") ?: "EBONE"
        autoActivateCustomerId = intent.getStringExtra("auto_activate_customer_id")
        transactionId = intent.getStringExtra("transaction_id")
        manualAction = intent.getStringExtra("manual_action") // "SUSPEND" | "ENABLE" | null

        webView = findViewById(R.id.loginWebView)

        val switchButton = findViewById<Button>(R.id.accountSwitchButton)
        switchButton.setOnClickListener { showAccountListDialog() }

        val micButton = findViewById<android.widget.ImageButton>(R.id.micButton)
        micButton.setOnClickListener {
            if (SpeechHelper.isSpeechAvailable(this)) {
                SpeechHelper.startSpeechInput(this)
            } else {
                Toast.makeText(this, "Mic available nahi", Toast.LENGTH_SHORT).show()
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.saveFormData = true
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 6.0) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                CookieManager.getInstance().flush()
                if (selectedIsp == "ZONG") {
                    webView.evaluateJavascript(
                        "document.querySelectorAll('a[target=\"_blank\"]').forEach(function(a){a.removeAttribute('target');});",
                        null
                    )
                }
                handlePageLoaded(url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (selectedIsp == "EBONE" && url.contains("/clients/client/") && autoActivateCustomerId == null) {
                    webView.postDelayed({ fetchEboneCustomerDetails() }, 1500)
                } else if (selectedIsp == "EBONE" && url.contains("/clients/clientStats/")) {
                    webView.postDelayed({ clickEboneSubmitButton() }, 1500)
                } else if (selectedIsp == "WATEEN" && url.contains("/user/user/view/")) {
                    if (autoActivateCustomerId != null) {
                        webView.postDelayed({ onWateenProfileOpened() }, 1500)
                    } else {
                        webView.postDelayed({ fetchWateenCustomerDetails() }, 1500)
                    }
                } else if (selectedIsp == "ZONG" && url.contains("customer_portal.php")) {
                    webView.postDelayed({ onZongProfileOpened() }, 1500)
                }
                return false
            }
        }

        loadInitialPage()
    }

    private fun securePrefs(name: String): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            this, name, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getPrefsName() = when (selectedIsp) {
        "WATEEN" -> WATEEN_PREFS
        "ZONG"   -> ZONG_PREFS
        else     -> PREFS_NAME
    }

    private fun domainFor(isp: String) = when (isp) {
        "WATEEN" -> "https://panel.wateen.com"
        "ZONG"   -> "https://turbonet.zong.com.pk"
        else     -> "https://partner.ebill.pk"
    }

    private fun loginUrlFor(isp: String) = when (isp) {
        "WATEEN" -> "https://panel.wateen.com/auth.html"
        "ZONG"   -> "https://turbonet.zong.com.pk/login.php"
        else     -> "https://partner.ebill.pk/logincheck"
    }

    private fun clientsUrlFor(isp: String) = when (isp) {
        "WATEEN" -> "https://panel.wateen.com/user/user/all"
        "ZONG"   -> "https://turbonet.zong.com.pk/customers.php"
        else     -> "https://partner.ebill.pk/clients"
    }

    private fun loadInitialPage() {
        // PRIMARY: ISP Panel Settings credentials — reuse the saved
        // session cookie if we have one, instead of always re-logging in.
        // If the cookie has expired, the panel will redirect to its own
        // login page and handlePageLoaded() detects that and calls
        // tryAutoLogin() automatically — so this is always safe.
        val ispUsername = IspPanelSettingsActivity.getSavedUsername(this, selectedIsp)
        if (!ispUsername.isNullOrEmpty()) {
            val savedCookie = getIspSessionCookie(selectedIsp)
            if (savedCookie.isNotEmpty()) {
                CookieManager.getInstance().setCookie(domainFor(selectedIsp), savedCookie)
                CookieManager.getInstance().flush()
                webView.loadUrl(clientsUrlFor(selectedIsp))
            } else {
                webView.loadUrl(loginUrlFor(selectedIsp))
            }
            return
        }

        // FALLBACK: old per-ISP manual account store
        val accounts = loadAccounts()
        val active = securePrefs(getPrefsName()).getString(KEY_ACTIVE, "") ?: ""
        if (active.isNotEmpty() && accounts.has(active)) {
            activeAccountName = active
            val acc = accounts.getJSONObject(active)
            val cookie = acc.optString("cookie", "")
            if (cookie.isNotEmpty()) {
                CookieManager.getInstance().setCookie(domainFor(selectedIsp), cookie)
                CookieManager.getInstance().flush()
                // Do NOT set loginDone=true here — let handlePageLoaded confirm
                // whether the cookie is still valid. If expired, the panel will
                // redirect to login page and handlePageLoaded will call tryAutoLogin().
                webView.loadUrl(clientsUrlFor(selectedIsp))
            } else {
                // No cookie saved — go straight to login
                webView.loadUrl(loginUrlFor(selectedIsp))
            }
        } else {
            webView.loadUrl(loginUrlFor(selectedIsp))
        }
    }

    private fun loadAccounts(): JSONObject {
        val raw = securePrefs(getPrefsName()).getString(KEY_ACCOUNTS, "") ?: ""
        return if (raw.isEmpty()) JSONObject() else JSONObject(raw)
    }

    private fun saveAccounts(accounts: JSONObject) {
        securePrefs(getPrefsName()).edit().putString(KEY_ACCOUNTS, accounts.toString()).apply()
    }

    private fun setActiveAccount(name: String) {
        securePrefs(getPrefsName()).edit().putString(KEY_ACTIVE, name).apply()
        activeAccountName = name
    }

    private fun showAccountListDialog() {
        val accounts = loadAccounts()
        val names = accounts.keys().asSequence().toList()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_account_list, null)
        val listView = dialogView.findViewById<android.widget.ListView>(R.id.accountListView)
        val addButton = dialogView.findViewById<Button>(R.id.addAccountButton)
        val title = when (selectedIsp) {
            "WATEEN" -> "Wateen Accounts"
            "ZONG"   -> "Zong Accounts"
            else     -> "Ebone Accounts"
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title).setView(dialogView).setNegativeButton("Close", null).create()
        val adapter = object : ArrayAdapter<String>(this, R.layout.item_account_row, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_account_row, parent, false)
                val name = names[position]
                view.findViewById<TextView>(R.id.accountNameText).text = name
                view.findViewById<TextView>(R.id.accountStatusText).text = if (name == activeAccountName) "Active" else ""
                view.setOnClickListener { switchToAccount(name); dialog.dismiss() }
                view.findViewById<Button>(R.id.deleteAccountButton).setOnClickListener {
                    val updated = loadAccounts(); updated.remove(name); saveAccounts(updated)
                    Toast.makeText(this@WebViewLoginActivity, "Account removed", Toast.LENGTH_SHORT).show()
                    dialog.dismiss(); showAccountListDialog()
                }
                return view
            }
        }
        listView.adapter = adapter
        addButton.setOnClickListener { dialog.dismiss(); showAddAccountDialog() }
        dialog.show()
    }

    private fun showAddAccountDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val nameInput = EditText(this).apply { hint = "Account name (e.g. Akmal)" }
        val ispLabel = when (selectedIsp) { "WATEEN" -> "Wateen"; "ZONG" -> "Zong"; else -> "ebill.pk" }
        val userInput = EditText(this).apply { hint = "$ispLabel username" }
        val passInput = EditText(this).apply {
            hint = "$ispLabel password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(nameInput); layout.addView(userInput); layout.addView(passInput)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add new account").setView(layout)
            .setPositiveButton("Save and login") { _, _ ->
                val accName = nameInput.text.toString().trim()
                val username = userInput.text.toString().trim()
                val password = passInput.text.toString().trim()
                if (accName.isEmpty() || username.isEmpty() || password.isEmpty()) {
                    Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val accounts = loadAccounts()
                accounts.put(accName, JSONObject().apply { put("username", username); put("password", password); put("cookie", "") })
                saveAccounts(accounts)
                activeAccountName = accName; setActiveAccount(accName)
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                loginDone = false
                webView.loadUrl(loginUrlFor(selectedIsp))
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun switchToAccount(name: String) {
        val accounts = loadAccounts()
        if (!accounts.has(name)) return
        val acc = accounts.getJSONObject(name)
        val cookie = acc.optString("cookie", "")
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        setActiveAccount(name)
        if (cookie.isNotEmpty()) {
            CookieManager.getInstance().setCookie(domainFor(selectedIsp), cookie)
            CookieManager.getInstance().flush()
            loginDone = true
            webView.loadUrl(clientsUrlFor(selectedIsp))
        } else {
            loginDone = false
            webView.loadUrl(loginUrlFor(selectedIsp))
        }
    }

    private fun handlePageLoaded(url: String) {
        when {
            selectedIsp == "EBONE" && (url.contains("logincheck") || url.contains("login")) -> {
                loginDone = false; tryAutoLogin()
            }
            selectedIsp == "WATEEN" && url.contains("auth.html") -> {
                loginDone = false; tryAutoLogin()
            }
            selectedIsp == "ZONG" && url.contains("login.php") -> {
                loginDone = false; tryAutoLogin()
            }
            // NEW: Ebone suspend/enable — password-change page loaded
            selectedIsp == "EBONE" && url.contains("/clients/clientChange/") && manualAction != null -> {
                fillEbonePasswordAndSubmit()
            }
            selectedIsp == "EBONE" && url.contains("/clients/clientStats/") -> {
                clickEboneSubmitButton()
            }
            selectedIsp == "EBONE" && url.contains("/clients/client/") && autoActivateCustomerId != null -> {
                val urlCustomerId = url
                    .substringAfterLast("/clients/client/")
                    .substringBefore("?")
                    .trim()
                    .trimEnd('/')  // Fix: trailing slash causes mismatch
                if (urlCustomerId != autoActivateCustomerId) {
                    android.util.Log.e("WebViewLoginActivity", "Customer ID mismatch — expected $autoActivateCustomerId, got $urlCustomerId. Aborting to avoid activating wrong customer.")
                } else if (eboneSubmitClicked) {
                    fetchEboneExpiryAndFinish()
                } else {
                    clickEboneActiveLink()
                }
            }
            selectedIsp == "EBONE" && url.contains("/clients/client/") -> {
                fetchEboneCustomerDetails()
            }
            selectedIsp == "WATEEN" && url.contains("panel.wateen.com") &&
                    url.contains("/user/user/view/") && autoActivateCustomerId != null -> {
                onWateenProfileOpened()
            }
            selectedIsp == "WATEEN" && url.contains("panel.wateen.com") && url.contains("/user/user/view/") -> {
                fetchWateenCustomerDetails()
            }
            selectedIsp == "ZONG" && url.contains("customer_portal.php") && autoActivateCustomerId != null && !zongDealerMode -> {
                onZongFranchiseProfileOpened()
            }
            selectedIsp == "ZONG" && url.contains("customer_portal.php") && autoActivateCustomerId != null && zongDealerMode -> {
                onZongProfileOpened()
            }
            selectedIsp == "ZONG" && url.contains("customer_portal.php") -> {
                fetchZongCustomerDetails()
            }
            selectedIsp == "EBONE" && url.contains("partner.ebill.pk") &&
                    !url.contains("/clients/client/") && !url.contains("/clients/clientStats/") &&
                    !url.contains("/clients/clientChange/") -> {
                loginDone = true
                saveCookieForCurrentAccount("https://partner.ebill.pk")
                cacheIspSessionCookieIfApplicable("EBONE", "https://partner.ebill.pk")
                android.widget.Toast.makeText(this, "Ebone: Logged in — URL: $url", android.widget.Toast.LENGTH_LONG).show()
                webView.evaluateJavascript(
                    "document.querySelectorAll('.modal,.modal-backdrop,.popup').forEach(function(el){el.style.display='none';});document.body.classList.remove('modal-open');", null
                )
                if (!url.contains("/clients")) {
                    webView.postDelayed({ webView.loadUrl("https://partner.ebill.pk/clients") }, 800)
                } else if (autoActivateCustomerId != null) {
                    webView.postDelayed({ searchEboneCustomer(autoActivateCustomerId!!) }, 800)
                }
            }
            selectedIsp == "WATEEN" && url.contains("panel.wateen.com") &&
                    !url.contains("auth.html") && !url.contains("/user/user/view/") -> {
                loginDone = true
                saveCookieForCurrentAccount("https://panel.wateen.com")
                cacheIspSessionCookieIfApplicable("WATEEN", "https://panel.wateen.com")
                if (!url.contains("/user/user/all")) {
                    webView.postDelayed({ webView.loadUrl("https://panel.wateen.com/user/user/all") }, 800)
                } else {
                    autoActivateCustomerId?.let { id -> webView.postDelayed({ searchWateenCustomer(id) }, 800) }
                }
            }
            selectedIsp == "ZONG" && url.contains("turbonet.zong.com.pk") &&
                    !url.contains("login.php") && !url.contains("customer_portal.php") -> {
                loginDone = true
                if (!zongDealerMode) {
                    saveCookieForCurrentAccount("https://turbonet.zong.com.pk")
                    cacheIspSessionCookieIfApplicable("ZONG", "https://turbonet.zong.com.pk")
                }
                if (!url.contains("customers.php")) {
                    webView.postDelayed({ webView.loadUrl("https://turbonet.zong.com.pk/customers.php") }, 800)
                } else {
                    autoActivateCustomerId?.let { id -> webView.postDelayed({ searchZongCustomer(id) }, 800) }
                }
            }
        }
    }

    private fun saveCookieForCurrentAccount(domain: String) {
        val cookie = CookieManager.getInstance().getCookie(domain)
        if (cookie != null && activeAccountName.isNotEmpty()) {
            val accounts = loadAccounts()
            val acc = if (accounts.has(activeAccountName)) accounts.getJSONObject(activeAccountName) else JSONObject()
            acc.put("cookie", cookie)
            accounts.put(activeAccountName, acc)
            saveAccounts(accounts)
            setActiveAccount(activeAccountName)
        }
    }

    private fun tryAutoLogin() {
        // PRIMARY: Check ISP Panel Settings (non-dealer accounts only)
        val ispUsername = IspPanelSettingsActivity.getSavedUsername(this, selectedIsp)
        val ispPassword = IspPanelSettingsActivity.getSavedPassword(this, selectedIsp)
        if (!ispUsername.isNullOrEmpty() && !ispPassword.isNullOrEmpty()) {
            doLoginWith(ispUsername, ispPassword)
            return
        }
        // FALLBACK: Old per-ISP account store
        if (activeAccountName.isEmpty()) return
        val accounts = loadAccounts()
        if (!accounts.has(activeAccountName)) return
        val acc = accounts.getJSONObject(activeAccountName)
        val username = acc.optString("username", "")
        val password = acc.optString("password", "")
        if (username.isEmpty() || password.isEmpty()) return
        doLoginWith(username, password)
    }

    private fun doLoginWith(username: String, password: String) {
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var u = document.querySelector('input[type=text],input[name=username],input[name=email],#username,#email');" +
                        "  var p = document.querySelector('input[type=password],#password');" +
                        "  var b = document.querySelector('#send,button[type=submit],input[type=submit],.btn-login,#login-btn');" +
                        "  if(u) u.value='" + username + "';" +
                        "  if(p) p.value='" + password + "';" +
                        "  if(u && p && b){ b.click(); return 'submitted'; }" +
                        "  if(b){ b.click(); return 'submitted via button only'; }" +
                        "  return 'not found';" +
                        "})()", null
            )
        }, 1200)
    }

    private fun writeActivationResultToFirestore(success: Boolean, expiry: String) {
        val actualSuccess = success && expiry.isNotBlank()
        transactionId?.let { txId ->
            db.collection("transactions").document(txId)
                .update("status", if (actualSuccess) "VERIFIED" else "FAILED")
        }
        if (actualSuccess) {
            autoActivateCustomerId?.let { custId ->
                db.collection("customers").document(custId)
                    .update(mapOf("activationStatus" to "ACTIVE", "lastPaymentDate" to System.currentTimeMillis(), "ispExpiryDate" to expiry))
            }
        }
    }

    // ===================== NEW: MANUAL SUSPEND / ENABLE =====================

    /**
     * Called once the SUSPEND/ENABLE action has actually gone through on
     * the ISP panel side (Ebone password changed, or Wateen/Zong
     * disable/enable link hit). Updates Firestore, tells the calling
     * screen it worked, and closes the WebView.
     */
    private fun finishManualActionSuccess() {
        val custId = autoActivateCustomerId
        if (custId != null) {
            val newStatus = if (manualAction == "SUSPEND") "DISABLED" else "ACTIVE"
            val updates = mutableMapOf<String, Any>("activationStatus" to newStatus)
            if (manualAction == "ENABLE") {
                // Re-enabling clears any pending grace deadline and resets
                // the billing cycle from right now.
                updates["lastPaymentDate"] = System.currentTimeMillis()
                updates["graceDeadline"] = FieldValue.delete()
            }
            db.collection("customers").document(custId).update(updates)
        }
        Toast.makeText(
            this,
            if (manualAction == "SUSPEND") "Customer suspended" else "Customer re-enabled",
            Toast.LENGTH_LONG
        ).show()
        setResult(RESULT_OK, Intent().apply { putExtra("manual_action_success", true) })
        finish()
    }

    private fun finishManualActionFailure(reason: String) {
        Toast.makeText(this, "Could not complete: $reason", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK, Intent().apply { putExtra("manual_action_success", false) })
        finish()
    }

    /** Ebone: fills the new-password field on /clients/clientChange/{id}
     * with the fixed suspend/enable code and submits. */
    private fun fillEbonePasswordAndSubmit() {
        val newPassword = if (manualAction == "SUSPEND") EBONE_SUSPEND_PASSWORD else EBONE_ENABLE_PASSWORD
        // Disable Chrome/WebView autofill on this page specifically — a
        // previously-saved password autofilling into the field AFTER our
        // script runs is the most likely reason the value doesn't stick.
        webView.settings.saveFormData = false
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var inputs = document.querySelectorAll('input[type=password]');" +
                        "  var filled = 0;" +
                        "  for (var i=0;i<inputs.length;i++){" +
                        "    inputs[i].removeAttribute('autocomplete');" +
                        "    inputs[i].setAttribute('autocomplete','off');" +
                        "    inputs[i].value = '$newPassword';" +
                        "    inputs[i].setAttribute('value','$newPassword');" +
                        "    inputs[i].dispatchEvent(new Event('input',{bubbles:true}));" +
                        "    inputs[i].dispatchEvent(new Event('change',{bubbles:true}));" +
                        "    inputs[i].dispatchEvent(new Event('blur',{bubbles:true}));" +
                        "    filled++;" +
                        "  }" +
                        "  return filled + '';" +
                        "})()"
            ) { filledCountRaw ->
                val filledCount = filledCountRaw.trim().removeSurrounding("\"").toIntOrNull() ?: 0
                if (filledCount == 0) {
                    finishManualActionFailure("Password field not found on Ebone page")
                    return@evaluateJavascript
                }
                // Small delay so the site's own JS (validation, autofill
                // re-check) settles before we click submit, then re-verify
                // the field still holds our value right before clicking.
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){" +
                                "  var inputs = document.querySelectorAll('input[type=password]');" +
                                "  for (var i=0;i<inputs.length;i++){" +
                                "    if (inputs[i].value !== '$newPassword'){" +
                                "      inputs[i].value = '$newPassword';" +
                                "      inputs[i].dispatchEvent(new Event('input',{bubbles:true}));" +
                                "    }" +
                                "  }" +
                                // Submit the FORM directly instead of clicking
                                // the button — form.submit() bypasses any
                                // client-side JS validation (e.g. a blocked
                                // click handler checking a confirm-password
                                // field), unlike button.click() which respects it.
                                "  var form = document.querySelector('form[action*=\"clientChange\"]') || document.querySelector('form');" +
                                "  if(form){ form.submit(); return 'submitted'; }" +
                                "  var b = document.querySelector('button[type=submit]');" +
                                "  if(b){ b.click(); return 'submitted-via-click'; }" +
                                "  return 'not found';" +
                                "})()"
                    ) { result ->
                        val clean = result.trim().removeSurrounding("\"")
                        if (clean == "submitted" || clean == "submitted-via-click") {
                            webView.postDelayed({ finishManualActionSuccess() }, 2000)
                        } else {
                            finishManualActionFailure("Submit button not found on Ebone page")
                        }
                    }
                }, 800)
            }
        }, 1200)
    }

    // ===================== WATEEN =====================

    private fun searchWateenCustomer(customerId: String) {
        webView.evaluateJavascript(
            "(function(){" +
                    "  var inp = document.querySelector('.dataTables_filter input');" +
                    "  if(inp){" +
                    "    inp.focus();" +
                    "    inp.value = '$customerId';" +
                    "    inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "    inp.dispatchEvent(new Event('keyup',{bubbles:true}));" +
                    "  }" +
                    "})()", null
        )
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var smalls = document.querySelectorAll('a small');" +
                        "  for(var i=0;i<smalls.length;i++){" +
                        "    if(smalls[i].innerText.trim() === '$customerId'){ return smalls[i].closest('a').href; }" +
                        "  }" +
                        "  var link = document.querySelector('#userListAll tbody tr td a');" +
                        "  if(link){ return link.href; }" +
                        "  return '';" +
                        "})()"
            ) { profileUrl ->
                val cleanUrl = profileUrl.trim().removeSurrounding("\"")
                if (cleanUrl.isNotEmpty() && cleanUrl.startsWith("http")) {
                    webView.loadUrl(cleanUrl)
                }
            }
        }, 1500)
    }

    private fun onWateenProfileOpened() {
        // NEW: manual SUSPEND/ENABLE — find the "Disable Net"/"Enable Net"
        // link on the profile and navigate straight to its href. The
        // jconfirm popup is a client-side UI safety layer on the anchor's
        // click handler only — the href itself is the real action URL, so
        // loading it directly performs the disable/enable with no popup.
        if (manualAction != null) {
            val selector = if (manualAction == "SUSPEND")
                "a.disable-user-connection" else "a.enable-user-connection"
            webView.evaluateJavascript(
                "(function(){" +
                        "  var link = document.querySelector('$selector');" +
                        "  if(link){ return link.href; }" +
                        "  return '';" +
                        "})()"
            ) { hrefRaw ->
                val href = hrefRaw.trim().removeSurrounding("\"")
                if (href.isNotEmpty() && href.startsWith("http")) {
                    webView.postDelayed({ webView.loadUrl(href) }, 300)
                    webView.postDelayed({ finishManualActionSuccess() }, 2500)
                } else {
                    finishManualActionFailure("Disable/Enable link not found on Wateen profile")
                }
            }
            return
        }

        webView.evaluateJavascript(
            "(function(){" +
                    "  var spans = document.querySelectorAll('span.btn-warning');" +
                    "  for (var i=0;i<spans.length;i++){" +
                    "    if (spans[i].innerText.indexOf('Renew') > -1){ spans[i].click(); return 'clicked'; }" +
                    "  }" +
                    "  return 'not found';" +
                    "})()", null
        )
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var buttons = document.querySelectorAll('button[type=submit]');" +
                        "  for (var i=0;i<buttons.length;i++){" +
                        "    if (buttons[i].innerText.indexOf('Active User') > -1){ buttons[i].click(); return 'clicked'; }" +
                        "  }" +
                        "  return 'not found';" +
                        "})()", null
            )
        }, 1500)
        webView.postDelayed({ fetchWateenExpiryAndFinish() }, 3500)
    }

    private fun fetchWateenExpiryAndFinish() {
        val script = """
            (function(){
                var expiry = '';
                var el = document.querySelector('abbr[title="Expiry Date/Time"] strong');
                if (el) { expiry = (el.textContent || el.innerText || '').trim(); }
                return JSON.stringify({expiry:expiry});
            })()
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            try {
                val clean = result.removeSurrounding("\"").replace("\\\"", "\"")
                val expiry = Regex("\"expiry\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                if (expiry.isNotBlank()) {
                    Toast.makeText(this, "Wateen activated ✅ Expiry: $expiry", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Wateen: Activation may have failed — expiry not found", Toast.LENGTH_LONG).show()
                }
                writeActivationResultToFirestore(true, expiry)
                val resultIntent = Intent().apply { putExtra("activation_success", expiry.isNotBlank()); putExtra("new_expiry_date", expiry) }
                setResult(RESULT_OK, resultIntent)
            } catch (e: Exception) {
                writeActivationResultToFirestore(false, "")
                setResult(RESULT_OK, Intent().apply { putExtra("activation_success", false) })
            } finally { finish() }
        }
    }

    // ===================== EBONE =====================

    private fun searchEboneCustomer(customerId: String) {
        // NEW: manual SUSPEND/ENABLE — skip straight to the password-change
        // page instead of the normal client profile / renew flow.
        if (manualAction != null) {
            webView.loadUrl("https://partner.ebill.pk/clients/clientChange/$customerId")
            return
        }
        // Direct URL — fastest and most reliable approach.
        // If "not found" is returned, it means the customer ID
        // is wrong/mistyped in Firestore (or doesn't exist in panel).
        webView.loadUrl("https://partner.ebill.pk/clients/client/$customerId")
    }

    private fun clickEboneActiveLink() {
        webView.evaluateJavascript(
            "(function(){" +
                    "  var link = document.querySelector('a[href*=\"/clientStats/\"]');" +
                    "  if(link){ link.click(); return 'clicked'; }" +
                    "  return 'not found';" +
                    "})()", null
        )
    }

    private fun clickEboneSubmitButton() {
        eboneSubmitClicked = true
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var btn = document.querySelector('button[type=submit].btn-default');" +
                        "  if(!btn){" +
                        "    var all = document.querySelectorAll('button[type=submit]');" +
                        "    for (var i=0;i<all.length;i++){ if(all[i].innerText.indexOf('Submit') > -1){ btn = all[i]; break; } }" +
                        "  }" +
                        "  if(btn){ btn.click(); return 'submitted'; }" +
                        "  return 'not found';" +
                        "})()", null
            )
        }, 800)
    }

    private fun fetchEboneExpiryAndFinish() {
        val script = """
            (function(){
                var expiry = '';
                var rows = document.querySelectorAll('table.table-hover tbody tr');
                for (var i=0; i<rows.length; i++){
                    var th = rows[i].querySelector('th');
                    if (th && th.innerText.indexOf('Expiry') > -1){
                        var cells = rows[i].querySelectorAll('td');
                        if (cells.length > 0){ expiry = (cells[cells.length - 1].textContent || '').trim(); }
                        break;
                    }
                }
                return JSON.stringify({expiry:expiry});
            })()
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            try {
                val clean = result.removeSurrounding("\"").replace("\\\"", "\"")
                val expiry = Regex("\"expiry\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                writeActivationResultToFirestore(expiry.isNotEmpty(), expiry)
                setResult(RESULT_OK, Intent().apply { putExtra("activation_success", expiry.isNotEmpty()); putExtra("new_expiry_date", expiry) })
            } catch (e: Exception) {
                setResult(RESULT_OK, Intent().apply { putExtra("activation_success", false) })
            } finally { finish() }
        }
    }

    // ===================== ZONG: FRANCHISE SEARCH =====================

    private fun searchZongCustomer(customerId: String) {
        webView.evaluateJavascript(
            "(function(){" +
                    "  var inp = document.querySelector('input[aria-controls=\"managercustomers\"],.dataTables_filter input');" +
                    "  if(inp){" +
                    "    inp.focus();" +
                    "    inp.value = '$customerId';" +
                    "    inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "    inp.dispatchEvent(new Event('keyup',{bubbles:true}));" +
                    "  }" +
                    "})()", null
        )
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var links = document.querySelectorAll('a[href*=\"customer_portal.php\"]');" +
                        "  for(var i=0;i<links.length;i++){" +
                        "    if(links[i].innerText.trim()==='$customerId'){ return links[i].href; }" +
                        "  }" +
                        "  if(links.length>0){ return links[0].href; }" +
                        "  return '';" +
                        "})()"
            ) { profileUrl ->
                val cleanUrl = profileUrl.trim().removeSurrounding("\"")
                if (cleanUrl.isNotEmpty() && cleanUrl.startsWith("http")) {
                    webView.loadUrl(cleanUrl)
                }
            }
        }, 1500)
    }

    // ===================== ZONG: FRANCHISE PROFILE - GET DEALER =====================

    private fun onZongFranchiseProfileOpened() {
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var strongs = document.querySelectorAll('td strong');" +
                        "  if(strongs.length>0){ return strongs[0].innerText.trim(); }" +
                        "  return '';" +
                        "})()"
            ) { dealerNameRaw ->
                val dealerName = dealerNameRaw.trim().removeSurrounding("\"")
                if (dealerName.isNotEmpty()) {
                    switchToZongDealerPanel(dealerName)
                } else {
                    Toast.makeText(this, "Zong: Dealer name not found on profile", Toast.LENGTH_LONG).show()
                    writeActivationResultToFirestore(false, ""); finish()
                }
            }
        }, 1000)
    }

    // ===================== ZONG: SWITCH TO DEALER PANEL =====================

    private fun switchToZongDealerPanel(dealerName: String) {
        val prefs = EncryptedSharedPreferences.create(
            this, "isp_panel_prefs",
            MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val accountsJson = prefs.getString("all_accounts_json", null)
        if (accountsJson.isNullOrEmpty()) {
            Toast.makeText(this, "No saved accounts — add dealer in ISP Panel Settings", Toast.LENGTH_LONG).show()
            writeActivationResultToFirestore(false, ""); finish(); return
        }
        try {
            val arr = org.json.JSONArray(accountsJson)
            var dealerUsername: String? = null
            var dealerPassword: String? = null
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optBoolean("isDealer") && obj.optString("isp") == "ZONG" &&
                    obj.optString("dealerName").equals(dealerName, ignoreCase = true)) {
                    dealerUsername = obj.getString("username")
                    dealerPassword = obj.getString("password")
                    break
                }
            }
            if (dealerUsername == null || dealerPassword == null) {
                Toast.makeText(this, "Dealer \"$dealerName\" not found in ISP Panel Settings", Toast.LENGTH_LONG).show()
                writeActivationResultToFirestore(false, ""); finish(); return
            }
            val accounts = loadAccounts()
            accounts.put("__dealer__$dealerName", JSONObject().apply {
                put("username", dealerUsername); put("password", dealerPassword); put("cookie", "")
            })
            saveAccounts(accounts)
            activeAccountName = "__dealer__$dealerName"
            setActiveAccount("__dealer__$dealerName")
            zongDealerMode = true
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            loginDone = false
            webView.loadUrl("https://turbonet.zong.com.pk/login.php")
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            writeActivationResultToFirestore(false, ""); finish()
        }
    }

    // ===================== ZONG: DEALER PANEL - ACTIVATE / SUSPEND / ENABLE =====================

    private fun onZongProfileOpened() {
        // NEW: manual SUSPEND/ENABLE — find the actionx=Disable/Enable
        // link that's currently visible on the profile (only one of the
        // two is shown at a time, depending on current status) and
        // navigate to it directly. The token in the URL is generated
        // fresh per page load, so it must be read live — never hardcoded.
        if (manualAction != null) {
            webView.evaluateJavascript(
                "(function(){" +
                        "  var links = document.querySelectorAll('a[href*=\"actionx=\"]');" +
                        "  for (var i=0;i<links.length;i++){" +
                        "    if (links[i].href.indexOf('actionx=Disable') > -1 || links[i].href.indexOf('actionx=Enable') > -1){" +
                        "      return links[i].href;" +
                        "    }" +
                        "  }" +
                        "  return '';" +
                        "})()"
            ) { hrefRaw ->
                val href = hrefRaw.trim().removeSurrounding("\"")
                if (href.isNotEmpty() && href.startsWith("http")) {
                    // Whatever the link currently says (Disable or Enable)
                    // reflects the customer's CURRENT state and toggles it —
                    // matches what we asked for either way, since a
                    // suspended customer only shows an Enable link and
                    // vice versa.
                    webView.postDelayed({ webView.loadUrl(href) }, 300)
                    webView.postDelayed({ finishManualActionSuccess() }, 2500)
                } else {
                    finishManualActionFailure("Disable/Enable link not found on Zong profile")
                }
            }
            return
        }

        if (autoActivateCustomerId != null) {
            webView.evaluateJavascript(
                "(function(){" +
                        "  var btn = document.querySelector('[data-target^=\"#recharge\"]');" +
                        "  if(btn){ btn.click(); return 'clicked'; }" +
                        "  return 'not found';" +
                        "})()", null
            )
            webView.postDelayed({
                webView.evaluateJavascript(
                    "(function(){" +
                            "  var btn = document.querySelector('#saferecharge');" +
                            "  if(btn){ btn.click(); return 'clicked'; }" +
                            "  return 'not found';" +
                            "})()", null
                )
            }, 1500)
            webView.postDelayed({ fetchZongExpiryAndFinish() }, 4000)
        } else {
            fetchZongCustomerDetails()
        }
    }

    private fun fetchZongExpiryAndFinish() {
        val script = """
            (function(){
                var expiry = '';
                var tiles = document.querySelectorAll('.col-md-4');
                for (var i=0; i<tiles.length; i++){
                    var title = tiles[i].querySelector('.title');
                    if (title && title.innerText.indexOf('Expiration') > -1){
                        var val = tiles[i].querySelector('.counter');
                        if (val) expiry = (val.textContent || '').trim();
                        break;
                    }
                }
                return JSON.stringify({expiry:expiry});
            })()
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            try {
                val clean = result.removeSurrounding("\"").replace("\\\"", "\"")
                val expiry = Regex("\"expiry\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                writeActivationResultToFirestore(expiry.isNotEmpty(), expiry)
                setResult(RESULT_OK, Intent().apply { putExtra("activation_success", expiry.isNotEmpty()); putExtra("new_expiry_date", expiry) })
            } catch (e: Exception) {
                setResult(RESULT_OK, Intent().apply { putExtra("activation_success", false) })
            } finally { finish() }
        }
    }

    private var zongDetailsFetchDone = false

    private fun fetchZongCustomerDetails(attempt: Int = 1) {
        if (zongDetailsFetchDone) return
        val script = """
            (function(){
                var userId = '', address = '', phone = '', expiry = '';
                var tables = document.querySelectorAll('table.skills');
                for (var t=0; t<tables.length; t++){
                    var rows = tables[t].querySelectorAll('tbody tr');
                    var hasFullName = false;
                    for (var i=0; i<rows.length; i++){
                        var chk = rows[i].querySelector('td.item');
                        if (chk && chk.textContent.trim() === 'Full Name') { hasFullName = true; break; }
                    }
                    if (!hasFullName) continue;
                    for (var i=0; i<rows.length; i++){
                        var itemTd = rows[i].querySelector('td.item');
                        if (!itemTd) continue;
                        var label = itemTd.textContent.trim();
                        var cells = rows[i].querySelectorAll('td');
                        var valueTd = cells[cells.length - 1];
                        var value = valueTd ? valueTd.textContent.trim() : '';
                        if (label === 'PPPoE Auth User') { userId = value; }
                        else if (label === 'Address') { address = value; }
                        else if (label === 'Mobile') { phone = value; }
                    }
                    break;
                }
                var tiles = document.querySelectorAll('.col-md-4');
                for (var j=0; j<tiles.length; j++){
                    var title = tiles[j].querySelector('.title');
                    if (title && title.innerText.indexOf('Expiration') > -1){
                        var val = tiles[j].querySelector('.counter');
                        if (val) expiry = (val.textContent || '').trim();
                    }
                }
                return JSON.stringify({userId:userId, address:address, phone:phone, expiry:expiry});
            })()
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            try {
                val clean = result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                val userId = Regex("\"userId\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val address = Regex("\"address\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val phone = normalizePakPhone(Regex("\"phone\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: "")

                // The Bio-Data table (Address/Mobile) can render slightly
                // after the initial page paint (User ID area loads first).
                // Retry a few times before settling for whatever we have,
                // instead of finishing early with incomplete data.
                val incomplete = address.isEmpty() || phone.isEmpty()
                if (incomplete && attempt < 4) {
                    webView.postDelayed({ fetchZongCustomerDetails(attempt + 1) }, 1200)
                    return@evaluateJavascript
                }

                if (!zongDetailsFetchDone && (userId.isNotEmpty() || address.isNotEmpty() || phone.isNotEmpty())) {
                    zongDetailsFetchDone = true
                    setResult(RESULT_OK, Intent().apply {
                        putExtra("fetched_user_id", userId)
                        putExtra("fetched_address", address)
                        putExtra("fetched_phone", phone)
                    })
                    finish()
                }
            } catch (e: Exception) {}
        }
    }

    private fun fetchEboneCustomerDetails() {
        val script = """
            (function(){
                var userId = '', address = '', phone = '';
                var rows = document.querySelectorAll('table.table-hover tbody tr');
                for (var k=0; k<rows.length; k++){
                    var thFirst = rows[k].querySelector('th');
                    if (thFirst && thFirst.innerText.indexOf('UserID') > -1){
                        var tdFirst = rows[k].querySelector('td');
                        if (tdFirst){ userId = (tdFirst.textContent || '').trim(); }
                        break;
                    }
                }
                for (var i=0; i<rows.length; i++){
                    var th = rows[i].querySelector('th');
                    if (!th) continue;
                    if (th.innerText.indexOf('Address') > -1 && th.innerText.indexOf('Email') === -1){
                        var allCells = rows[i].children;
                        if (allCells.length >= 2){ address = (allCells[1].textContent || '').trim(); }
                        if (allCells.length >= 3){ phone = (allCells[2].textContent || '').trim().replace(new RegExp('^/+'), ''); }
                    }
                }
                return JSON.stringify({userId:userId, address:address, phone:phone});
            })()
        """.trimIndent()
        webView.evaluateJavascript(script) { result -> handleFetchResult(result) }
    }

    private fun fetchWateenCustomerDetails() {
        val script = """
            (function(){
                var userId = '', address = '', phone = '';
                var usernameEl = document.querySelector('.h5.font-weight-300');
                if (usernameEl){ userId = (usernameEl.innerText || '').replace('@','').trim(); }
                var addressEl = document.querySelector('.h5.mt-4');
                if (addressEl){ address = (addressEl.textContent || '').trim().replace(/[\r\n\t]+/g, ' ').replace(/\s+/g, ' ').trim(); }
                var listItems = document.querySelectorAll('.list-group-item');
                for (var i=0; i<listItems.length; i++){
                    var icon = listItems[i].querySelector('i');
                    if (!icon) continue;
                    var text = (listItems[i].innerText || '').trim();
                    if ((icon.className.indexOf('fa-mobile-alt') > -1 || icon.className.indexOf('fa-phone') > -1) && phone === ''){ phone = text; }
                }
                if (userId === ''){
                    var urlParts = window.location.href.split('/');
                    for (var j=0; j<urlParts.length; j++){
                        if (urlParts[j] === 'view' && urlParts[j+1]){ userId = urlParts[j+1].replace(new RegExp('/','g'),''); }
                    }
                }
                return JSON.stringify({userId:userId, address:address, phone:phone});
            })()
        """.trimIndent()
        webView.evaluateJavascript(script) { result -> handleFetchResult(result) }
    }

    /** Converts "+923034507600" → "03034507600" (Pakistan local format). */
    private fun normalizePakPhone(raw: String): String {
        var p = raw.trim().replace(" ", "").replace("-", "")
        return when {
            p.startsWith("+92") -> "0" + p.substring(3)
            p.startsWith("92") && p.length > 10 -> "0" + p.substring(2)
            else -> p
        }
    }

    private fun handleFetchResult(result: String) {
        try {
            val clean = result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            val userId = Regex("\"userId\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
            val address = Regex("\"address\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
            val phone = normalizePakPhone(Regex("\"phone\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: "")
            if (userId.isNotEmpty() || address.isNotEmpty() || phone.isNotEmpty()) {
                setResult(RESULT_OK, Intent().apply {
                    putExtra("fetched_user_id", userId)
                    putExtra("fetched_address", address)
                    putExtra("fetched_phone", phone)
                })
                finish()
            }
        } catch (e: Exception) {}
    }
}