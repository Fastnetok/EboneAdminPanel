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
 *
 * Handles:
 *  - Zong Renala login (Franchise)
 *  - Zong Renala dealer top-up (Add Credit)
 *  - Zong Renala franchise balance check (Auto-update after top-up)
 */
class ZongRenalaWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var manualAction: String? = null
    private var dealerEboneId: String? = null
    private var topupAmount: String? = null
    private var dealerInternalId: String? = null
    private var dealerDisplayName: String? = null
    private var dealerSearchName: String? = null
    private var sourceTransactionId: String? = null
    private var debugTapInspectorEnabled = false

    private var loginDone = false
    private var loginAttemptInProgress = false
    private var balanceReadStarted = false
    private var dealerListStarted = false
    private var dealerSearchStarted = false
    private var creditStarted = false

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

    private fun clearZongDomainCookies() {
        val existing = CookieManager.getInstance().getCookie(DOMAIN) ?: return
        existing.split(";")
            .map { it.trim().substringBefore("=") }
            .filter { it.isNotBlank() }
            .forEach { name ->
                CookieManager.getInstance().setCookie(
                    DOMAIN,
                    "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT"
                )
            }
        CookieManager.getInstance().flush()
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

                if (debugTapInspectorEnabled) {
                    webView.evaluateJavascript(
                        "(function(){" +
                                "if(window.__tapInspectorInstalled)return;" +
                                "window.__tapInspectorInstalled=true;" +
                                "document.addEventListener('click',function(e){" +
                                "var el=e.target;" +
                                "var info='TAG: '+el.tagName+" +
                                "' | id='+(el.id||'-')+" +
                                "' | name='+(el.getAttribute('name')||'-')+" +
                                "' | class='+(el.className||'-')+" +
                                "' | text='+(el.innerText?el.innerText.substring(0,80):'-');" +
                                "if(window.TapInspector)window.TapInspector.onTap(info);" +
                                "},true);" +
                                "})()",
                        null
                    )
                }

                handlePage(url)
            }
        }
    }

    private fun loadRenalaAccount() {
        val username = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", ZONE)
        val password = IspPanelSettingsActivity.getSavedPassword(this, "ZONG", ZONE)

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Toast.makeText(this, "Zong Renala franchise account is not configured.", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val savedCookie = getRenalaSessionCookie()
        clearZongDomainCookies()

        loginDone = false
        loginAttemptInProgress = false

        if (savedCookie.isNotBlank()) {
            CookieManager.getInstance().setCookie(DOMAIN, savedCookie)
            CookieManager.getInstance().flush()
            webView.loadUrl(HOME_URL)
        } else {
            webView.loadUrl(LOGIN_URL)
        }
    }

    private fun handlePage(url: String) {
        if (url.contains("login.php", ignoreCase = true)) {
            loginDone = false
            if (!loginAttemptInProgress) {
                webView.postDelayed({ tryLogin() }, 300)
            }
            return
        }

        if (!url.contains(DOMAIN, ignoreCase = true)) return

        saveRenalaSessionCookie()

        if (manualAction == "CHECK_BALANCE") {
            if (!balanceReadStarted) {
                balanceReadStarted = true
                webView.postDelayed({ readRenalaBalance() }, 700)
            }
            return
        }

        if (manualAction == "DEALER_TOPUP") {
            when {
                url.contains("sub_dealers.php", ignoreCase = true) -> {
                    if (!dealerSearchStarted) {
                        dealerSearchStarted = true
                        webView.postDelayed({
                            searchAndClickZongDealer(
                                dealerDisplayName ?: dealerSearchName ?: ""
                            )
                        }, 700)
                    }
                }

                url.contains("subdealer_portal.php", ignoreCase = true) -> {
                    if (!creditStarted) {
                        creditStarted = true
                        webView.postDelayed({ openZongAddCreditAndFillAmount() }, 700)
                    }
                }

                else -> {
                    if (!dealerListStarted) {
                        dealerListStarted = true
                        webView.postDelayed({ webView.loadUrl(DEALER_LIST_URL) }, 500)
                    }
                }
            }
        }
    }

    private fun tryLogin(attempt: Int = 1) {
        if (loginAttemptInProgress) return

        val username = IspPanelSettingsActivity.getSavedUsername(this, "ZONG", ZONE)
        val password = IspPanelSettingsActivity.getSavedPassword(this, "ZONG", ZONE)

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            fail("Zong Renala account is not configured.")
            return
        }

        loginAttemptInProgress = true

        webView.evaluateJavascript(
            "(function(){" +
                    "var u=document.querySelector('input[type=\\\"text\\\"],input[name=\\\"username\\\"],input[name=\\\"email\\\"],#username,#email');" +
                    "var p=document.querySelector('input[type=\\\"password\\\"],#password');" +
                    "var b=document.querySelector('button[type=\\\"submit\\\"],input[type=\\\"submit\\\"],#send,.btn-login,#login-btn');" +
                    "if(!u||!p)return'fields_not_ready';" +
                    "u.value='${jsEscape(username)}';" +
                    "p.value='${jsEscape(password)}';" +
                    "u.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "p.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "if(b){b.click();return'submitted';}" +
                    "return'submitted_no_button';" +
                    "})()"
        ) { raw ->
            loginAttemptInProgress = false
            val result = cleanJsResult(raw)

            if (result == "fields_not_ready") {
                if (attempt < 10) {
                    webView.postDelayed({ tryLogin(attempt + 1) }, 500)
                } else {
                    fail("Zong Renala login fields were not found.")
                }
            } else {
                loginDone = true
            }
        }
    }

    private fun searchAndClickZongDealer(searchName: String) {
        if (searchName.isBlank()) {
            fail("Zong Renala dealer name is missing.")
            return
        }

        val safeName = jsEscape(searchName)

        webView.evaluateJavascript(
            "(function(){" +
                    "var inp=document.querySelector('input[aria-controls=\\\"table3\\\"]');" +
                    "if(!inp)return'not_found';" +
                    "inp.focus();" +
                    "inp.value='${safeName}';" +
                    "inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "inp.dispatchEvent(new Event('keyup',{bubbles:true}));" +
                    "return'filled';" +
                    "})()"
        ) { raw ->
            if (cleanJsResult(raw) != "filled") {
                fail("Zong Renala dealer search box not found.")
                return@evaluateJavascript
            }

            webView.postDelayed({
                webView.evaluateJavascript(
                    "(function(){" +
                            "var icons=document.querySelectorAll('i.mdi-plus');" +
                            "var matches=[];" +
                            "for(var i=0;i<icons.length;i++){" +
                            "var row=icons[i].closest('tr');" +
                            "var rowText=row?(row.innerText||row.textContent||''):'';" +
                            "if(rowText.toLowerCase().indexOf('${safeName.lowercase()}'.toLowerCase())>-1)" +
                            "matches.push({icon:i,rowText:rowText.substring(0,120)});" +
                            "}" +
                            "if(matches.length!==1)return JSON.stringify({status:'ambiguous',count:matches.length,totalIcons:icons.length});" +
                            "var link=icons[matches[0].icon].closest('a');" +
                            "if(!link) return JSON.stringify({status:'no_link'});" +
                            "var target=link.getAttribute('data-target');" +
                            "if(!target) return JSON.stringify({status:'no_target'});" +
                            "link.click();" +
                            "return JSON.stringify({status:'clicked',modalSelector:target});" +
                            "})()"
                ) { clickRaw ->
                    val clean = cleanJsResult(clickRaw).replace("\\\"", "\"")
                    val status = Regex("\"status\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""

                    if (status != "clicked") {
                        fail("Could not open the confirmed Zong Renala dealer Add Credit modal.")
                        return@evaluateJavascript
                    }

                    val modalSelector = Regex("\"modalSelector\":\"(.*?)\"").find(clean)?.groupValues?.get(1) ?: ""

                    if (modalSelector.isBlank()) {
                        fail("Could not determine the Zong Renala dealer credit modal.")
                        return@evaluateJavascript
                    }

                    webView.postDelayed({
                        openRenalaCreditModalAndSubmit(modalSelector)
                    }, 800)
                }
            }, 1800)
        }
    }

    private fun openRenalaCreditModalAndSubmit(modalSelector: String) {
        val amount = topupAmount?.trim().orEmpty()
        if (amount.isBlank()) { fail("Zong Renala top-up amount is missing."); return }

        val amountJs = jsEscape(amount)
        val selectorJs = jsEscape(modalSelector)

        webView.evaluateJavascript(
            "(function(){" +
                    "var modal=document.querySelector('${selectorJs}');" +
                    "if(!modal)return'modal_not_found';" +
                    "var amt=modal.querySelector('input[name=\\\"credit_amount\\\"]');" +
                    "if(!amt)return'amount_not_found';" +
                    "amt.value='${amountJs}';" +
                    "amt.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "return'filled';" +
                    "})()"
        ) { raw ->
            if (cleanJsResult(raw) == "filled") {
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){" +
                                "var modal=document.querySelector('${selectorJs}');" +
                                "if(!modal)return'modal_not_found';" +
                                "var btn=modal.querySelector('button[name=\\\"doNewCredit\\\"]');" +
                                "if(!btn)return'not_found';" +
                                "btn.click();return'submitted';" +
                                "})()"
                    ) { submitRaw ->
                        if (cleanJsResult(submitRaw) == "submitted") {
                            webView.postDelayed({ captureDealerTopupResult() }, 2500)
                        } else {
                            fail("Zong Renala Credit Account button not found.")
                        }
                    }
                }, 600)
            } else {
                fail("Zong Renala credit modal/amount field not found.")
            }
        }
    }

    private fun openZongAddCreditAndFillAmount() {
        val amount = topupAmount?.trim().orEmpty()
        if (amount.isBlank()) { fail("Zong Renala top-up amount is missing."); return }

        webView.evaluateJavascript(
            "(function(){" +
                    "var b=document.querySelector('button[data-target=\\\"#credit\\\"]');" +
                    "if(!b)return'not_found';" +
                    "b.click();return'opened';" +
                    "})()"
        ) { raw ->
            if (cleanJsResult(raw) != "opened") {
                creditStarted = false
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
                                        "if(!btn)return'not_found';" +
                                        "btn.click();return'submitted';" +
                                        "})()"
                            ) { submitRaw ->
                                if (cleanJsResult(submitRaw) == "submitted") {
                                    webView.postDelayed({ captureDealerTopupResult() }, 2500)
                                } else {
                                    fail("Zong Renala Credit Account button not found.")
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

    private fun readRenalaBalance(attempt: Int = 1) {
        webView.evaluateJavascript(
            "(function(){" +
                    "var text=document.body.innerText||'';" +
                    "var m=text.match(/Available\\s+Credit\\s+Rs\\.?\\s*(-?[0-9,]+(?:\\.[0-9]+)?)/i);" +
                    "if(m)return JSON.stringify({found:true,value:m[1]});" +
                    "var m2=text.match(/Account\\s+Credit[^0-9\\-]*(-?[0-9,]+(?:\\.[0-9]+)?)/i);" +
                    "if(m2)return JSON.stringify({found:true,value:m2[1]});" +
                    "return JSON.stringify({found:false});" +
                    "})()"
        ) { raw ->
            val clean = raw.removeSurrounding("\"").replace("\\\"", "\"")
            Log.d("ZongRenalaWebView", "Balance check raw: $raw")
            Log.d("ZongRenalaWebView", "Balance check clean: $clean")

            val balance = Regex("\"value\":\"(.*?)\"")
                .find(clean)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()

            if (balance != null) {
                Log.d("ZongRenalaWebView", "Found balance: $balance")
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
                Log.d("ZongRenalaWebView", "Balance not found, attempt $attempt/10, retrying...")
                webView.postDelayed({ readRenalaBalance(attempt + 1) }, 1000)
            } else {
                fail("Zong Renala کا بیلنس نہیں مل سکا۔ براہ کرم دوبارہ کوشش کریں یا دستی چیک کریں۔")
            }
        }
    }

    private fun captureDealerTopupResult() {
        webView.evaluateJavascript("(function(){ var b = document.querySelector('.box-body'); var text = (b ? b.innerText : document.body.innerText) || ''; var dealerBalance = ''; var labels = document.querySelectorAll('h5.card-title'); for (var i=0;i<labels.length;i++){ if (labels[i].innerText.trim() === 'Dealer Balance'){ var v = labels[i].parentElement ? labels[i].parentElement.querySelector('span.h2') : null; if (v) dealerBalance = v.innerText.trim(); break; } } return JSON.stringify({text: text.substring(0, 800), dealerBalance: dealerBalance}); })()") { raw ->
            val clean = raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            val dealerBalance = Regex("\\\"dealerBalance\\\":\\\"(.*?)\\\"").find(clean)?.groupValues?.getOrNull(1) ?: ""
            val amount = topupAmount?.toDoubleOrNull() ?: 0.0

            db.collection("dealerPayments").add(mapOf("dealerId" to (dealerInternalId ?: ""), "dealerName" to (dealerDisplayName ?: ""), "panel" to "ZONG", "zone" to ZONE, "ispDealerId" to (dealerEboneId ?: ""), "amount" to amount, "submittedAt" to System.currentTimeMillis(), "resultText" to clean.take(500), "dealerBalanceAfter" to dealerBalance, "sourceTransactionId" to (sourceTransactionId ?: "")))

            val transactionId = sourceTransactionId?.trim()?.takeIf { it.isNotBlank() }
            if (transactionId != null) {
                db.collection("dealerTransactions").document(transactionId).update(mapOf("status" to "COMPLETED", "transferStatus" to "TRANSFERRED", "transferredAt" to System.currentTimeMillis(), "transferResultText" to clean.take(500)))
                    .addOnCompleteListener { continueAfterDealerTopupSuccess() }
            } else {
                setResult(RESULT_OK, Intent().apply { putExtra("dealer_topup_submitted", true); putExtra("selected_isp", "ZONG"); putExtra("target_zone", ZONE) })
                continueAfterDealerTopupSuccess()
            }
        }
    }

    private fun continueAfterDealerTopupSuccess() {
        manualAction = "CHECK_BALANCE"
        balanceReadStarted = false
        webView.loadUrl(HOME_URL)
    }

    private fun fail(message: String) {
        Log.e("ZongRenalaWebView", message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED, Intent().apply { putExtra("error", message); putExtra("selected_isp", "ZONG"); putExtra("target_zone", ZONE) })
        finish()
    }

    private fun cleanJsResult(raw: String): String = raw.trim().removeSurrounding("\"")
    private fun jsEscape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
}
