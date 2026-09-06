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
    private var loginAttemptInProgress = false
    private var zongDealerMode = false
    private var activeAccountName = ""
    private var selectedIsp = "EBONE"
    private var autoActivateCustomerId: String? = null
    private var eboneSubmitClicked = false
    // NEW: guards against re-capturing the password if the panel
    // redirects back to /clients/client/{id} after a successful password
    // change (e.g. as a "success -> back to profile" pattern). Without
    // this, a second landing on the profile page would read the
    // ALREADY-CHANGED password and overwrite the correct original one
    // saved in Firestore — which is exactly what caused ENABLE to
    // restore the wrong (new) password instead of the real original.
    private var eboneSuspendCaptureDone = false
    // NEW: guards ZONG's SUSPEND/ENABLE click so it only ever fires once
    // per session. Without this, the action link's redirect back to the
    // same customer_portal.php page (after a successful click) was being
    // treated as a fresh request, finding the NOW-OPPOSITE action link
    // (since the status just flipped) and clicking THAT too — silently
    // undoing the action it had just performed (disable -> re-enable, or
    // enable -> re-disable).
    private var zongActionClicked = false
    private var transactionId: String? = null
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var manualAction: String? = null

    private var eboneOriginalPassword: String? = null

    private var dealerEboneId: String? = null
    private var topupAmount: String? = null
    private var eboneTopupSubmitAttempted = false
    private var eboneTopupSubmitAttempt = 0
    private var eboneTopupVerificationAttempt = 0
    private var eboneTopupSuccessConfirmed = false
    private var eboneBalanceCheckAttempted = false

    private var wateenDealerListLoadAttempted = false
    private var wateenDealerSearchAttempted = false
    private var wateenTopupSubmitAttempted = false
    private var wateenBalanceCheckAttempted = false
    private var wateenBalanceReadAttempt = 0

    private var zongDealerListLoadAttempted = false
    private var zongTopupSubmitAttempted = false
    private var zongBalanceCheckAttempted = false

    private var dealerInternalId: String? = null
    private var dealerDisplayName: String? = null

    private var dealerSearchName: String? = null

    private var targetZone: String = "Okara"

    private var sourceTransactionId: String? = null

    private var debugTapInspectorEnabled = false

    // NEW: when set (via the "dealer_account_name" intent extra), the
    // WebView must log in using this SPECIFIC named dealer account saved
    // in IspPanelSettingsActivity (isDealer=true, dealerName matches) —
    // never the Franchise account, even if one exists for this ISP+zone.
    private var forceAccountName: String? = null
    private var forcedDealerUsername: String? = null
    private var forcedDealerPassword: String? = null

    // NEW: base URL taken directly from the Unpaid Package Activation
    // screen's URL field (e.g. "https://partner.ebill.pk"). When present,
    // this is used as the base for building the customer's profile URL
    // instead of WebViewLoginActivity's own hardcoded domain — this is
    // what makes "whatever is typed in Search Customer gets appended
    // straight onto this URL" actually work.
    private var customerUrlOverride: String? = null

    private var zongNavigationStep = 0
    private var zongLastTrailUrl = ""

    companion object {
        // TEMPORARY TEST VALUE — set to "Tetra9" at the admin's explicit
        // request, to visually confirm the capture-save-and-change flow
        // actually writes a NEW password onto the real panel. Revert to
        // "8888" once this test is confirmed and before relying on the
        // real automatic Phase 7 disable flow.
        private const val EBONE_SUSPEND_PASSWORD = "Tetra9"
        private const val EBONE_ENABLE_PASSWORD = "1001"
    }

    private val PREFS_NAME = "ebill_accounts"
    private val KEY_ACCOUNTS = "accounts_json"
    private val KEY_ACTIVE = "active_account"
    private val WATEEN_PREFS = "wateen_accounts"
    private val ZONG_PREFS = "zong_accounts"

    private val ISP_SESSION_PREFS = "isp_session_cookies"

    private fun getIspSessionCookie(isp: String): String {
        return securePrefs(ISP_SESSION_PREFS).getString("${isp}_$targetZone", "") ?: ""
    }

    private fun saveIspSessionCookie(isp: String, cookie: String) {
        securePrefs(ISP_SESSION_PREFS).edit().putString("${isp}_$targetZone", cookie).apply()
    }

    private fun cacheIspSessionCookieIfApplicable(isp: String, domain: String) {
        val ispUsername = IspPanelSettingsActivity.getSavedUsername(this, isp, targetZone)
        if (!ispUsername.isNullOrEmpty()) {
            val cookie = CookieManager.getInstance().getCookie(domain)
            if (!cookie.isNullOrEmpty()) {
                saveIspSessionCookie(isp, cookie)
            }
        }
    }

    private fun clearCookiesForDomain(domain: String) {
        val existing = CookieManager.getInstance().getCookie(domain) ?: return
        val cookieNames = existing.split(";")
            .map { it.trim().substringBefore("=") }
            .filter { it.isNotBlank() }
        for (name in cookieNames) {
            CookieManager.getInstance().setCookie(domain, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
        }
        CookieManager.getInstance().flush()
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
        manualAction = intent.getStringExtra("manual_action")
        dealerEboneId = intent.getStringExtra("dealer_ebone_id")
        topupAmount = intent.getStringExtra("topup_amount")
        dealerInternalId = intent.getStringExtra("dealer_internal_id")
        dealerDisplayName = intent.getStringExtra("dealer_display_name")
        sourceTransactionId = intent.getStringExtra("source_transaction_id")
        dealerSearchName = intent.getStringExtra("dealer_search_name")
        targetZone = intent.getStringExtra("target_zone")?.ifBlank { null } ?: "Okara"

        forceAccountName = intent.getStringExtra("dealer_account_name")
        customerUrlOverride = intent.getStringExtra("customer_url")?.trim()?.takeIf { it.isNotBlank() }

        debugTapInspectorEnabled = intent.getBooleanExtra("debug_tap_inspector", false)

        webView = findViewById(R.id.loginWebView)

        val switchButton = findViewById<Button>(R.id.accountSwitchButton)
        switchButton.setOnClickListener { showAccountListDialog() }

        val micButton = findViewById<android.widget.ImageButton>(R.id.micButton)
        micButton.setOnClickListener {
            if (SpeechHelper.isSpeechAvailable(this)) {
                SpeechHelper.startSpeechInput(this)
            } else {
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

        if (debugTapInspectorEnabled) {
            webView.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onTap(info: String) {
                    runOnUiThread {
                        android.util.Log.d("TapInspector", info)
                    }
                }
            }, "TapInspector")
        }

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
                    }, 600)
                }
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

        if (!forceAccountName.isNullOrBlank() && selectedIsp == "EBONE") {
            val dealerUsername = IspPanelSettingsActivity.getDealerUsername(
                this, selectedIsp, targetZone, forceAccountName!!
            )
            val dealerPassword = IspPanelSettingsActivity.getDealerPassword(
                this, selectedIsp, targetZone, forceAccountName!!
            )

            if (!dealerUsername.isNullOrEmpty() && !dealerPassword.isNullOrEmpty()) {
                forcedDealerUsername = dealerUsername
                forcedDealerPassword = dealerPassword

                // CRITICAL: EBONE Franchise and EBONE dealer accounts share
                // the exact same domain (partner.ebill.pk). If a Franchise
                // session cookie is currently live for that domain (from
                // any earlier use of this app), simply loading the login
                // page could silently reuse that old Franchise session
                // instead of showing a fresh login for this dealer. Clear
                // the domain's cookies first so a real, fresh login as
                // this specific dealer always happens.
                clearCookiesForDomain(domainFor(selectedIsp))
                loginDone = false
                webView.loadUrl(loginUrlFor(selectedIsp))
            } else {
                Toast.makeText(
                    this,
                    "Dealer account \"$forceAccountName\" not found for $selectedIsp/$targetZone. Add it first via ISP Panel Settings → Add Dealer.",
                    Toast.LENGTH_LONG
                ).show()
                loadInitialPage()
            }
        } else {
            loadInitialPage()
        }
    }

    private fun securePrefs(name: String): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return try {
            EncryptedSharedPreferences.create(
                this, name, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.e("WebViewLoginActivity", "Corrupted encrypted prefs '$name' — recreating fresh", e)
            getSharedPreferences(name, MODE_PRIVATE).edit().clear().commit()
            deleteSharedPreferences(name)
            EncryptedSharedPreferences.create(
                this, name, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
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
        val ispUsername = IspPanelSettingsActivity.getSavedUsername(this, selectedIsp, targetZone)

        if (!ispUsername.isNullOrEmpty()) {
            val savedCookie = getIspSessionCookie(selectedIsp)
            clearCookiesForDomain(domainFor(selectedIsp))

            if (savedCookie.isNotEmpty()) {
                CookieManager.getInstance().setCookie(domainFor(selectedIsp), savedCookie)
                CookieManager.getInstance().flush()
                webView.loadUrl(clientsUrlFor(selectedIsp))
            } else {
                webView.loadUrl(loginUrlFor(selectedIsp))
            }
            return
        }

        if (!targetZone.equals("Okara", ignoreCase = true)) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            webView.loadUrl(loginUrlFor(selectedIsp))
            return
        }

        val accounts = loadAccounts()
        val active = securePrefs(getPrefsName()).getString(KEY_ACTIVE, "") ?: ""
        if (active.isNotEmpty() && accounts.has(active)) {
            activeAccountName = active
            val acc = accounts.getJSONObject(active)
            val cookie = acc.optString("cookie", "")
            if (cookie.isNotEmpty()) {
                CookieManager.getInstance().setCookie(domainFor(selectedIsp), cookie)
                CookieManager.getInstance().flush()
                webView.loadUrl(clientsUrlFor(selectedIsp))
            } else {
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

    private fun recordZongNavigationTrail(url: String) {
        zongNavigationStep++
        zongLastTrailUrl = url

        val username = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", targetZone) ?: ""
        val step = zongNavigationStep
        val zone = targetZone

        android.util.Log.d(
            "ZongNavTrail",
            "STEP $step | zone=$zone | user=$username | attempted=$zongBalanceCheckAttempted | URL=$url"
        )

        webView.evaluateJavascript(
            "(function(){" +
                    "var t=(document.title||'').trim();" +
                    "var state=document.readyState||'';" +
                    "var body=(document.body && (document.body.innerText||document.body.textContent))||'';" +
                    "body=body.replace(/\\s+/g,' ').trim();" +
                    "var hasAvailable=/Available\\s+Credit/i.test(body);" +
                    "var hasAccountCredit=/Account\\s+Credit/i.test(body);" +
                    "return JSON.stringify({title:t,state:state,hasAvailableCredit:hasAvailable,hasAccountCredit:hasAccountCredit,snippet:body.substring(0,500),href:window.location.href});" +
                    "})()"
        ) { raw ->
            val clean = raw
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\\\", "\\")

            android.util.Log.d(
                "ZongNavTrail",
                "STEP $step DETAILS | zone=$zone | URL=$url | page=$clean"
            )

            val shortSnippet = Regex("\"snippet\":\"(.*?)\"")
                .find(clean)?.groupValues?.get(1)?.take(180).orEmpty()

        }
    }

    private fun handlePageLoaded(url: String) {
        if (selectedIsp == "ZONG" && manualAction == "CHECK_BALANCE") {
            recordZongNavigationTrail(url)
        }
        when {
            selectedIsp == "EBONE" && (url.contains("logincheck") || url.contains("login")) -> {
                loginDone = false; loginAttemptInProgress = false; tryAutoLogin()
            }
            selectedIsp == "WATEEN" && url.contains("auth.html") -> {
                loginDone = false; loginAttemptInProgress = false; tryAutoLogin()
            }
            selectedIsp == "ZONG" && url.contains("login.php") -> {
                loginDone = false; loginAttemptInProgress = false; tryAutoLogin()
            }
            selectedIsp == "EBONE" && url.contains("/clients/clientChange/") && manualAction != null -> {
                prepareEbonePasswordAction()
            }
            selectedIsp == "EBONE" && url.contains("/payments/addbalance/") && manualAction == "DEALER_TOPUP" -> {
                if (eboneTopupSubmitAttempted && !eboneTopupSuccessConfirmed) {
                    verifyEboneDealerTopupResult(topupAmount ?: "", eboneTopupVerificationAttempt)
                } else if (!eboneTopupSubmitAttempted && !eboneTopupSuccessConfirmed) {
                    fillDealerTopupAmountAndSubmit()
                }
            }
            selectedIsp == "EBONE" && url.contains("/clients/clientStats/") -> {
                clickEboneSubmitButton()
            }
            selectedIsp == "EBONE" && url.contains("/clients/client/") && autoActivateCustomerId != null -> {
                val urlCustomerId = url
                    .substringAfterLast("/clients/client/")
                    .substringBefore("?")
                    .trim()
                    .trimEnd('/')
                if (urlCustomerId != autoActivateCustomerId) {
                    android.util.Log.e("WebViewLoginActivity", "Customer ID mismatch — expected $autoActivateCustomerId, got $urlCustomerId. Aborting to avoid activating wrong customer.")
                } else if (manualAction == "SUSPEND") {
                    // NEW: this is where the real password actually lives
                    // — capture it here, save it, then follow the "Change"
                    // link to set the new one. Guarded so a redirect back
                    // to this exact page AFTER the password was already
                    // changed never re-captures (and overwrites) the
                    // correct original value with the new one.
                    if (!eboneSuspendCaptureDone) {
                        eboneSuspendCaptureDone = true
                        captureEbonePasswordFromProfileThenChange(urlCustomerId)
                    }
                } else if (manualAction == "RELIEF_VIEW") {
                    // FIX: the previous version did nothing at all here —
                    // no finish(), no success result — which meant
                    // UnpaidPackageActivationActivity's onActivityResult
                    // never ran, reliefStatus stayed stuck on
                    // "ACTIVE_PENDING" forever, GraceDeadlineWorker (which
                    // only watches reliefStatus=="ACTIVE") never picked
                    // the customer up even after the grace time passed,
                    // and the Reactivate button / Relief Active list never
                    // updated. A short visible pause (so the operator
                    // actually sees the profile land, per the earlier
                    // "it closed too fast" concern) is enough — after
                    // that, report success and finish normally so the
                    // relief cycle actually completes.
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
            selectedIsp == "ZONG" && url.contains("customer_portal.php") && autoActivateCustomerId != null && manualAction == "RELIEF_VIEW" -> {
                // FIX: for relief activation, ZONG must ONLY confirm it
                // reached the right customer's page (currently
                // Active/green, since relief is only given while the
                // connection is already running) and do NOTHING else —
                // no click on the actionx= Disable/Enable link. This
                // must be checked BEFORE the generic manualAction!=null
                // branch below, which calls onZongProfileOpened() and
                // WOULD click that link otherwise.
                webView.postDelayed({
                    setResult(RESULT_OK, Intent().apply { putExtra("activation_success", true) })
                    finish()
                }, 1500)
            }
            selectedIsp == "ZONG" && url.contains("customer_portal.php") && autoActivateCustomerId != null && manualAction != null -> {
                // FIX: SUSPEND/ENABLE work directly on the Franchise
                // account's own customer_portal.php page (confirmed via
                // inspect — the Enable/Disable link with actionx=... is
                // right there). This must NEVER route through
                // onZongFranchiseProfileOpened(), which is a completely
                // different feature that tries to find and switch into a
                // SUB-DEALER's own separate login — not needed and not
                // wanted here, since the admin explicitly wants the
                // Franchise account (Abbas046) used directly, never any
                // dealer account, for ZONG.
                onZongProfileOpened()
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
                    !url.contains("/clients/clientChange/") &&
                    !url.contains("/payments/addbalance/") -> {
                loginDone = true
                saveCookieForCurrentAccount("https://partner.ebill.pk")
                cacheIspSessionCookieIfApplicable("EBONE", "https://partner.ebill.pk")
                webView.evaluateJavascript(
                    "document.querySelectorAll('.modal,.modal-backdrop,.popup').forEach(function(el){el.style.display='none';});document.body.classList.remove('modal-open');", null
                )
                if (manualAction == "DEALER_TOPUP" && !dealerEboneId.isNullOrBlank()) {
                    webView.postDelayed({
                        webView.loadUrl("https://partner.ebill.pk/payments/addbalance/${dealerEboneId}")
                    }, 800)
                } else if (manualAction == "CHECK_BALANCE") {
                    if (!eboneBalanceCheckAttempted) {
                        eboneBalanceCheckAttempted = true
                        webView.postDelayed({ readEboneFranchiseBalance() }, 800)
                    }
                } else if (!url.contains("/clients")) {
                    webView.postDelayed({ webView.loadUrl("https://partner.ebill.pk/clients") }, 800)
                } else if (autoActivateCustomerId != null) {
                    webView.postDelayed({ searchEboneCustomer(autoActivateCustomerId!!) }, 800)
                }
            }
            selectedIsp == "WATEEN" && url.contains("panel.wateen.com") &&
                    !url.contains("auth.html") && !url.contains("/user/user/view/") &&
                    !url.contains("/dealer/dealer/") -> {
                loginDone = true
                saveCookieForCurrentAccount("https://panel.wateen.com")
                cacheIspSessionCookieIfApplicable("WATEEN", "https://panel.wateen.com")
                if ((manualAction == "DEALER_TOPUP" && !dealerEboneId.isNullOrBlank()) ||
                    (manualAction == "FETCH_DEALER_ID" && !dealerSearchName.isNullOrBlank())) {
                    if (!wateenDealerListLoadAttempted) {
                        wateenDealerListLoadAttempted = true
                        webView.postDelayed({ webView.loadUrl("https://panel.wateen.com/dealer/dealer/all") }, 800)
                    }
                } else if (manualAction == "CHECK_BALANCE") {
                    val onWateenRoot = !url.contains("/user/user/") && !url.contains("/dealer/dealer/")
                    if (onWateenRoot) {
                        if (!wateenBalanceCheckAttempted) {
                            wateenBalanceCheckAttempted = true
                            wateenBalanceReadAttempt = 1
                            webView.postDelayed({ readWateenFranchiseBalance(1) }, 450)
                        }
                    } else if (!wateenBalanceCheckAttempted) {
                        webView.postDelayed({ webView.loadUrl("https://panel.wateen.com/") }, 800)
                    }
                } else if (!url.contains("/user/user/all")) {
                    webView.postDelayed({ webView.loadUrl("https://panel.wateen.com/user/user/all") }, 800)
                } else {
                    autoActivateCustomerId?.let { id -> webView.postDelayed({ searchWateenCustomer(id) }, 800) }
                }
            }
            selectedIsp == "WATEEN" && url.contains("/dealer/dealer/all") &&
                    (manualAction == "DEALER_TOPUP" || manualAction == "FETCH_DEALER_ID") -> {
                if (!wateenDealerSearchAttempted) {
                    wateenDealerSearchAttempted = true
                    val searchTerm = if (manualAction == "FETCH_DEALER_ID") dealerSearchName else dealerEboneId
                    webView.postDelayed({ searchWateenDealer(searchTerm ?: "") }, 900)
                }
            }
            selectedIsp == "WATEEN" && url.contains("/dealer/dealer/view/") && manualAction == "DEALER_TOPUP" -> {
                if (!wateenTopupSubmitAttempted) {
                    wateenTopupSubmitAttempted = true
                    android.util.Log.d("WebViewLoginActivity", "Wateen dealer topup — landed on: $url")
                    webView.postDelayed({ openWateenPaymentModalAndSubmit() }, 1800)
                }
            }
            selectedIsp == "ZONG" && url.contains("turbonet.zong.com.pk") &&
                    !url.contains("login.php") && !url.contains("customer_portal.php") &&
                    !url.contains("sub_dealers.php") -> {
                loginDone = true
                if (!zongDealerMode) {
                    saveCookieForCurrentAccount("https://turbonet.zong.com.pk")
                    cacheIspSessionCookieIfApplicable("ZONG", "https://turbonet.zong.com.pk")
                }
                if (manualAction == "DEALER_TOPUP" && !dealerEboneId.isNullOrBlank()) {
                    if (!zongDealerListLoadAttempted) {
                        zongDealerListLoadAttempted = true
                        webView.postDelayed({ webView.loadUrl("https://turbonet.zong.com.pk/sub_dealers.php") }, 800)
                    }
                } else if (manualAction == "CHECK_BALANCE") {
                    val onSuitableZongPage = !url.contains("customers.php")
                    if (onSuitableZongPage) {
                        if (!zongBalanceCheckAttempted) {
                            zongBalanceCheckAttempted = true
                            webView.postDelayed({ readZongFranchiseBalance() }, 1500)
                        }
                    } else if (!zongBalanceCheckAttempted) {
                        webView.postDelayed({ webView.loadUrl("https://turbonet.zong.com.pk/index.php") }, 800)
                    }
                } else if (!url.contains("customers.php")) {
                    webView.postDelayed({ webView.loadUrl("https://turbonet.zong.com.pk/customers.php") }, 800)
                } else {
                    webView.evaluateJavascript(
                        "(function(){" +
                                "  var sel = document.querySelector('select[name=\"managercustomers_length\"]');" +
                                "  if(sel){ sel.value = '1000'; sel.dispatchEvent(new Event('change', {bubbles:true})); }" +
                                "})()", null
                    )
                    webView.postDelayed({
                        autoActivateCustomerId?.let { id -> searchZongCustomer(id) }
                    }, 2000)
                }
            }
            selectedIsp == "ZONG" && url.contains("sub_dealers.php") && manualAction == "DEALER_TOPUP" -> {
                if (!zongTopupSubmitAttempted) {
                    zongTopupSubmitAttempted = true
                    webView.postDelayed({ searchAndCreditZongDealer(dealerEboneId ?: "", topupAmount ?: "") }, 700)
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
        if (loginAttemptInProgress) return
        loginAttemptInProgress = true

        // FIX: if a specific dealer account was forced (e.g. "Akmal" via
        // dealer_account_name), its credentials — already looked up
        // correctly from IspPanelSettingsActivity in onCreate — MUST be
        // used, and Franchise credentials must never be checked at all.
        // Previously this checked IspPanelSettingsActivity.getSavedUsername
        // first, which is Franchise-priority by design and silently
        // overrode the forced dealer selection.
        if (!forcedDealerUsername.isNullOrBlank() && !forcedDealerPassword.isNullOrBlank()) {
            doLoginWith(forcedDealerUsername!!, forcedDealerPassword!!)
            return
        }

        val ispUsername = IspPanelSettingsActivity.getSavedUsername(this, selectedIsp, targetZone)
        val ispPassword = IspPanelSettingsActivity.getSavedPassword(this, selectedIsp, targetZone)
        if (!ispUsername.isNullOrEmpty() && !ispPassword.isNullOrEmpty()) {
            doLoginWith(ispUsername, ispPassword)
            return
        }
        if (!targetZone.equals("Okara", ignoreCase = true)) {
            loginAttemptInProgress = false
            return
        }
        if (activeAccountName.isEmpty()) { loginAttemptInProgress = false; return }
        val accounts = loadAccounts()
        if (!accounts.has(activeAccountName)) { loginAttemptInProgress = false; return }
        val acc = accounts.getJSONObject(activeAccountName)
        val username = acc.optString("username", "")
        val password = acc.optString("password", "")
        if (username.isEmpty() || password.isEmpty()) { loginAttemptInProgress = false; return }
        doLoginWith(username, password)
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
                    loginAttemptInProgress = false
                    if (result == "fields_not_ready") {
                    }
                }
            }
        }, if (attempt == 1) 1800L else 900L)
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

    /**
     * FIX (relief automation): when a SUSPEND completes successfully, the
     * customer's reliefStatus is now also set to "SUSPENDED" if they had
     * one. This is what makes them appear in the "Relief users" list as
     * disabled/removed from the active-relief count, and lets
     * UnpaidPackageActivationActivity's Reactivate button correctly know
     * a real ISP-panel restore is needed (vs. an early-payment case where
     * the connection was never actually cut).
     */
    private fun finishManualActionSuccess() {
        val custId = autoActivateCustomerId
        if (custId != null) {
            val newStatus = if (manualAction == "SUSPEND") "DISABLED" else "ACTIVE"
            val updates = mutableMapOf<String, Any>("activationStatus" to newStatus)
            if (manualAction == "ENABLE") {
                updates["lastPaymentDate"] = System.currentTimeMillis()
                updates["graceDeadline"] = FieldValue.delete()
                updates["eboneOriginalPassword"] = FieldValue.delete()
                updates["eboneOriginalPasswordSavedAt"] = FieldValue.delete()
                updates["reliefStatus"] = FieldValue.delete()
                updates["reliefTestMode"] = FieldValue.delete()
                updates["reliefCompany"] = FieldValue.delete()
            } else if (manualAction == "SUSPEND") {
                updates["reliefStatus"] = "SUSPENDED"
                if (!eboneOriginalPassword.isNullOrBlank()) {
                    updates["eboneOriginalPasswordSavedAt"] = FieldValue.serverTimestamp()
                }
            }
            db.collection("customers").document(custId).update(updates)

            // NEW: standalone Relief Log entry — writes to its own
            // separate "reliefLogs" collection only, never touches
            // "customers" or anything above.
            if (manualAction == "SUSPEND") {
                ReliefLogRepository.logDeactivation(custId)
            }
        }
        setResult(RESULT_OK, Intent().apply { putExtra("manual_action_success", true) })
        finish()
    }

    private fun finishManualActionFailure(reason: String) {
        setResult(RESULT_OK, Intent().apply { putExtra("manual_action_success", false) })
        finish()
    }

    private fun prepareEbonePasswordAction() {
        val custId = autoActivateCustomerId?.trim().orEmpty()
        if (custId.isEmpty()) {
            finishManualActionFailure("EBONE Customer ID is missing")
            return
        }

        if (manualAction == "ENABLE") {
            if (eboneOriginalPassword.isNullOrBlank()) {
                finishManualActionFailure("Saved original EBONE password is missing; restore aborted")
                return
            }
            fillEbonePasswordAndSubmit()
            return
        }

        if (manualAction != "SUSPEND") {
            fillEbonePasswordAndSubmit()
            return
        }

        // FIX: for SUSPEND, the real password is now captured earlier —
        // directly from the profile page's plain-text "5555 Change" cell,
        // via captureEbonePasswordFromProfileThenChange(). By the time we
        // land here (on clientChange), eboneOriginalPassword is already
        // set, so there's nothing left to read from this page — just fill
        // in the new password and submit.
        if (!eboneOriginalPassword.isNullOrBlank()) {
            fillEbonePasswordAndSubmit()
            return
        }

        webView.postDelayed({
            webView.evaluateJavascript(
                """
                (function(){
                    var inputs = document.querySelectorAll('input[type=password]');
                    var values = [];
                    for (var i=0;i<inputs.length;i++){
                        var v = (inputs[i].value || '').trim();
                        if(v){ values.push(v); }
                    }
                    return JSON.stringify({count:values.length,values:values});
                })()
                """.trimIndent()
            ) { raw ->
                val clean = raw
                    .removeSurrounding("\"")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")

                val valuesRaw = Regex("\"values\":\\[(.*?)\\]")
                    .find(clean)?.groupValues?.get(1).orEmpty()

                val values = Regex("\"(.*?)\"")
                    .findAll(valuesRaw)
                    .map { it.groupValues[1] }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()

                val original = values.firstOrNull()

                if (original.isNullOrBlank()) {
                    finishManualActionFailure(
                        "EBONE Inspector page did not expose the current password for $custId. Password was NOT changed."
                    )
                    return@evaluateJavascript
                }

                eboneOriginalPassword = original

                db.collection("customers").document(custId)
                    .update(
                        mapOf(
                            "eboneOriginalPassword" to original,
                            "eboneOriginalPasswordSavedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    .addOnSuccessListener {
                        android.util.Log.d(
                            "WebViewLoginActivity",
                            "EBONE original password captured and saved for $custId. Proceeding to 8888."
                        )
                        fillEbonePasswordAndSubmit()
                    }
                    .addOnFailureListener { e ->
                        finishManualActionFailure(
                            "Could not save original EBONE password for $custId. Password was NOT changed: ${e.message}"
                        )
                    }
            }
        }, 700)
    }

    private fun fillEbonePasswordAndSubmit() {
        val newPassword = if (manualAction == "SUSPEND") {
            EBONE_SUSPEND_PASSWORD
        } else {
            eboneOriginalPassword?.trim().orEmpty()
        }

        if (newPassword.isEmpty()) {
            finishManualActionFailure("No original EBONE password is available for restore")
            return
        }
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
        }, 700)
    }

    private fun searchWateenCustomer(customerId: String) {
        webView.evaluateJavascript(
            "(function(){" +
                    "  var inp = document.querySelector('input[aria-controls=\"userListAll\"],.dataTables_filter input');" +
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
        if (manualAction == "RELIEF_VIEW") {
            // FIX: for relief activation, WATEEN must ONLY confirm it
            // reached the right customer's profile (currently Active,
            // showing "Disable Net" — since relief is only given while
            // the connection is already running) and do NOTHING else —
            // no click on Disable/Enable. Without this explicit check,
            // manualAction != null below would treat RELIEF_VIEW as if
            // it were an ENABLE request and click "Enable Net".
            webView.postDelayed({
                setResult(RESULT_OK, Intent().apply { putExtra("activation_success", true) })
                finish()
            }, 1500)
            return
        }
        if (manualAction != null) {
            if (manualAction == "SUSPEND") {
                // FIX: "Disable Net" does NOT directly perform the action
                // via its href — clicking it opens a confirmation popup
                // (auto-cancels after ~9-10 seconds if nothing is
                // clicked). The REAL disable only happens when the
                // popup's own red "Disable" button is clicked. Simply
                // navigating to the link's href (the old approach) skips
                // this popup entirely and would not actually disable the
                // connection.
                webView.evaluateJavascript(
                    "(function(){" +
                            "  var link = document.querySelector('a.disable-user-connection');" +
                            "  if(link){ link.click(); return 'clicked'; }" +
                            "  return 'not_found';" +
                            "})()"
                ) { clickResultRaw ->
                    val clickResult = clickResultRaw.trim().removeSurrounding("\"")
                    if (clickResult != "clicked") {
                        finishManualActionFailure("Disable Net link not found on Wateen profile")
                        return@evaluateJavascript
                    }
                    // Short pause for the popup to actually render, then
                    // click its real confirm button — well within the
                    // ~9-10 second auto-cancel window.
                    webView.postDelayed({
                        webView.evaluateJavascript(
                            "(function(){" +
                                    "  var buttons = document.querySelectorAll('button.btn-red');" +
                                    "  for (var i=0;i<buttons.length;i++){" +
                                    "    if (buttons[i].innerText.trim().indexOf('Disable') > -1){ buttons[i].click(); return 'clicked'; }" +
                                    "  }" +
                                    "  if (buttons.length === 1){ buttons[0].click(); return 'clicked'; }" +
                                    "  return 'not_found';" +
                                    "})()"
                        ) { confirmResultRaw ->
                            val confirmResult = confirmResultRaw.trim().removeSurrounding("\"")
                            if (confirmResult == "clicked") {
                                webView.postDelayed({ finishManualActionSuccess() }, 2000)
                            } else {
                                finishManualActionFailure("Disable confirmation button not found in Wateen popup")
                            }
                        }
                    }, 800)
                }
                return
            }

            // FIX: ENABLE also opens the same kind of confirmation popup
            // as DISABLE — same auto-cancel timing, just a GREEN "Enable"
            // button instead of the red "Disable" one. Clicking the link
            // alone (old approach) does not perform the real action.
            webView.evaluateJavascript(
                "(function(){" +
                        "  var link = document.querySelector('a.enable-user-connection');" +
                        "  if(link){ link.click(); return 'clicked'; }" +
                        "  return 'not_found';" +
                        "})()"
            ) { clickResultRaw ->
                val clickResult = clickResultRaw.trim().removeSurrounding("\"")
                if (clickResult != "clicked") {
                    finishManualActionFailure("Enable Net link not found on Wateen profile")
                    return@evaluateJavascript
                }
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){" +
                                "  var buttons = document.querySelectorAll('button.btn-green');" +
                                "  for (var i=0;i<buttons.length;i++){" +
                                "    if (buttons[i].innerText.trim().indexOf('Enable') > -1){ buttons[i].click(); return 'clicked'; }" +
                                "  }" +
                                "  if (buttons.length === 1){ buttons[0].click(); return 'clicked'; }" +
                                "  return 'not_found';" +
                                "})()"
                    ) { confirmResultRaw ->
                        val confirmResult = confirmResultRaw.trim().removeSurrounding("\"")
                        if (confirmResult == "clicked") {
                            webView.postDelayed({ finishManualActionSuccess() }, 2000)
                        } else {
                            finishManualActionFailure("Enable confirmation button not found in Wateen popup")
                        }
                    }
                }, 800)
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
                } else {
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

    private fun searchEboneCustomer(customerId: String) {
        // NEW: if the Unpaid Package Activation screen's URL field value
        // was passed in, use IT as the base — the searched/selected
        // customer ID is appended directly onto this URL to build the
        // exact navigation address, instead of always using the
        // hardcoded "https://partner.ebill.pk" domain.
        val base = (customerUrlOverride ?: "https://partner.ebill.pk").trimEnd('/')

        if (manualAction == "ENABLE") {
            val cleanId = customerId.trim()
            if (cleanId.isEmpty()) {
                finishManualActionFailure("EBONE Customer ID is empty")
                return
            }
            db.collection("customers").document(cleanId).get()
                .addOnSuccessListener { snapshot ->
                    val saved = snapshot.getString("eboneOriginalPassword")?.trim().orEmpty()
                    if (saved.isEmpty()) {
                        finishManualActionFailure(
                            "Original EBONE password was not saved for $cleanId. Restore aborted; 1001 was NOT used."
                        )
                        return@addOnSuccessListener
                    }
                    eboneOriginalPassword = saved
                    webView.loadUrl("$base/clients/clientChange/$cleanId")
                }
                .addOnFailureListener { e ->
                    finishManualActionFailure(
                        "Could not read saved EBONE password for $cleanId: ${e.message}"
                    )
                }
            return
        }

        // FIX: SUSPEND now goes to the PROFILE page first
        // (/clients/client/{id}), not straight to clientChange. This is
        // where the real password is actually shown, as confirmed HTML:
        //   <td>5555 <a href="...clientChange/olt">Change</a></td>
        // clientChange has no password to read at all — it's purely the
        // "set a new password" destination. RELIEF_VIEW also lands here
        // (and does nothing further, per current behaviour).
        eboneOriginalPassword = null
        webView.loadUrl("$base/clients/client/$customerId")
    }

    /**
     * NEW: reads the real, current password directly from the customer
     * profile page's "UserID / Password / Type" table row (confirmed
     * HTML structure — plain text immediately followed by a "Change"
     * link, e.g. "5555 Change"). Saves it to Firestore, THEN follows the
     * exact same "Change" link (built the same way the panel itself
     * builds it: base + /clients/clientChange/ + id) to actually set the
     * new password.
     */
    private fun captureEbonePasswordFromProfileThenChange(customerId: String) {
        webView.evaluateJavascript(
            "(function(){" +
                    "  var rows = document.querySelectorAll('table tbody tr');" +
                    "  for (var i=0;i<rows.length;i++){" +
                    "    var th = rows[i].querySelector('th');" +
                    "    if (!th) continue;" +
                    "    if (th.textContent.indexOf('Password') === -1) continue;" +
                    "    var tds = rows[i].querySelectorAll('td');" +
                    "    if (tds.length < 2) continue;" +
                    "    var raw = (tds[1].textContent || '').trim();" +
                    "    var pwd = raw.replace(/Change\\s*$/, '').trim();" +
                    "    return pwd;" +
                    "  }" +
                    "  return '';" +
                    "})()"
        ) { raw ->
            val original = raw.trim().removeSurrounding("\"")

            if (original.isBlank()) {
                finishManualActionFailure(
                    "EBONE profile page did not expose the current password for $customerId. Password was NOT changed."
                )
                return@evaluateJavascript
            }

            eboneOriginalPassword = original

            db.collection("customers").document(customerId)
                .update(
                    mapOf(
                        "eboneOriginalPassword" to original,
                        "eboneOriginalPasswordSavedAt" to FieldValue.serverTimestamp()
                    )
                )
                .addOnSuccessListener {
                    android.util.Log.d(
                        "WebViewLoginActivity",
                        "EBONE original password captured from profile page and saved for $customerId."
                    )
                    val base = (customerUrlOverride ?: "https://partner.ebill.pk").trimEnd('/')
                    webView.loadUrl("$base/clients/clientChange/$customerId")
                }
                .addOnFailureListener { e ->
                    finishManualActionFailure(
                        "Could not save original EBONE password for $customerId. Password was NOT changed: ${e.message}"
                    )
                }
        }
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

    private fun fillDealerTopupAmountAndSubmit() {
        val amount = topupAmount
        if (amount.isNullOrBlank()) {
            finishManualActionFailure("No top-up amount was provided")
            return
        }

        if (eboneTopupSuccessConfirmed) return

        if (eboneTopupSubmitAttempted) {
            verifyEboneDealerTopupResult(amount, eboneTopupVerificationAttempt)
            return
        }

        if (eboneTopupSubmitAttempt >= 8) {
            finishManualActionFailure(
                "Ebone payment form did not become ready for Submit after 8 checks. No success was recorded."
            )
            return
        }

        eboneTopupSubmitAttempt++
        val attempt = eboneTopupSubmitAttempt

        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var inp = document.querySelector('input[name=\\\"PaidAmt\\\"]');" +
                        "  if(!inp) return 'no_amount_field';" +
                        "  if(inp.disabled || inp.readOnly) return 'amount_field_disabled';" +
                        "  inp.value = '$amount';" +
                        "  inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "  inp.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "  var form = inp.form || document.querySelector('form[action*=\\\"addbalance\\\"]');" +
                        "  if(!form) return 'no_form';" +
                        "  var btn = form.querySelector('button[type=submit],input[type=submit],button[name=\\\"submit\\\"],#send');" +
                        "  if(!btn){ btn = document.querySelector('button[type=submit],input[type=submit],#send'); }" +
                        "  if(btn && (btn.disabled || btn.offsetParent === null)) return 'submit_not_ready';" +
                        "  return 'ready';" +
                        "})()"
            ) { readyResultRaw ->
                val readyResult = readyResultRaw.trim().removeSurrounding("\"")

                if (readyResult != "ready") {
                    android.util.Log.d(
                        "WebViewLoginActivity",
                        "Ebone dealer top-up attempt $attempt: form not ready ($readyResult). Retrying."
                    )
                    webView.postDelayed({ fillDealerTopupAmountAndSubmit() }, 1200)
                    return@evaluateJavascript
                }

                webView.evaluateJavascript(
                    "(function(){" +
                            "  var inp = document.querySelector('input[name=\\\"PaidAmt\\\"]');" +
                            "  var form = inp ? (inp.form || document.querySelector('form[action*=\\\"addbalance\\\"]')) : null;" +
                            "  if(!form) return 'no_form';" +
                            "  var btn = form.querySelector('button[type=submit],input[type=submit],button[name=\\\"submit\\\"],#send');" +
                            "  if(!btn){ btn = document.querySelector('button[type=submit],input[type=submit],#send'); }" +
                            "  if(btn && !btn.disabled && btn.offsetParent !== null){ btn.click(); return 'clicked'; }" +
                            "  return 'submit_not_ready';" +
                            "})()"
                ) { clickResultRaw ->
                    val clickResult = clickResultRaw.trim().removeSurrounding("\"")

                    if (clickResult == "clicked") {
                        eboneTopupSubmitAttempted = true
                        eboneTopupVerificationAttempt = 0
                        verifyEboneDealerTopupResult(amount, 0)
                    } else {
                        android.util.Log.d(
                            "WebViewLoginActivity",
                            "Ebone dealer top-up attempt $attempt: submit click unavailable ($clickResult). Retrying."
                        )
                        webView.postDelayed({ fillDealerTopupAmountAndSubmit() }, 1200)
                    }
                }
            }
        }, 900)
    }

    private fun verifyEboneDealerTopupResult(amount: String, attempt: Int) {
        if (eboneTopupSuccessConfirmed) return

        if (attempt >= 10) {
            finishManualActionFailure(
                "Ebone Submit was clicked, but the panel did not confirm the payment. No success was recorded."
            )
            return
        }

        eboneTopupVerificationAttempt = attempt
        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var body=((document.body&&document.body.innerText)||'').replace(/\\s+/g,' ').trim();" +
                        "  var url=window.location.href||'';" +
                        "  var amount='$amount'.replace(/,/g,'');" +
                        "  var success=/(payment|balance|credit|transaction)[^\\n]{0,80}(success|successful|completed|added|updated)|successfully[^\\n]{0,80}(payment|added|updated|credited)|payment[^\\n]{0,80}successful/i.test(body);" +
                        "  var error=/(error|failed|failure|invalid|insufficient|unable|cannot|not found)/i.test(body);" +
                        "  var form=document.querySelector('form[action*=\\\"addbalance\\\"]')||document.querySelector('form');" +
                        "  var inp=document.querySelector('input[name=\\\"PaidAmt\\\"]');" +
                        "  var btn=form?form.querySelector('button[type=submit],input[type=submit],button[name=\\\"submit\\\"],#send'):null;" +
                        "  var formVisible=!!(form&&form.offsetParent!==null);" +
                        "  var buttonReady=!!(btn&&!btn.disabled&&btn.offsetParent!==null);" +
                        "  var awayFromAddBalance=url.indexOf('/payments/addbalance/')===-1;" +
                        "  var amountSeen=amount && body.indexOf(amount)>-1;" +
                        "  return JSON.stringify({url:url,success:success,error:error,formVisible:formVisible,buttonReady:buttonReady,awayFromAddBalance:awayFromAddBalance,amountSeen:amountSeen,body:body.substring(0,1400)});" +
                        "})()"
            ) { raw ->
                val clean = raw.removeSurrounding("\"")
                    .replace("\\\"", "\"")
                    .replace("\\n", " ")
                    .replace("\\\\", "\\")

                val success = Regex("\\\"success\\\":(true|false)")
                    .find(clean)?.groupValues?.get(1) == "true"
                val error = Regex("\\\"error\\\":(true|false)")
                    .find(clean)?.groupValues?.get(1) == "true"
                val awayFromAddBalance = Regex("\\\"awayFromAddBalance\\\":(true|false)")
                    .find(clean)?.groupValues?.get(1) == "true"
                val formVisible = Regex("\\\"formVisible\\\":(true|false)")
                    .find(clean)?.groupValues?.get(1) == "true"
                val buttonReady = Regex("\\\"buttonReady\\\":(true|false)")
                    .find(clean)?.groupValues?.get(1) == "true"

                android.util.Log.d(
                    "WebViewLoginActivity",
                    "Ebone dealer top-up verify ${attempt + 1}/10: success=$success error=$error away=$awayFromAddBalance form=$formVisible button=$buttonReady page=$clean"
                )

                if (success) {
                    eboneTopupSuccessConfirmed = true
                    captureDealerTopupResult()
                    return@evaluateJavascript
                }

                if (attempt + 1 < 10 && !error) {
                    webView.postDelayed({ verifyEboneDealerTopupResult(amount, attempt + 1) }, 850)
                    return@evaluateJavascript
                }

                markSubmittedButUnconfirmed()
                return@evaluateJavascript
            }
        }, 1800)
    }

    private fun markSubmittedButUnconfirmed() {
        eboneTopupSuccessConfirmed = true

        sourceTransactionId?.let { txId ->
            if (txId.isNotBlank()) {
                db.collection("dealerTransactions").document(txId)
                    .update(
                        mapOf(
                            "transferStatus" to "AUTO_FAILED",
                            "transferError" to "Submit was clicked once but the panel never clearly confirmed success — please check the panel balance manually before resending. (Not resubmitted automatically.)"
                        )
                    )
            }
        }
        finishManualActionFailure(
            "Ebone Submit was clicked once. The panel did not clearly confirm success. Not resubmitting — please check the panel manually."
        )
    }

    private fun showAdminTransferNotification(dealerName: String, panel: String, zone: String, amount: Double) {
        val channelId = "dealer_auto_transfer"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(channelId, "Dealer Auto Transfers", android.app.NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
        val zoneLabel = if (zone.equals("Okara", ignoreCase = true)) "" else " ($zone)"
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Payment auto-transferred")
            .setContentText("$dealerName$zoneLabel — Rs. ${"%.0f".format(amount)} sent via $panel")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(("transfer_$dealerName$zone$panel${System.currentTimeMillis()}").hashCode(), notification)
    }

    private fun captureDealerTopupResult() {
        webView.evaluateJavascript(
            "(function(){" +
                    "  var b = document.querySelector('.box-body');" +
                    "  var text = (b ? b.innerText : document.body.innerText) || '';" +
                    "  var dealerBalance = '';" +
                    "  var labels = document.querySelectorAll('h5.card-title');" +
                    "  for (var i=0;i<labels.length;i++){" +
                    "    if (labels[i].innerText.trim() === 'Dealer Balance'){" +
                    "      var valEl = labels[i].parentElement ? labels[i].parentElement.querySelector('span.h2') : null;" +
                    "      if (valEl) dealerBalance = valEl.innerText.trim();" +
                    "      break;" +
                    "    }" +
                    "  }" +
                    "  return JSON.stringify({text: text.substring(0, 800), url: window.location.href, dealerBalance: dealerBalance});" +
                    "})()"
        ) { resultRaw ->
            var capturedText = ""
            var dealerBalanceAfter = ""
            try {
                val clean = resultRaw
                    .removeSurrounding("\"")
                    .replace("\\\"", "\"")
                    .replace("\\n", " ")
                    .replace("\\\\", "\\")
                capturedText = clean
                val balanceMatch = Regex("\"dealerBalance\":\"(.*?)\"").find(resultRaw)
                dealerBalanceAfter = balanceMatch?.groupValues?.get(1) ?: ""
                android.util.Log.d("WebViewLoginActivity", "Dealer top-up result page: $clean")
                if (dealerBalanceAfter.isNotBlank()) {
                    android.util.Log.d("WebViewLoginActivity", "Dealer's balance shown on this page after submit: $dealerBalanceAfter")
                }
            } catch (_: Exception) {
            }

            val amountValue = topupAmount?.toDoubleOrNull() ?: 0.0
            val logEntry = mapOf(
                "dealerId" to (dealerInternalId ?: ""),
                "dealerName" to (dealerDisplayName ?: ""),
                "panel" to selectedIsp,
                "ispDealerId" to (dealerEboneId ?: ""),
                "amount" to amountValue,
                "submittedAt" to System.currentTimeMillis(),
                "resultText" to capturedText.take(500),
                "dealerBalanceAfter" to dealerBalanceAfter,
                "sourceTransactionId" to (sourceTransactionId ?: "")
            )
            db.collection("dealerPayments").add(logEntry)

            val smsSourceTxId = sourceTransactionId?.takeIf { it.isNotBlank() }
            if (smsSourceTxId != null) {
                db.collection("dealerTransactions").document(smsSourceTxId)
                    .update(
                        mapOf(
                            "status" to "COMPLETED",
                            "transferStatus" to "TRANSFERRED",
                            "transferredAt" to System.currentTimeMillis(),
                            "transferResultText" to capturedText.take(500)
                        )
                    )
                    .addOnSuccessListener {
                        manualAction = null
                        setResult(RESULT_OK, Intent().apply {
                            putExtra("dealer_topup_submitted", true)
                            putExtra("sms_payment_completed", true)
                            putExtra("source_transaction_id", smsSourceTxId)
                        })
                        finish()
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.e(
                            "WebViewLoginActivity",
                            "Could not finalize SMS-triggered dealer transaction $smsSourceTxId",
                            e
                        )
                        continueAfterDealerTopupSuccess()
                    }
                return@evaluateJavascript
            }

            val balanceNote = if (dealerBalanceAfter.isNotBlank())
                "Dealer's balance now shows: $dealerBalanceAfter. " else ""
            setResult(RESULT_OK, Intent().apply {
                putExtra("dealer_topup_submitted", true)
            })

            continueAfterDealerTopupSuccess()
        }
    }

    private fun continueAfterDealerTopupSuccess() {
        manualAction = "CHECK_BALANCE"
        when (selectedIsp) {
            "EBONE" -> {
                eboneBalanceCheckAttempted = false
                webView.postDelayed({ readEboneFranchiseBalance() }, 1000)
            }
            "WATEEN" -> {
                wateenBalanceCheckAttempted = false
                wateenBalanceReadAttempt = 0
                webView.postDelayed({ webView.loadUrl("https://panel.wateen.com/") }, 600)
            }
            "ZONG" -> {
                zongBalanceCheckAttempted = false
                zongNavigationStep = 0
                zongLastTrailUrl = ""
                webView.postDelayed({ webView.loadUrl("https://turbonet.zong.com.pk/index.php") }, 600)
            }
            else -> finish()
        }
    }

    private fun readEboneFranchiseBalance(attempt: Int = 1) {
        webView.evaluateJavascript(
            "(function(){" +
                    "  var footer = document.querySelector('.dropdown-menu li.footer');" +
                    "  var text = footer ? footer.innerText : '';" +
                    "  if (text && text.indexOf('Balance') > -1) {" +
                    "    return JSON.stringify({text:text, clicked:false});" +
                    "  }" +
                    "  var icon = document.querySelector('i.fa-dollar');" +
                    "  var link = icon ? icon.closest('a') : null;" +
                    "  if (link) { link.click(); }" +
                    "  return JSON.stringify({text:'', clicked: !!link});" +
                    "})()"
        ) { resultRaw ->
            try {
                val clean = resultRaw
                    .removeSurrounding("\"")
                    .replace("\\\"", "\"")
                    .replace("\\n", " ")
                    .replace("\\\\", "\\")
                val textMatch = Regex("\"text\":\"(.*?)\",\"clicked\"").find(clean)
                val text = textMatch?.groupValues?.get(1) ?: ""
                val clicked = clean.contains("\"clicked\":true")

                if (text.isNotBlank()) {
                    val balanceMatch = Regex("Balance:\\s*(-?[0-9.,]+)").find(text)
                    val balance = balanceMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
                    if (balance != null) {
                        FranchiseBalanceManager.updateBalance("EBONE", balance, targetZone) { _ ->
                            FranchiseBalanceManager.checkAndNotifyLowBalance(this, "EBONE", balance, targetZone)
                        }
                        setResult(RESULT_OK, Intent().apply { putExtra("checked_balance", balance) })
                        finish()
                        return@evaluateJavascript
                    }
                }

                if (clicked && attempt < 3) {
                    webView.postDelayed({ readEboneFranchiseBalance(attempt + 1) }, 700)
                } else {
                    finishManualActionFailure("Could not read the franchise balance dropdown on Ebone")
                }
            } catch (e: Exception) {
                if (attempt < 3) {
                    webView.postDelayed({ readEboneFranchiseBalance(attempt + 1) }, 700)
                } else {
                    finishManualActionFailure("Could not read the franchise balance dropdown: ${e.message}")
                }
            }
        }
    }

    private fun searchWateenDealer(searchTerm: String) {
        if (searchTerm.isBlank()) {
            finishManualActionFailure("No search term was provided")
            return
        }
        webView.evaluateJavascript(
            "(function(){" +
                    "  var inp = document.querySelector('input[aria-controls=\"dtAllDealers\"]');" +
                    "  if(inp){" +
                    "    inp.focus();" +
                    "    inp.value = '$searchTerm';" +
                    "    inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "    inp.dispatchEvent(new Event('keyup',{bubbles:true}));" +
                    "    return 'search_box_found';" +
                    "  }" +
                    "  return 'search_box_not_found';" +
                    "})()"
        ) { searchResultRaw ->
            val searchResult = searchResultRaw.trim().removeSurrounding("\"")
            if (searchResult != "search_box_found") {
                finishManualActionFailure("Dealer search box not found on Wateen dealer list page")
                return@evaluateJavascript
            }
            webView.postDelayed({
                webView.evaluateJavascript(
                    "(function(){" +
                            "  var links = document.querySelectorAll('a[href*=\"/dealer/dealer/view/\"]');" +
                            "  for (var i=0;i<links.length;i++){" +
                            "    var row = links[i].closest('tr');" +
                            "    var rowText = row ? row.innerText : links[i].innerText;" +
                            "    if (rowText && rowText.toLowerCase().indexOf('$searchTerm'.toLowerCase()) > -1){ return links[i].href; }" +
                            "  }" +
                            "  if (links.length === 1) { return links[0].href; }" +
                            "  return '';" +
                            "})()"
                ) { hrefRaw ->
                    val href = hrefRaw.trim().removeSurrounding("\"")
                    if (href.isEmpty() || !href.startsWith("http")) {
                        finishManualActionFailure("Could not find dealer \"$searchTerm\" in the Wateen dealer search results")
                        return@evaluateJavascript
                    }
                    if (manualAction == "FETCH_DEALER_ID") {
                        val numericId = Regex("""/dealer/dealer/view/(\d+)""").find(href)?.groupValues?.get(1)
                        if (numericId != null) {
                            setResult(RESULT_OK, Intent().apply {
                                putExtra("fetched_dealer_id", numericId)
                            })
                            finish()
                        } else {
                            finishManualActionFailure("Found a dealer link but could not read its numeric ID")
                        }
                    } else {
                        webView.loadUrl(href)
                    }
                }
            }, 700)
        }
    }

    private fun openWateenPaymentModalAndSubmit(attempt: Int = 1) {
        val amount = topupAmount
        if (amount.isNullOrBlank()) {
            finishManualActionFailure("No top-up amount was provided")
            return
        }
        if (attempt == 1) {
        } else {
        }
        webView.evaluateJavascript(
            "(function(){" +
                    "  var els = document.querySelectorAll('.btn-primary');" +
                    "  for (var i=0;i<els.length;i++){" +
                    "    if (els[i].innerText.trim() === 'Payment'){ els[i].click(); return 'clicked'; }" +
                    "  }" +
                    "  var candidates = [];" +
                    "  for (var i=0;i<els.length && i<10;i++){ candidates.push(els[i].tagName + ':' + els[i].innerText.trim()); }" +
                    "  var anyPay = document.querySelectorAll('*');" +
                    "  var payMatches = [];" +
                    "  for (var j=0;j<anyPay.length && payMatches.length<5;j++){" +
                    "    var t = (anyPay[j].innerText || '').trim();" +
                    "    if (t.length>0 && t.length<25 && t.toLowerCase().indexOf('payment')>-1 && anyPay[j].children.length===0){" +
                    "      payMatches.push(anyPay[j].tagName + ':' + anyPay[j].className + ':' + t);" +
                    "    }" +
                    "  }" +
                    "  return 'not_found|' + JSON.stringify(candidates) + '|' + JSON.stringify(payMatches);" +
                    "})()"
        ) { clickResultRaw ->
            val clickResult = clickResultRaw.trim().removeSurrounding("\"")
            if (clickResult != "clicked") {
                if (attempt < 3) {
                    webView.postDelayed({ openWateenPaymentModalAndSubmit(attempt + 1) }, 1500)
                    return@evaluateJavascript
                }
                val parts = clickResult.split("|")
                val btnPrimaryTexts = parts.getOrNull(1)?.replace("\\\"", "\"") ?: "[]"
                val paymentMatches = parts.getOrNull(2)?.replace("\\\"", "\"") ?: "[]"
                android.util.Log.d("WebViewLoginActivity", "Payment button not found after $attempt attempts. .btn-primary texts on page: $btnPrimaryTexts")
                android.util.Log.d("WebViewLoginActivity", "Elements containing 'payment' text: $paymentMatches")
                finishManualActionFailure("Payment button not found on Wateen dealer profile — see previous toast/Logcat for what's actually on the page")
                return@evaluateJavascript
            }
            webView.postDelayed({
                webView.evaluateJavascript(
                    "(function(){" +
                            "  var sel = document.querySelector('select.paymentmethod[name=\"paymentmethod\"]');" +
                            "  var amt = document.querySelector('input.amount[name=\"amount\"]');" +
                            "  if(!sel || !amt) return 'fields_not_found';" +
                            "  sel.value = '7';" +
                            "  sel.dispatchEvent(new Event('change',{bubbles:true}));" +
                            "  amt.value = '$amount';" +
                            "  amt.dispatchEvent(new Event('input',{bubbles:true}));" +
                            "  amt.dispatchEvent(new Event('change',{bubbles:true}));" +
                            "  var note = document.querySelector('input.other[name=\"other\"]');" +
                            "  if(note){" +
                            "    note.value = 'Online Payment';" +
                            "    note.dispatchEvent(new Event('input',{bubbles:true}));" +
                            "    note.dispatchEvent(new Event('change',{bubbles:true}));" +
                            "  }" +
                            "  return 'filled';" +
                            "})()"
                ) { fillResultRaw ->
                    val fillResult = fillResultRaw.trim().removeSurrounding("\"")
                    if (fillResult != "filled") {
                        finishManualActionFailure("Payment method/amount fields not found in Wateen payment modal (modal may not have opened — Step 1 said clicked, but modal fields aren't there)")
                        return@evaluateJavascript
                    }
                    webView.postDelayed({
                        webView.evaluateJavascript(
                            "(function(){" +
                                    "  var buttons = document.querySelectorAll('button[type=submit].btn-primary');" +
                                    "  for (var i=0;i<buttons.length;i++){" +
                                    "    if (buttons[i].innerText.indexOf('Add Payment') > -1){ buttons[i].click(); return 'submitted'; }" +
                                    "  }" +
                                    "  if (buttons.length === 1){ buttons[0].click(); return 'submitted'; }" +
                                    "  return 'not_found';" +
                                    "})()"
                        ) { submitResultRaw ->
                            val submitResult = submitResultRaw.trim().removeSurrounding("\"")
                            if (submitResult == "submitted") {
                                webView.postDelayed({ captureDealerTopupResult() }, 2500)
                            } else {
                                finishManualActionFailure("Add Payment button not found in Wateen payment modal")
                            }
                        }
                    }, 600)
                }
            }, 800)
        }
    }

    private fun readWateenFranchiseBalance(attempt: Int = 1) {
        wateenBalanceReadAttempt = attempt

        webView.evaluateJavascript(
            "(function(){" +
                    "  var result = {found:false,value:'',url:window.location.href,snippet:(document.body.innerText || document.body.textContent || '').substring(0,500)};" +
                    "  var links = document.querySelectorAll('a[href*=' + String.fromCharCode(39) + 'accounting/mybalance' + String.fromCharCode(39) + ']');" +
                    "  for(var i=0;i<links.length;i++){" +
                    "    var linkText = (links[i].innerText || links[i].textContent || '').trim();" +
                    "    if(linkText.toLowerCase().indexOf('my balance') === -1) continue;" +
                    "    var card = links[i].closest('.card-stats') || links[i].closest('.card');" +
                    "    var valEl = card ? card.querySelector('span.h2') : null;" +
                    "    if(!valEl){" +
                    "      var col = links[i].closest('.col');" +
                    "      valEl = col ? col.querySelector('span.h2') : null;" +
                    "    }" +
                    "    if(valEl){" +
                    "      var value = (valEl.innerText || valEl.textContent || '').trim();" +
                    "      if(value){ return JSON.stringify({found:true,value:value,url:window.location.href,snippet:result.snippet}); }" +
                    "    }" +
                    "  }" +
                    "  var body = document.body.innerText || document.body.textContent || '';" +
                    "  var m = body.match(/My\\s+Balance[\\s\\S]{0,120}?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)/i);" +
                    "  if(m){ return JSON.stringify({found:true,value:m[1],url:window.location.href,snippet:result.snippet}); }" +
                    "  return JSON.stringify(result);" +
                    "})()"
        ) { resultRaw ->
            val clean = resultRaw
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\\\", "\\")

            val foundTrue = clean.contains("\"found\":true")
            val balance = if (foundTrue) {
                Regex("\"value\":\"(.*?)\"")
                    .find(clean)
                    ?.groupValues
                    ?.get(1)
                    ?.replace(",", "")
                    ?.toDoubleOrNull()
            } else null

            if (balance != null) {
                val fieldName = if (targetZone.equals("Okara", ignoreCase = true)) {
                    "wateenBalance"
                } else {
                    "wateenBalance_$targetZone"
                }

                FranchiseBalanceManager.updateBalance("WATEEN", balance, targetZone) { _ ->
                    FranchiseBalanceManager.checkAndNotifyLowBalance(
                        this,
                        "WATEEN",
                        balance,
                        targetZone
                    )
                }

                setResult(
                    RESULT_OK,
                    Intent().apply { putExtra("checked_balance", balance) }
                )
                finish()
            } else if (attempt < 8) {
                val nextAttempt = attempt + 1
                android.util.Log.d(
                    "WateenBalance",
                    "My Balance not found yet — retry $nextAttempt/8 after 700ms. URL=${webView.url}"
                )
                webView.postDelayed({
                    readWateenFranchiseBalance(nextAttempt)
                }, 700)
            } else {
                val urlInfo = Regex("\"url\":\"(.*?)\"")
                    .find(clean)?.groupValues?.get(1) ?: "unknown"
                val snippet = Regex("\"snippet\":\"(.*?)\"")
                    .find(clean)?.groupValues?.get(1)?.take(300) ?: ""

                finishManualActionFailure(
                    "Could not find Wateen My Balance after 8 checks. Page: $urlInfo. Text seen: $snippet"
                )
            }
        }
    }

    private fun searchAndCreditZongDealer(searchTerm: String, amount: String) {
        if (searchTerm.isBlank() || amount.isBlank()) {
            finishManualActionFailure("Missing dealer name or amount for Zong top-up")
            return
        }
        webView.evaluateJavascript(
            "(function(){" +
                    "  var inp = document.querySelector('input[aria-controls=\"table3\"]');" +
                    "  if(!inp) return 'search_box_not_found';" +
                    "  inp.focus();" +
                    "  inp.value = '$searchTerm';" +
                    "  inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "  inp.dispatchEvent(new Event('keyup',{bubbles:true}));" +
                    "  return 'search_box_found';" +
                    "})()"
        ) { searchResultRaw ->
            val searchResult = searchResultRaw.trim().removeSurrounding("\"")
            if (searchResult != "search_box_found") {
                finishManualActionFailure("Dealer search box not found on Zong Sub-Dealers page")
                return@evaluateJavascript
            }
            webView.postDelayed({
                webView.evaluateJavascript(
                    "(function(){" +
                            "  var icons = document.querySelectorAll('i.mdi-plus');" +
                            "  var matches = [];" +
                            "  for (var i=0;i<icons.length;i++){" +
                            "    var row = icons[i].closest('tr');" +
                            "    var rowText = row ? row.innerText : '';" +
                            "    if (rowText.toLowerCase().indexOf('$searchTerm'.toLowerCase()) > -1){" +
                            "      matches.push({icon:i, rowText: rowText.substring(0,80)});" +
                            "    }" +
                            "  }" +
                            "  if (matches.length !== 1){" +
                            "    return JSON.stringify({status:'ambiguous', count: matches.length, totalIcons: icons.length});" +
                            "  }" +
                            "  var link = icons[matches[0].icon].closest('a');" +
                            "  if (!link) return JSON.stringify({status:'no_link'});" +
                            "  var target = link.getAttribute('data-target');" +
                            "  if (!target){ return JSON.stringify({status:'no_target'}); }" +
                            "  link.click();" +
                            "  return JSON.stringify({status:'clicked', rowText: matches[0].rowText, modalSelector: target});" +
                            "})()"
                ) { clickResultRaw ->
                    val clean = clickResultRaw
                        .removeSurrounding("\"")
                        .replace("\\\"", "\"")
                    val status = Regex("\"status\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                    if (status != "clicked") {
                        val countInfo = Regex("\"count\":(\\d+)").find(clean)?.groupValues?.get(1) ?: "?"
                        val totalInfo = Regex("\"totalIcons\":(\\d+)").find(clean)?.groupValues?.get(1) ?: "?"
                        finishManualActionFailure(
                            "Could not open a single confirmed dealer modal for \"$searchTerm\" " +
                                    "(status=$status, $countInfo/$totalInfo matches). " +
                                    "Stopped WITHOUT crediting anyone."
                        )
                        return@evaluateJavascript
                    }
                    val matchedRowText = Regex("\"rowText\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                    val modalSelector = Regex("\"modalSelector\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                    if (modalSelector.isBlank()) {
                        finishManualActionFailure("Could not determine which dealer's modal opened — stopped WITHOUT crediting anyone.")
                        return@evaluateJavascript
                    }
                    webView.postDelayed({
                        webView.evaluateJavascript(
                            "(function(){" +
                                    "  var modal = document.querySelector('$modalSelector');" +
                                    "  if(!modal) return JSON.stringify({status:'modal_not_found'});" +
                                    "  var amt = modal.querySelector('input[name=\"credit_amount\"]');" +
                                    "  if(!amt) return JSON.stringify({status:'amount_not_found'});" +
                                    "  amt.value = '$amount';" +
                                    "  amt.dispatchEvent(new Event('input',{bubbles:true}));" +
                                    "  amt.dispatchEvent(new Event('change',{bubbles:true}));" +
                                    "  var modalText = modal.innerText.substring(0,300);" +
                                    "  return JSON.stringify({status:'filled', modalText: modalText});" +
                                    "})()"
                        ) { fillResultRaw ->
                            val fillClean = fillResultRaw.removeSurrounding("\"").replace("\\\"", "\"")
                            if (!fillClean.contains("\"status\":\"filled\"")) {
                                finishManualActionFailure("Amount field not found inside modal $modalSelector — stopped WITHOUT crediting anyone.")
                                return@evaluateJavascript
                            }
                            val modalText = Regex("\"modalText\":\"(.*?)\"").find(fillClean)?.groupValues?.get(1) ?: ""
                            android.util.Log.d("WebViewLoginActivity", "Zong credit — modal=$modalSelector row=\"$matchedRowText\" modalShows=\"$modalText\"")

                            webView.evaluateJavascript(
                                "(function(){" +
                                        "  var modal = document.querySelector('$modalSelector');" +
                                        "  if(!modal) return 'modal_not_found';" +
                                        "  var btn = modal.querySelector('button[name=\"doNewCredit\"]');" +
                                        "  if(btn){ btn.click(); return 'submitted'; }" +
                                        "  return 'not_found';" +
                                        "})()"
                            ) { submitResultRaw ->
                                val submitResult = submitResultRaw.trim().removeSurrounding("\"")
                                if (submitResult == "submitted") {
                                    webView.postDelayed({ captureDealerTopupResult() }, 2500)
                                } else {
                                    finishManualActionFailure("Credit Account button not found inside modal $modalSelector")
                                }
                            }
                        }
                    }, 800)
                }
            }, 1800)
        }
    }

    private fun readZongFranchiseBalance(attempt: Int = 1) {
        webView.evaluateJavascript(
            "(function(){" +
                    "  var bodyText = document.body.innerText || document.body.textContent || '';" +
                    "  var m = bodyText.match(/Available Credit Rs\\.?\\s*(-?[0-9,]+(\\.[0-9]+)?)/i);" +
                    "  if (m) return JSON.stringify({found:true, value:m[1]});" +
                    "  var m2 = bodyText.match(/Account Credit[^0-9\\-]*(-?[0-9,]+(\\.[0-9]+)?)/i);" +
                    "  if (m2) return JSON.stringify({found:true, value:m2[1]});" +
                    "  return JSON.stringify({found:false, url: window.location.href, snippet: bodyText.substring(0,300)});" +
                    "})()"
        ) { resultRaw ->
            val clean = resultRaw
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\\\", "\\")
            val foundTrue = clean.contains("\"found\":true")
            val balance = if (foundTrue) {
                Regex("\"value\":\"(.*?)\"").find(clean)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            } else null

            if (balance != null) {
                val fieldName = if (targetZone.equals("Okara", ignoreCase = true)) "zongBalance" else "zongBalance_$targetZone"
                FranchiseBalanceManager.updateBalance("ZONG", balance, targetZone) { _ ->
                    FranchiseBalanceManager.checkAndNotifyLowBalance(this, "ZONG", balance, targetZone)
                }
                setResult(RESULT_OK, Intent().apply { putExtra("checked_balance", balance) })
                finish()
            } else {
                if (attempt < 8) {
                    val nextAttempt = attempt + 1
                    webView.postDelayed({
                        readZongFranchiseBalance(nextAttempt)
                    }, 700)
                } else {
                    val urlInfo = Regex("\"url\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: "unknown"
                    val snippet = Regex("\"snippet\":\"(.*?)\"").find(clean)?.groupValues?.get(1)?.take(200) ?: ""
                    finishManualActionFailure("Could not find Zong balance after 8 checks. Page: $urlInfo. Text seen: $snippet")
                }
            }
        }
    }

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
                    writeActivationResultToFirestore(false, ""); finish()
                }
            }
        }, 1000)
    }

    private fun switchToZongDealerPanel(dealerName: String) {
        val prefs = EncryptedSharedPreferences.create(
            this, "isp_panel_prefs",
            MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val accountsJson = prefs.getString("all_accounts_json", null)
        if (accountsJson.isNullOrEmpty()) {
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
            writeActivationResultToFirestore(false, ""); finish()
        }
    }

    private fun onZongProfileOpened() {
        if (manualAction != null) {
            if (zongActionClicked) {
                // Already clicked once this session — the redirect back
                // to this same profile page after a successful action
                // must NOT trigger a second click.
                return
            }
            zongActionClicked = true

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

                val incomplete = address.isEmpty() || phone.isEmpty()
                if (incomplete && attempt < 4) {
                    webView.postDelayed({ fetchZongCustomerDetails(attempt + 1) }, 700)
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

    private var eboneDetailsFetchDone = false

    private fun fetchEboneCustomerDetails(attempt: Int = 1) {
        if (eboneDetailsFetchDone) return

        val expectedId = autoActivateCustomerId
        if (expectedId != null) {
            val currentUrl = webView.url ?: ""
            val urlCustomerId = currentUrl
                .substringAfterLast("/clients/client/")
                .substringBefore("?")
                .trim()
                .trimEnd('/')
            if (urlCustomerId != expectedId) {
                android.util.Log.e(
                    "WebViewLoginActivity",
                    "Ebone details fetch: URL customer ($urlCustomerId) does not match requested ($expectedId) — aborting to avoid wrong data."
                )
                if (attempt < 3) {
                    webView.postDelayed({ fetchEboneCustomerDetails(attempt + 1) }, 1000)
                } else {
                    finishManualActionFailure("Could not load the requested customer's profile (Ebone showed a different customer)")
                }
                return
            }
        }

        val script = """
            (function(){
                var userId = '', address = '', phone = '';
                var rows = document.querySelectorAll('table.table-hover tbody tr');
                for (var i=0; i<rows.length; i++){
                    var th = rows[i].querySelector('th');
                    if (!th) continue;
                    var label = th.textContent.trim();
                    var tds = rows[i].querySelectorAll('td');
                    if (label.indexOf('UserID') > -1) {
                        if (tds[0]) userId = (tds[0].textContent || '').trim();
                    } else if (label.indexOf('Address') > -1 && label.indexOf('Email') === -1) {
                        if (tds[0]) address = (tds[0].textContent || '').trim();
                        if (tds[1]) {
                            var rawPhone = (tds[1].textContent || '').trim();
                            rawPhone = rawPhone.replace(/^\/+/, '');
                            var parts = rawPhone.split('/').filter(function(p){ return p.trim().length > 0; });
                            phone = parts.length > 0 ? parts[0].trim() : rawPhone.trim();
                        }
                    }
                }
                return JSON.stringify({userId:userId, address:address, phone:phone});
            })()
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            try {
                val clean = result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                val userId = Regex("\"userId\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val address = Regex("\"address\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val phone = normalizePakPhone(Regex("\"phone\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: "")

                if (expectedId != null && userId.isNotEmpty() && !userId.equals(expectedId, ignoreCase = true)) {
                    android.util.Log.e(
                        "WebViewLoginActivity",
                        "Ebone details fetch: table userId ($userId) does not match requested ($expectedId) — aborting."
                    )
                    if (attempt < 4) {
                        webView.postDelayed({ fetchEboneCustomerDetails(attempt + 1) }, 1000)
                    } else {
                        finishManualActionFailure("Could not confirm the correct customer's data on Ebone")
                    }
                    return@evaluateJavascript
                }

                val incomplete = address.isEmpty() || phone.isEmpty()
                if (incomplete && attempt < 4) {
                    webView.postDelayed({ fetchEboneCustomerDetails(attempt + 1) }, 1000)
                    return@evaluateJavascript
                }

                if (!eboneDetailsFetchDone && (userId.isNotEmpty() || address.isNotEmpty() || phone.isNotEmpty())) {
                    eboneDetailsFetchDone = true
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