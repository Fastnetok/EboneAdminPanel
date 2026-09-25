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
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Isolated WebView flow for ZONG RENALA.
 * 100% identical login and balance reading logic to ZONG OKARA.
 */
class ZongRenalaWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var loginAttemptInProgress = false
    private var loginDone = false
    private var loginSubmitted = false
    private var balanceCheckAttempted = false
    private var dealerTopupStep = 0 // 0: Start, 1: Searching, 2: Filling, 3: Submitted
    private var sourceTransactionId: String? = null

    private var manualAction: String? = null
    private var dealerEboneId: String? = null
    private var topupAmount: String? = null
    private var dealerInternalId: String? = null
    private var dealerDisplayName: String? = null
    private var dealerSearchName: String? = null
    private var debugTapInspectorEnabled = false

    companion object {
        private const val DOMAIN = "https://turbonet.zong.com.pk"
        private const val LOGIN_URL = "$DOMAIN/login.php"
        private const val HOME_URL = "$DOMAIN/index.php"
        private const val DEALER_LIST_URL = "$DOMAIN/sub_dealers.php"
        private const val ZONE = "Renala"
    }

    private val sessionPrefsName = "isp_session_cookies"

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

    private fun getRenalaSessionCookie(): String =
        securePrefs(sessionPrefsName).getString("ZONG_Renala", "") ?: ""

    private fun saveRenalaSessionCookie() {
        val cookie = CookieManager.getInstance().getCookie(DOMAIN) ?: return
        if (cookie.isNotBlank()) {
            securePrefs(sessionPrefsName)
                .edit()
                .putString("ZONG_Renala", cookie)
                .apply()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        manualAction = intent.getStringExtra("manual_action")
        dealerEboneId = intent.getStringExtra("dealer_ebone_id")
        topupAmount = intent.getStringExtra("topup_amount")
        dealerInternalId = intent.getStringExtra("dealer_internal_id")
        dealerDisplayName = intent.getStringExtra("dealer_display_name")
        dealerSearchName = intent.getStringExtra("dealer_search_name")
        sourceTransactionId = intent.getStringExtra("source_transaction_id")
        debugTapInspectorEnabled = intent.getBooleanExtra("debug_tap_inspector", false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        webView = WebView(this)
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        configureWebView()
        loadRenalaAccount()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString =
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        }

        if (debugTapInspectorEnabled) {
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onTap(info: String) {
                    runOnUiThread {
                        Log.d("TapInspector", info)
                    }
                }
            }, "TapInspector")
        }

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url.isNullOrBlank()) return

                CookieManager.getInstance().flush()

                handlePage(url)

                if (!loginDone && !loginAttemptInProgress && !loginSubmitted && url.contains("login.php", ignoreCase = true)) {
                    webView.postDelayed({ tryLogin() }, 500)
                }
            }
        }
    }

    private fun loadRenalaAccount() {
        val username = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", ZONE)
        val password = IspPanelSettingsActivity.getSavedPassword(this, "ZONG", ZONE)

        Log.d("ZongRenalaWebView", "Zong Renala → Login Started → Renala URL ($LOGIN_URL) → User: $username")

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Toast.makeText(this, "Zong Renala franchise account is not configured.", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // STRICT COOKIE PURGE: Erase any leftover cookies from other zones/sessions
        val cm = CookieManager.getInstance()
        cm.removeSessionCookies(null)
        cm.removeAllCookies(null)
        cm.flush()

        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
        WebStorage.getInstance().deleteAllData()

        val savedCookie = getRenalaSessionCookie()

        loginDone = false
        loginAttemptInProgress = false
        loginSubmitted = false

        if (savedCookie.isNotBlank()) {
            Log.d("ZongRenalaWebView", "Zong Renala → Restoring Renala session cookie")
            savedCookie.split(";").forEach { part ->
                if (part.isNotBlank()) {
                    cm.setCookie(DOMAIN, part.trim())
                }
            }
            cm.flush()
            webView.loadUrl(HOME_URL)
        } else {
            Log.d("ZongRenalaWebView", "Zong Renala → No saved cookie, loading login page")
            webView.loadUrl(LOGIN_URL)
        }
    }

    private fun handlePage(url: String) {
        when {
            url.contains("login.php", ignoreCase = true) -> {
                Log.d("ZongRenalaWebView", "Zong Renala → Login Page Detected")
                loginDone = false
                loginSubmitted = false
                loginAttemptInProgress = false
                dealerTopupStep = 0
                balanceCheckAttempted = false
                if (!loginAttemptInProgress) tryLogin()
            }

            url.contains("turbonet.zong.com.pk", ignoreCase = true) && !url.contains("login.php", ignoreCase = true) -> {
                // ACCOUNT ISOLATION GUARD: Ensure we didn't land on Okara's session
                val expectedUsername = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", ZONE)
                val okaraUsername = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", "Okara")
                if (!expectedUsername.isNullOrBlank() && !okaraUsername.isNullOrBlank() && !okaraUsername.equals(expectedUsername, ignoreCase = true)) {
                    webView.evaluateJavascript("(function(){ return document.body ? document.body.innerText.substring(0, 1500) : ''; })()") { bodyText ->
                        val text = bodyText ?: ""
                        if (text.contains(okaraUsername, ignoreCase = true)) {
                            Log.w("ZongRenalaWebView", "Zong Renala → ACCOUNT ISOLATION VIOLATION: Detected Okara session ($okaraUsername) in Renala WebView! Purging cookies and forcing re-login.")
                            val cm = CookieManager.getInstance()
                            cm.removeSessionCookies(null)
                            cm.removeAllCookies(null)
                            cm.flush()
                            webView.clearCache(true)
                            webView.clearHistory()
                            loginDone = false
                            loginSubmitted = false
                            dealerTopupStep = 0
                            balanceCheckAttempted = false
                            webView.loadUrl(LOGIN_URL)
                            return@evaluateJavascript
                        }
                        Log.d("ZongRenalaWebView", "Zong Renala → Login Success → Proceeding with page")
                        proceedWithRenalaPage(url)
                    }
                } else {
                    Log.d("ZongRenalaWebView", "Zong Renala → Login Success → Proceeding with page")
                    proceedWithRenalaPage(url)
                }
            }
        }
    }

    private fun proceedWithRenalaPage(url: String) {
        loginDone = true
        
        val current = CookieManager.getInstance().getCookie(DOMAIN)
        if (!current.isNullOrBlank()) saveRenalaSessionCookie()

        if (manualAction == "DEALER_TOPUP") {
            if (dealerTopupStep == 0 && !url.contains("sub_dealers.php", ignoreCase = true) && !url.contains("subdealer_portal.php", ignoreCase = true)) {
                Log.d("ZongRenalaWebView", "Zong Renala → Starting Topup flow from Dashboard.")
                webView.loadUrl(DEALER_LIST_URL)
            }
        } else if (manualAction == "CHECK_BALANCE") {
            if (!balanceCheckAttempted) {
                balanceCheckAttempted = true
                webView.postDelayed({ readRenalaBalance() }, 700)
            }
        } else if (manualAction == null) {
            // Normal flow: if not already on customers.php, redirect to customers.php for complaints
            if (!url.contains("customers.php", ignoreCase = true) && !url.contains("customer_portal.php", ignoreCase = true)) {
                Log.d("ZongRenalaWebView", "Zong Renala → Redirecting to customers.php for Complaints")
                webView.loadUrl("$DOMAIN/customers.php")
            }
        }
    }

    private fun tryLogin(attempt: Int = 1) {
        if (loginAttemptInProgress || loginSubmitted) return
        loginAttemptInProgress = true

        val username = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", ZONE)
        val password = IspPanelSettingsActivity.getSavedPassword(this, "ZONG", ZONE)

        Log.d("ZongRenalaWebView", "Zong Renala → Filling Renala credentials for user: $username")

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            loginAttemptInProgress = false
            fail("Zong Renala account is not configured.")
            return
        }

        webView.evaluateJavascript(
            "(function(){" +
                    "var u=document.querySelector('input[name=username],#username');" +
                    "var p=document.querySelector('input[name=password],#password');" +
                    "var b=document.querySelector('button[type=submit],#send,.btn-login');" +
                    "if(!u||!p)return 'not_ready';" +
                    "u.value='${jsEscape(username)}'; p.value='${jsEscape(password)}';" +
                    "if(b){ b.click(); return 'submitted'; }" +
                    "return 'no_button';" +
                    "})()"
        ) { raw ->
            loginAttemptInProgress = false
            val result = cleanJsResult(raw)
            Log.d("ZongRenalaWebView", "Zong Renala → Login Submit Result: $result")

            if (result.contains("not_ready")) {
                if (attempt < 8) {
                    webView.postDelayed({ tryLogin(attempt + 1) }, 1000)
                } else {
                    fail("Zong Renala login fields were not found.")
                }
            } else if (result.contains("submitted")) {
                loginSubmitted = true
                loginDone = true
            }
        }
    }

    private fun readRenalaBalance(attempt: Int = 1) {
        webView.evaluateJavascript(
            "(function(){" +
                    "var t=document.body.innerText||'';" +
                    "var m=t.match(/Available\\s+Credit\\s+Rs\\.?\\s*([0-9,.]+)/i);" +
                    "return m ? m[1] : null;" +
                    "})()"
        ) { res ->
            val balance = res?.removeSurrounding("\"")?.replace(",", "")?.toDoubleOrNull()
            Log.d("ZongRenalaWebView", "Zong Renala → Balance Read attempt $attempt: $balance (raw: $res)")

            if (balance != null) {
                FranchiseBalanceManager.updateBalance("ZONG", balance, ZONE) {
                    FranchiseBalanceManager.showUpdateNotification(this, "ZONG", balance, ZONE)
                    FranchiseBalanceManager.checkAndNotifyLowBalance(this, "ZONG", balance, ZONE)
                    setResult(RESULT_OK, Intent().apply {
                        putExtra("checked_balance", balance)
                        putExtra("selected_isp", "ZONG")
                        putExtra("target_zone", ZONE)
                    })
                    finish()
                }
            } else if (attempt < 10) {
                webView.postDelayed({ readRenalaBalance(attempt + 1) }, 1000)
            } else {
                Toast.makeText(this, "Zong Renala balance could not be read.", Toast.LENGTH_SHORT).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    private fun searchAndClickZongDealer(searchName: String) {
        if (searchName.isBlank()) {
            fail("Zong Renala dealer name is missing.")
            return
        }
        val nameEscaped = jsEscape(searchName.substringBefore("(").trim())

        webView.evaluateJavascript(
            "(function(){" +
                    "var s=document.querySelector('input[type=\\\"search\\\"],.dataTables_filter input');" +
                    "if(!s)return'search_input_not_found';" +
                    "s.value='$nameEscaped';" +
                    "s.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "s.dispatchEvent(new Event('keyup',{bubbles:true}));" +
                    "return'searched';" +
                    "})()"
        ) { raw ->
            val res = cleanJsResult(raw)
            if (res == "searched") {
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){" +
                                "var links=document.querySelectorAll('a[href*=\\\"subdealer_portal.php\\\"]');" +
                                "for(var i=0;i<links.length;i++){" +
                                "  var t=(links[i].innerText||'').toLowerCase();" +
                                "  if(t.indexOf('${jsEscape(searchName.substringBefore("(").trim().lowercase())}')>-1){" +
                                "    links[i].click(); return 'clicked';" +
                                "  }" +
                                "}" +
                                "if(links.length>0){links[0].click();return'clicked_first';}" +
                                "return'link_not_found';" +
                                "})()"
                    ) { linkRaw ->
                        val linkRes = cleanJsResult(linkRaw)
                        if (linkRes.contains("clicked")) {
                            Log.d("ZongRenalaWebView", "Successfully clicked dealer link")
                        } else {
                            fail("Zong Renala dealer link not found in table.")
                        }
                    }
                }, 1200)
            } else {
                fail("Zong Renala search box not found.")
            }
        }
    }

    private fun openZongAddCreditAndFillAmount() {
        val amount = topupAmount?.trim().orEmpty()
        if (amount.isBlank()) { fail("Zong Renala top-up amount is missing."); return }

        webView.evaluateJavascript(
            "(function(){" +
                    "var b=document.querySelector('button[data-target=\\\"#credit\\\"], button:contains(\\\"Add Credit\\\")');" +
                    "if(!b) {" +
                    "  var allBtns = document.querySelectorAll('button');" +
                    "  for(var i=0; i<allBtns.length; i++) {" +
                    "    if(allBtns[i].innerText.indexOf('Add Credit') > -1) { b = allBtns[i]; break; }" +
                    "  }" +
                    "}" +
                    "if(!b)return'not_found';" +
                    "b.click();return'opened';" +
                    "})()"
        ) { raw ->
            if (cleanJsResult(raw) != "opened") {
                fail("Zong Renala Add Credit button not found.")
                return@evaluateJavascript
            }

            webView.postDelayed({
                webView.evaluateJavascript(
                    "(function(){" +
                            "var modal=document.querySelector('#credit');" +
                            "var amt=modal?modal.querySelector('input#credit_amount,input[name=\\\"credit_amount\\\"]'):document.querySelector('input#credit_amount,input[name=\\\"credit_amount\\\"]');" +
                            "if(!amt)return'not_found';" +
                            "amt.value='${jsEscape(amount)}';" +
                            "amt.dispatchEvent(new Event('input',{bubbles:true}));" +
                            "return'filled';" +
                            "})()"
                ) { fillRaw ->
                    if (cleanJsResult(fillRaw) == "filled") {
                        webView.postDelayed({
                            webView.evaluateJavascript(
                                "(function(){" +
                                        "var modal=document.querySelector('#credit');" +
                                        "var btn=modal?modal.querySelector('button[name=\\\"doNewCredit\\\"]'):document.querySelector('button[name=\\\"doNewCredit\\\"]');" +
                                        "if(btn && !window.__zongRenalaSubmitted){" +
                                        "  window.__zongRenalaSubmitted=true;" +
                                        "  btn.click(); return 'submitted';" +
                                        "}" +
                                        "return 'already_submitted';" +
                                        "})()"
                            ) { submitRaw ->
                                if (submitRaw.contains("submitted")) {
                                    dealerTopupStep = 3
                                    webView.postDelayed({ captureDealerTopupResult() }, 2500)
                                }
                            }
                        }, 300)
                    } else {
                        fail("Zong Renala credit amount field not found.")
                    }
                }
            }, 700)
        }
    }

    private fun captureDealerTopupResult() {
        webView.evaluateJavascript("(function(){ var b = document.querySelector('.box-body'); var text = (b ? b.innerText : document.body.innerText) || ''; var dealerBalance = ''; var labels = document.querySelectorAll('h5.card-title'); for (var i=0; i<labels.length; i++){ if (labels[i].innerText.trim() === 'Dealer Balance'){ var v = labels[i].parentElement ? labels[i].parentElement.querySelector('span.h2') : null; if (v) dealerBalance = v.innerText.trim(); break; } } return JSON.stringify({text: text.substring(0, 800), dealerBalance: dealerBalance}); })()") { raw ->
            val clean = raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            val dealerBalance = Regex("\\\"dealerBalance\\\":\\\"(.*?)\\\"").find(clean)?.groupValues?.getOrNull(1) ?: ""
            val amount = topupAmount?.toDoubleOrNull() ?: 0.0

            db.collection("dealerPayments").add(mapOf("dealerId" to (dealerInternalId ?: ""), "dealerName" to (dealerDisplayName ?: ""), "panel" to "ZONG", "zone" to ZONE, "ispDealerId" to (dealerEboneId ?: ""), "amount" to amount, "submittedAt" to System.currentTimeMillis(), "resultText" to clean.take(500), "dealerBalanceAfter" to dealerBalance, "sourceTransactionId" to (sourceTransactionId ?: "")))

            val transactionId = sourceTransactionId?.trim()?.takeIf { it.isNotBlank() }
            if (transactionId != null) {
                db.collection("dealerTransactions").document(transactionId).update(mapOf("status" to "COMPLETED", "transferStatus" to "TRANSFERRED", "transferredAt" to System.currentTimeMillis()))
                    .addOnCompleteListener {
                        setResult(RESULT_OK, Intent().apply {
                            putExtra("dealer_topup_submitted", true)
                            putExtra("selected_isp", "ZONG")
                            putExtra("target_zone", ZONE)
                        })
                        finish()
                    }
            } else {
                setResult(RESULT_OK, Intent().apply {
                    putExtra("dealer_topup_submitted", true)
                    putExtra("selected_isp", "ZONG")
                    putExtra("target_zone", ZONE)
                })
                finish()
            }
        }
    }

    private fun fail(reason: String) {
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun jsEscape(v: String): String =
        v.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    private fun cleanJsResult(raw: String?): String =
        raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: ""
}
