package com.example.eboneadminpanel

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

/**
 * Isolated WebView flow for ZONG OKARA.
 * 100% STABLE LOGIC RESTORED FROM HISTORY.
 */
class ZongOkaraWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var loginAttemptInProgress = false
    private var loginDone = false
    private var balanceCheckAttempted = false
    private var dealerListLoadAttempted = false
    private var dealerTopupAttempted = false
    private var sourceTransactionId: String? = null

    private var manualAction: String? = null
    private var dealerEboneId: String? = null
    private var topupAmount: String? = null
    private var dealerInternalId: String? = null
    private var dealerDisplayName: String? = null
    private var dealerSearchName: String? = null
    private var autoActivateCustomerId: String? = null
    private var zongDetailsFetchDone = false
    private var isCustomerSearchStarted = false // NEW: Proper tracking flag
    private var customerListPrepared = false

    private val domain = "https://turbonet.zong.com.pk"
    private val loginUrl = "$domain/login.php"
    private val homeUrl = "$domain/index.php"
    private val dealerListUrl = "$domain/sub_dealers.php"

    private val sessionPrefsName = "isp_session_cookies"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_webview_login)
        window.statusBarColor = Color.parseColor("#1B5E20")
        window.decorView.systemUiVisibility = 0

        manualAction = intent.getStringExtra("manual_action")
        dealerEboneId = intent.getStringExtra("dealer_ebone_id")
        topupAmount = intent.getStringExtra("topup_amount")
        dealerInternalId = intent.getStringExtra("dealer_internal_id")
        dealerDisplayName = intent.getStringExtra("dealer_display_name")
        dealerSearchName = intent.getStringExtra("dealer_search_name")
        autoActivateCustomerId = intent.getStringExtra("auto_activate_customer_id")
        sourceTransactionId = intent.getStringExtra("source_transaction_id")

        webView = findViewById(R.id.loginWebView)

        findViewById<Button>(R.id.accountSwitchButton).setOnClickListener {
            Toast.makeText(this, "Zong Okara account is controlled from ISP Panel Settings.", Toast.LENGTH_SHORT).show()
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.saveFormData = true
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 6.0) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                CookieManager.getInstance().flush()

                // Crucial: remove all target="_blank" so links open in SAME WebView
                webView.evaluateJavascript(
                    "(function(){" +
                            "  var links = document.querySelectorAll('a[target=\"_blank\"]');" +
                            "  for(var i=0; i<links.length; i++) { links[i].removeAttribute('target'); }" +
                            "})()", null
                )

                handlePage(url)

                if (!loginDone && !loginAttemptInProgress && url.contains("login.php")) {
                    webView.postDelayed({ tryAutoLogin() }, 500)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false // Handle all in this WebView
            }
        }

        loadInitialPage()
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) webView.resumeTimers()
    }

    private fun handlePage(url: String) {
        when {
            url.contains("login.php") -> {
                loginDone = false
                isCustomerSearchStarted = false // Reset on login
                customerListPrepared = false
                if (!loginAttemptInProgress) tryAutoLogin()
            }

            url.contains("customer_portal.php") -> {
                if (manualAction == null) {
                    if (isCustomerSearchStarted) {
                        Log.d("ZongOkaraWebView", "Customer profile reached. Fetching details...")
                        webView.postDelayed({ fetchZongCustomerDetails() }, 1500)
                    } else {
                        Log.d("ZongOkaraWebView", "Landed on franchise profile. Jumping to customers.php")
                        webView.loadUrl("https://turbonet.zong.com.pk/customers.php")
                    }
                }
            }

            url.contains("customers.php") && manualAction == null -> {
                isCustomerSearchStarted = true
                if (!customerListPrepared) {
                    customerListPrepared = true
                    prepareCustomerList()
                }
            }

            url.contains("sub_dealers.php") && manualAction == "DEALER_TOPUP" -> {
                if (!dealerTopupAttempted) {
                    dealerTopupAttempted = true
                    webView.postDelayed({ searchAndClickZongDealer(dealerSearchName ?: dealerDisplayName ?: "") }, 800)
                }
            }

            url.contains("subdealer_portal.php") && manualAction == "DEALER_TOPUP" -> {
                webView.postDelayed({ openZongAddCreditAndSubmit(topupAmount ?: "") }, 800)
            }

            url.contains("turbonet.zong.com.pk") && !url.contains("login.php") -> {
                loginDone = true
                if (manualAction == "DEALER_TOPUP") {
                    if (!dealerListLoadAttempted) {
                        dealerListLoadAttempted = true
                        webView.loadUrl(dealerListUrl)
                    }
                } else if (manualAction == "CHECK_BALANCE") {
                    if (!balanceCheckAttempted) {
                        balanceCheckAttempted = true
                        webView.postDelayed({ readZongOkaraBalance() }, 700)
                    }
                } else if (manualAction == null && !url.contains("customers.php") && !url.contains("customer_portal.php")) {
                    Log.d("ZongOkaraWebView", "Redirecting to customers.php")
                    webView.loadUrl("https://turbonet.zong.com.pk/customers.php")
                }
            }
        }
    }

    private fun prepareCustomerList() {
        Log.d("ZongOkaraWebView", "Preparing customer list with 1000 rows")
        webView.evaluateJavascript(
            "(function(){" +
                    "var sel=document.querySelector('select[name=\"managercustomers_length\"]');" +
                    "if(!sel) return 'length_selector_not_found';" +
                    "sel.value='1000';" +
                    "sel.dispatchEvent(new Event('change',{bubbles:true}));" +
                    "return 'prepared';" +
                    "})()"
        ) { result ->
            Log.d("ZongOkaraWebView", "Customer list preparation: $result")
            if (result.contains("length_selector_not_found")) {
                customerListPrepared = false
                webView.postDelayed({
                    if (!customerListPrepared) {
                        customerListPrepared = true
                        prepareCustomerList()
                    }
                }, 700)
                return@evaluateJavascript
            }
            autoActivateCustomerId?.let { customerId ->
                webView.postDelayed({ searchZongCustomer(customerId) }, 800)
            }
        }
    }

    private fun searchZongCustomer(customerId: String) {
        val script = """
            (function(){
                var inp = document.querySelector('input[aria-controls="managercustomers"], .dataTables_filter input');
                if(inp){
                    inp.focus();
                    inp.value = '$customerId';
                    inp.dispatchEvent(new Event('input',{bubbles:true}));
                    inp.dispatchEvent(new Event('keyup',{bubbles:true}));
                    return 'searching';
                }
                return 'not_found';
            })()
        """.trimIndent()
        
        webView.evaluateJavascript(script) { res ->
            Log.d("ZongOkaraWebView", "Search result: $res")
            // Longer delay to allow table to filter correctly
            webView.postDelayed({
                webView.evaluateJavascript(
                    "(function(){" +
                            "  var links = document.querySelectorAll('a[href*=\"customer_portal.php\"]');" +
                            "  var found = false;" +
                            "  for(var i=0; i<links.length; i++){" +
                            "    var t = (links[i].innerText || links[i].textContent || '').trim();" +
                            "    if(t === '$customerId'){ links[i].click(); found = true; break; }" +
                            "  }" +
                            "  return found ? 'clicked' : 'not_found_on_page';" +
                            "})()"
                ) { result ->
                    Log.d("ZongOkaraWebView", "Profile link click status: $result")
                }
            }, 2000)
        }
    }

    private fun fetchZongCustomerDetails(attempt: Int = 1) {
        if (zongDetailsFetchDone) return
        val script = """
            (function(){
                var userId = '', address = '', phone = '';
                var tables = document.querySelectorAll('table.skills');
                for (var t=0; t<tables.length; t++){
                    var rows = tables[t].querySelectorAll('tbody tr');
                    var hasFullName = false;
                    for (var i=0; i<rows.length; i++){
                        var checkCell = rows[i].querySelector('td.item');
                        if (checkCell && checkCell.textContent.trim() === 'Full Name') {
                            hasFullName = true;
                            break;
                        }
                    }
                    if (!hasFullName) continue;
                    for (var i=0; i<rows.length; i++){
                        var itemTd = rows[i].querySelector('td.item');
                        if (!itemTd) continue;
                        var label = itemTd.textContent.trim();
                        var cells = rows[i].querySelectorAll('td');
                        var valueCell = cells[cells.length - 1];
                        var value = valueCell ? valueCell.textContent.trim() : '';
                        if (label === 'PPPoE Auth User') { userId = value; }
                        else if (label.indexOf('Address') > -1) { address = value; }
                        else if (label === 'Mobile') { phone = value; }
                    }
                    break;
                }
                return JSON.stringify({userId:userId, address:address, phone:phone});
            })()
        """.trimIndent()
        
        webView.evaluateJavascript(script) { result ->
            try {
                val clean = result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                val userId = Regex("\"userId\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val address = Regex("\"address\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val rawPhone = Regex("\"phone\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""
                val phone = normalizePakPhone(rawPhone)

                val detailsAreIncomplete = address.isEmpty() || phone.isEmpty()
                if (detailsAreIncomplete && attempt < 4) {
                    webView.postDelayed({ fetchZongCustomerDetails(attempt + 1) }, 700)
                    return@evaluateJavascript
                }

                if (userId.isNotEmpty() || address.isNotEmpty() || phone.isNotEmpty()) {
                    zongDetailsFetchDone = true
                    val resultIntent = Intent().apply {
                        putExtra("fetched_user_id", userId)
                        putExtra("fetched_address", address)
                        putExtra("fetched_phone", phone)
                        putExtra("manual_action_success", true)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                } else if (attempt < 4) {
                    webView.postDelayed({ fetchZongCustomerDetails(attempt + 1) }, 1000)
                }
            } catch (e: Exception) {}
        }
    }

    private fun loadInitialPage() {
        // STRICTLY use Okara (Abbas046) credentials
        val username = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", "Okara")
        val password = IspPanelSettingsActivity.getSavedPassword(this, "ZONG", "Okara")
        
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Log.e("ZongOkaraWebView", "Okara credentials missing!")
            webView.loadUrl(loginUrl)
            return
        }

        val savedCookie = getSessionCookie()
        CookieManager.getInstance().removeAllCookies {
            if (savedCookie.isNotBlank()) {
                Log.d("ZongOkaraWebView", "Restoring Okara session cookie")
                savedCookie.split(";").forEach { part -> CookieManager.getInstance().setCookie(domain, part.trim()) }
                CookieManager.getInstance().flush()
                webView.loadUrl(homeUrl)
            } else {
                Log.d("ZongOkaraWebView", "No saved cookie, loading login page")
                webView.loadUrl(loginUrl)
            }
        }
    }

    private fun tryAutoLogin(attempt: Int = 1) {
        if (loginAttemptInProgress) return
        loginAttemptInProgress = true

        // Ensure we only use Okara account here
        val username = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", "Okara")
        val password = IspPanelSettingsActivity.getSavedPassword(this, "ZONG", "Okara")

        webView.evaluateJavascript(
            "(function(){" +
                    "var u=document.querySelector('input[name=username],#username');" +
                    "var p=document.querySelector('input[name=password],#password');" +
                    "var b=document.querySelector('button[type=submit],#send,.btn-login');" +
                    "if(!u||!p)return 'not_ready';" +
                    "u.value='$username'; p.value='$password';" +
                    "if(b){ b.click(); return 'submitted'; }" +
                    "return 'no_button';" +
                    "})()"
        ) { raw ->
            loginAttemptInProgress = false
            if (raw.contains("not_ready") && attempt < 5) {
                webView.postDelayed({ tryAutoLogin(attempt + 1) }, 1000)
            }
        }
    }

    private fun searchAndClickZongDealer(searchName: String, attempt: Int = 1) {
        val cleanName = searchName.substringBefore("(").trim()
        val searchJs = JSONObjectEscape.forJavaScript(cleanName)
        webView.evaluateJavascript(
            "(function(){" +
                    "var inp = document.querySelector('input[type=search][aria-controls=\"table3\"]');" +
                    "if(!inp) return 'not_found';" +
                    "inp.value = '$searchJs'; inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "return 'done';" +
                    "})()"
        ) { raw ->
            if (raw.contains("done")) {
                webView.postDelayed({ 
                    webView.evaluateJavascript("(function(){ var links=document.querySelectorAll(\"a[href*='subdealer_portal.php']\"); for(var i=0;i<links.length;i++){ if(links[i].innerText.toLowerCase().indexOf('$cleanName'.toLowerCase())>-1){ links[i].click(); return 'clicked'; } } return 'not_found'; })()") { }
                }, 1500)
            } else if (attempt < 5) {
                webView.postDelayed({ searchAndClickZongDealer(searchName, attempt + 1) }, 1000)
            }
        }
    }

    private fun openZongAddCreditAndSubmit(amount: String) {
        webView.evaluateJavascript("(function(){ var btns=document.querySelectorAll('button[data-target=\"#credit\"]'); if(btns.length>0){ btns[0].click(); return 'clicked'; } return 'not_found'; })()") {
            webView.postDelayed({
                webView.evaluateJavascript("(function(){ var inp=document.querySelector('input[name=\"credit_amount\"]'); if(inp){ inp.value='$amount'; document.querySelector('button[name=\"doNewCredit\"]').click(); return 'submitted'; } return 'not_found'; })()") {
                    webView.postDelayed({ captureDealerTopupResult() }, 3000)
                }
            }, 1000)
        }
    }

    private fun readZongOkaraBalance() {
        webView.evaluateJavascript("(function(){ var t=document.body.innerText; var m=t.match(/Available Credit Rs\\.?\\s*([0-9,.]+)/i); return m ? m[1] : null; })()") { res ->
            val balance = res.replace("\"", "").replace(",", "").toDoubleOrNull()
            if (balance != null) {
                FranchiseBalanceManager.updateBalance("ZONG", balance, "Okara") {
                    FranchiseBalanceManager.showUpdateNotification(this, "ZONG", balance, "Okara")
                    setResult(RESULT_OK); finish()
                }
            }
        }
    }

    private fun captureDealerTopupResult() {
        webView.evaluateJavascript("(function(){ return document.body.innerText.substring(0,500); })()") { clean ->
            val logEntry = mapOf("dealerName" to (dealerDisplayName ?: ""), "panel" to "ZONG", "amount" to (topupAmount?.toDoubleOrNull() ?: 0.0), "submittedAt" to System.currentTimeMillis(), "zone" to "Okara")
            db.collection("dealerPayments").add(logEntry).addOnCompleteListener { finish() }
        }
    }

    private fun normalizePakPhone(raw: String): String {
        var p = raw.trim().replace(" ", "").replace("-", "").replace("+92", "0")
        if (p.startsWith("92")) p = "0" + p.substring(2)
        return p
    }

    private fun finishFailure(reason: String) {
        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun getSessionCookie(): String {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(this, sessionPrefsName, masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        return prefs.getString("ZONG_Okara", "") ?: ""
    }

    private fun saveSessionCookie(cookie: String) {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(this, sessionPrefsName, masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        prefs.edit().putString("ZONG_Okara", cookie).apply()
    }

    private object JSONObjectEscape {
        fun forJavaScript(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
    }
}
