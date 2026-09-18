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
 *
 * Handles:
 *  - Zong Okara login (Franchise)
 *  - Zong Okara dealer top-up (Add Credit)
 *  - Zong Okara franchise balance check (Auto-update after top-up)
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
                if (url.isNullOrBlank()) return

                CookieManager.getInstance().flush()

                webView.evaluateJavascript(
                    "document.querySelectorAll('a[target=\"_blank\"]').forEach(function(a){a.removeAttribute('target');});",
                    null
                )

                handlePage(url)

                if (!loginDone && !loginAttemptInProgress &&
                    url.contains("login.php", ignoreCase = true)
                ) {
                    webView.postDelayed({ tryAutoLogin() }, 500)
                }
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
            url.contains("login.php", ignoreCase = true) -> {
                loginDone = false
                if (!loginAttemptInProgress) {
                    webView.postDelayed({ tryAutoLogin() }, 300)
                }
            }

            url.contains("customer_portal.php", ignoreCase = true) -> {
                if (manualAction == null || manualAction == "RELIEF_VIEW") {
                    webView.postDelayed({ fetchZongCustomerDetails() }, 1500)
                }
            }

            url.contains("sub_dealers.php", ignoreCase = true) &&
                    manualAction == "DEALER_TOPUP" -> {
                if (!dealerTopupAttempted) {
                    dealerTopupAttempted = true
                    webView.postDelayed({
                        searchAndClickZongDealer(
                            dealerSearchName ?: dealerDisplayName ?: dealerEboneId ?: ""
                        )
                    }, 800)
                }
            }

            url.contains("subdealer_portal.php", ignoreCase = true) &&
                    manualAction == "DEALER_TOPUP" -> {
                webView.postDelayed({
                    openZongAddCreditAndSubmit(topupAmount ?: "")
                }, 800)
            }

            url.contains("turbonet.zong.com.pk", ignoreCase = true) &&
                    !url.contains("login.php", ignoreCase = true) &&
                    !url.contains("sub_dealers.php", ignoreCase = true) &&
                    !url.contains("subdealer_portal.php", ignoreCase = true) -> {

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
                } else if (autoActivateCustomerId != null) {
                    // NEW: handling for complaint creation / user search
                    Log.d("ZongOkaraWebView", "Search requested for customer: $autoActivateCustomerId")
                    webView.postDelayed({ searchZongCustomer(autoActivateCustomerId!!) }, 500)
                }
            }
        }
    }

    private fun searchZongCustomer(customerId: String) {
        val script = """
            (function(){
                var sel = document.querySelector('select[name="managercustomers_length"]');
                if(sel){ sel.value = '1000'; sel.dispatchEvent(new Event('change', {bubbles:true})); }
                
                var inp = document.querySelector('input[aria-controls="managercustomers"], .dataTables_filter input');
                if(inp){
                    inp.focus();
                    inp.value = '$customerId';
                    inp.dispatchEvent(new Event('input', {bubbles:true}));
                    inp.dispatchEvent(new Event('keyup', {bubbles:true}));
                    return 'searching';
                }
                return 'search_box_not_found';
            })()
        """.trimIndent()
        
        webView.evaluateJavascript(script) { res ->
            Log.d("ZongOkaraWebView", "Search status: $res")
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
    }

    private fun loadInitialPage() {
        val username = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", "Okara")
        val password = IspPanelSettingsActivity.getSavedPassword(this, "ZONG", "Okara")

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Toast.makeText(this, "Zong Okara account not configured.", Toast.LENGTH_LONG).show()
            webView.loadUrl(loginUrl)
            return
        }

        val savedCookie = getSessionCookie()

        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()

            if (savedCookie.isNotBlank()) {
                savedCookie.split(";").forEach { part ->
                    CookieManager.getInstance().setCookie(domain, part.trim())
                }
                CookieManager.getInstance().flush()
                webView.loadUrl(homeUrl)
            } else {
                webView.loadUrl(loginUrl)
            }
        }
    }

    private fun tryAutoLogin(attempt: Int = 1) {
        if (loginAttemptInProgress) return
        loginAttemptInProgress = true

        val username = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", "Okara")
        val password = IspPanelSettingsActivity.getSavedPassword(this, "ZONG", "Okara")

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            loginAttemptInProgress = false
            finishFailure("Zong Okara login account not found")
            return
        }

        webView.postDelayed({
            val userJs = JSONObjectEscape.forJavaScript(username)
            val passJs = JSONObjectEscape.forJavaScript(password)

            webView.evaluateJavascript(
                "(function(){" +
                        "var u=document.querySelector('input[type=text],input[name=username],input[name=email],#username,#email');" +
                        "var p=document.querySelector('input[type=password],#password');" +
                        "var b=document.querySelector('#send,button[type=submit],input[type=submit],.btn-login,#login-btn');" +
                        "if(!u||!p)return 'fields_not_ready';" +
                        "u.value='$userJs';" +
                        "u.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "u.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "p.value='$passJs';" +
                        "p.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "p.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "if(b){b.click();return 'submitted';}" +
                        "var f=p.closest('form');" +
                        "if(f){f.submit();return 'submitted_form';}" +
                        "return 'no_button';" +
                        "})()"
            ) { raw ->
                val result = raw.trim().removeSurrounding("\"")

                if (result == "fields_not_ready" && attempt < 8) {
                    loginAttemptInProgress = false
                    tryAutoLogin(attempt + 1)
                } else {
                    loginAttemptInProgress = false
                    loginDone = result == "submitted" || result == "submitted_form"
                }
            }
        }, if (attempt == 1) 300L else 700L)
    }

    private fun logZongOkaraTopup(message: String) {
        Log.d("ZONG_OKARA_TOPUP", message)
    }

    private fun searchAndClickZongDealer(searchName: String, attempt: Int = 1) {
        logZongOkaraTopup("Starting dealer search: $searchName, attempt=$attempt")
        if (searchName.isBlank()) {
            finishTopupFailure("Zong dealer name is missing")
            return
        }

        // Strip zone if present (e.g. "Abbas046 (Okara)" -> "Abbas046")
        val cleanName = searchName.substringBefore("(").trim()
        val searchJs = JSONObjectEscape.forJavaScript(cleanName)

        webView.evaluateJavascript(
            "(function(){" +
                    "var inp = document.querySelector('input[type=search][aria-controls=\"table3\"], input[aria-controls=\"table3\"]');" +
                    "if(!inp) return 'search_not_found';" +
                    "inp.focus(); inp.value = '$searchJs';" +
                    "inp.dispatchEvent(new Event('input', {bubbles:true}));" +
                    "inp.dispatchEvent(new Event('keyup', {bubbles:true}));" +
                    "return 'search_done';" +
                    "})()"
        ) { raw ->
            val res = raw.trim().removeSurrounding("\"")
            if (res != "search_done") {
                if (attempt < 10) webView.postDelayed({ searchAndClickZongDealer(searchName, attempt + 1) }, 1000)
                else finishTopupFailure("Zong dealer search box not found")
                return@evaluateJavascript
            }

            webView.postDelayed({ clickZongDealerLink(cleanName, 1) }, 2000)
        }
    }

    private fun clickZongDealerLink(searchName: String, attempt: Int) {
        logZongOkaraTopup("Finding dealer link: $searchName, attempt=$attempt")
        val searchJs = JSONObjectEscape.forJavaScript(searchName.lowercase(Locale.ROOT))

        webView.evaluateJavascript(
            "(function(){" +
                    "var links = document.querySelectorAll(\"a[href*='subdealer_portal.php']\");" +
                    "var matches = [];" +
                    "for(var i=0; i<links.length; i++){" +
                    "  var t = (links[i].innerText||'').replace(/\\s+/g, ' ').trim().toLowerCase();" +
                    "  if(t.indexOf('$searchJs') > -1) matches.push(links[i]);" +
                    "}" +
                    "if(matches.length === 0) return 'not_found';" +
                    "var target = matches[0];" +
                    "target.removeAttribute('target');" +
                    "target.click();" +
                    "return 'clicked';" +
                    "})()"
        ) { raw ->
            val result = raw.trim().removeSurrounding("\"")
            if (result == "clicked") return@evaluateJavascript

            if (attempt < 12) {
                webView.postDelayed({ clickZongDealerLink(searchName, attempt + 1) }, 1000)
            } else {
                finishTopupFailure("Could not find Zong dealer link for \"$searchName\"")
            }
        }
    }

    private fun openZongAddCreditAndSubmit(amount: String, attempt: Int = 1) {
        logZongOkaraTopup("Opening Add Credit, amount=$amount, attempt=$attempt")
        if (amount.isBlank()) {
            finishTopupFailure("Top-up amount is missing")
            return
        }

        webView.evaluateJavascript(
            "(function(){" +
                    "var btns=document.querySelectorAll('button[data-toggle=\"modal\"][data-target=\"#credit\"]');" +
                    "for(var i=0;i<btns.length;i++){" +
                    "if((btns[i].innerText||'').indexOf('Add Credit')>-1){btns[i].click();return 'clicked';}" +
                    "}" +
                    "return 'not_found';" +
                    "})()"
        ) { openRaw ->
            if (openRaw.trim().removeSurrounding("\"") != "clicked") {
                if (attempt < 8) {
                    webView.postDelayed({ openZongAddCreditAndSubmit(amount, attempt + 1) }, 700)
                } else finishTopupFailure("Zong Add Credit button not found")
                return@evaluateJavascript
            }

            val amountJs = JSONObjectEscape.forJavaScript(amount)

            webView.postDelayed({
                webView.evaluateJavascript(
                    "(function(){" +
                            "var inp=document.querySelector('#credit_amount[name=\"credit_amount\"]');" +
                            "if(!inp)return 'not_found';" +
                            "inp.focus();inp.value='$amountJs';" +
                            "inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                            "inp.dispatchEvent(new Event('change',{bubbles:true}));" +
                            "return 'filled';" +
                            "})()"
                ) { amountRaw ->
                    if (amountRaw.trim().removeSurrounding("\"") != "filled") {
                        finishTopupFailure("Zong credit_amount field not found")
                        return@evaluateJavascript
                    }

                    webView.postDelayed({
                        webView.evaluateJavascript(
                            "(function(){" +
                                    "var btn=document.querySelector('button[type=submit][name=\"doNewCredit\"]');" +
                                    "if(!btn)return 'not_found';" +
                                    "btn.click();return 'submitted';" +
                                    "})()"
                        ) { submitRaw ->
                            if (submitRaw.trim().removeSurrounding("\"") == "submitted") {
                                webView.postDelayed({ captureDealerTopupResult() }, 2500)
                            } else {
                                finishTopupFailure("Zong Credit Account button not found")
                            }
                        }
                    }, 600)
                }
            }, 700)
        }
    }

    private fun readZongOkaraBalance(attempt: Int = 1) {
        webView.evaluateJavascript(
            "(function(){" +
                    "var body=document.body ? (document.body.innerText||document.body.textContent||'') : '';" +
                    "var m=body.match(/Available\\s+Credit\\s+Rs\\.?\\s*(-?[0-9,]+(?:\\.[0-9]+)?)/i);" +
                    "if(m)return JSON.stringify({found:true,value:m[1]});" +
                    "var m2=body.match(/Account\\s+Credit[^0-9-]*(-?[0-9,]+(?:\\.[0-9]+)?)/i);" +
                    "if(m2)return JSON.stringify({found:true,value:m2[1]});" +
                    "return JSON.stringify({found:false,url:window.location.href});" +
                    "})()"
        ) { raw ->
            val clean = raw.trim()
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\n", " ")

            val balance = Regex("\"value\":\"(.*?)\"")
                .find(clean)
                ?.groupValues
                ?.get(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()

            if (balance != null) {
                FranchiseBalanceManager.updateBalance("ZONG", balance, "Okara") { _ ->
                    FranchiseBalanceManager.checkAndNotifyLowBalance(
                        this,
                        "ZONG",
                        balance,
                        "Okara"
                    )
                    setResult(
                        RESULT_OK,
                        Intent().apply {
                            putExtra("checked_balance", balance)
                            putExtra("selected_isp", "ZONG")
                            putExtra("target_zone", "Okara")
                        }
                    )
                    finish()
                }
            } else if (attempt < 10) {
                webView.postDelayed({
                    readZongOkaraBalance(attempt + 1)
                }, 1000)
            } else {
                finishFailure("Zong Okara کا بیلنس نہیں مل سکا۔ براہ کرم دستی چیک کریں۔")
            }
        }
    }

    private fun captureDealerTopupResult() {
        logZongOkaraTopup("Capturing top-up result")
        webView.evaluateJavascript(
            "(function(){" +
                    "var b=document.querySelector('.box-body');" +
                    "var text=(b?b.innerText:document.body.innerText)||'';" +
                    "var dealerBalance='';" +
                    "var labels=document.querySelectorAll('h5.card-title');" +
                    "for(var i=0;i<labels.length;i++){" +
                    "if((labels[i].innerText||'').trim()==='Dealer Balance'){" +
                    "var v=labels[i].parentElement ? labels[i].parentElement.querySelector('span.h2') : null;" +
                    "if(v)dealerBalance=v.innerText.trim();" +
                    "break;" +
                    "}" +
                    "}" +
                    "return JSON.stringify({text:text.substring(0,800),url:window.location.href,dealerBalance:dealerBalance});" +
                    "})()"
        ) { raw ->
            val clean = raw.trim()
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\\\", "\\")

            val dealerBalanceAfter =
                Regex("\"dealerBalance\":\"(.*?)\"")
                    .find(raw)
                    ?.groupValues
                    ?.get(1)
                    ?: ""

            val amountValue = topupAmount?.toDoubleOrNull() ?: 0.0

            val logEntry = mapOf(
                "dealerId" to (dealerInternalId ?: ""),
                "dealerName" to (dealerDisplayName ?: dealerSearchName ?: ""),
                "panel" to "ZONG",
                "ispDealerId" to (dealerEboneId ?: ""),
                "amount" to amountValue,
                "submittedAt" to System.currentTimeMillis(),
                "resultText" to clean.take(500),
                "dealerBalanceAfter" to dealerBalanceAfter,
                "sourceTransactionId" to (sourceTransactionId ?: ""),
                "zone" to "Okara"
            )

            db.collection("dealerPayments").add(logEntry)

            val smsTx = sourceTransactionId?.takeIf { it.isNotBlank() }
            if (smsTx != null) {
                db.collection("dealerTransactions").document(smsTx)
                    .update(
                        mapOf(
                            "status" to "COMPLETED",
                            "transferStatus" to "TRANSFERRED",
                            "transferredAt" to System.currentTimeMillis(),
                            "transferResultText" to clean.take(500)
                        )
                    )
                    .addOnCompleteListener {
                        // AUTO-UPDATE BALANCE AFTER TOPUP
                        continueAfterDealerTopupSuccess()
                    }
            } else {
                setResult(
                    RESULT_OK,
                    Intent().apply {
                        putExtra("dealer_topup_submitted", true)
                        putExtra("selected_isp", "ZONG")
                        putExtra("target_zone", "Okara")
                    }
                )
                continueAfterDealerTopupSuccess()
            }
        }
    }

    private fun continueAfterDealerTopupSuccess() {
        manualAction = "CHECK_BALANCE"
        balanceCheckAttempted = false
        webView.loadUrl(homeUrl)
    }

    private fun finishTopupFailure(reason: String) {
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra("dealer_topup_submitted", false)
                putExtra("selected_isp", "ZONG")
                putExtra("target_zone", "Okara")
                putExtra("error_reason", reason)
            }
        )
        finish()
    }

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
                if (incomplete && attempt < 5) {
                    webView.postDelayed({ fetchZongCustomerDetails(attempt + 1) }, 800)
                    return@evaluateJavascript
                }

                if (!zongDetailsFetchDone && (userId.isNotEmpty() || address.isNotEmpty() || phone.isNotEmpty())) {
                    zongDetailsFetchDone = true
                    val resultIntent = Intent().apply {
                        putExtra("fetched_user_id", userId)
                        putExtra("fetched_address", address)
                        putExtra("fetched_phone", phone)
                        putExtra("manual_action_success", true)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            } catch (e: Exception) {
                Log.e("ZongOkaraWebView", "Error in fetchZongCustomerDetails: ${e.message}")
            }
        }
    }

    private fun normalizePakPhone(raw: String): String {
        var p = raw.trim().replace(" ", "").replace("-", "")
        return when {
            p.startsWith("+92") -> "0" + p.substring(3)
            p.startsWith("92") && p.length > 10 -> "0" + p.substring(2)
            else -> p
        }
    }

    private fun finishFailure(reason: String) {
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra("checked_balance", -1.0)
                putExtra("selected_isp", "ZONG")
                putExtra("target_zone", "Okara")
                putExtra("error_reason", reason)
            }
        )
        finish()
    }

    private fun getSessionCookie(): String {
        val prefs = securePrefs(sessionPrefsName)
        return prefs.getString("ZONG_Okara", "") ?: ""
    }

    private fun saveSessionCookie() {
        val cookie = CookieManager.getInstance().getCookie(domain) ?: return
        securePrefs(sessionPrefsName)
            .edit()
            .putString("ZONG_Okara", cookie)
            .apply()
    }

    private fun securePrefs(name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return try {
            EncryptedSharedPreferences.create(
                this,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            getSharedPreferences(name, MODE_PRIVATE)
        }
    }

    private object JSONObjectEscape {
        fun forJavaScript(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029")
        }
    }
}
