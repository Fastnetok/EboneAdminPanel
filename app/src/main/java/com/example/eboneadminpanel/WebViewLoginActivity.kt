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
import org.json.JSONObject

class WebViewLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var loginDone = false
    private var activeAccountName = ""
    private var selectedIsp = "EBONE"

    private val PREFS_NAME = "ebill_accounts"
    private val KEY_ACCOUNTS = "accounts_json"
    private val KEY_ACTIVE = "active_account"
    private val WATEEN_PREFS = "wateen_accounts"

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) {
            webView.resumeTimers()
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SpeechHelper.SPEECH_REQUEST_CODE &&
            resultCode == RESULT_OK
        ) {
            val spokenText = SpeechHelper.getResultFromIntent(data)
            if (spokenText.isNotEmpty()) {
                if (selectedIsp == "EBONE") {
                    webView.evaluateJavascript("""
                        (function(){
                            var form = document.querySelector('form.sidebar-form');
                            if(form){
                                var inp = form.querySelector('input[name="username"]');
                                if(inp){
                                    inp.value = '$spokenText';
                                    form.submit();
                                }
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
                handlePageLoaded(url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (selectedIsp == "EBONE" && url.contains("/clients/client/")) {
                    webView.postDelayed({ fetchEboneCustomerDetails() }, 1500)
                } else if (selectedIsp == "WATEEN" && url.contains("/user/user/view/")) {
                    webView.postDelayed({ fetchWateenCustomerDetails() }, 1500)
                }
                return false
            }
        }

        loadInitialPage()
    }

    private fun getPrefsName(): String {
        return if (selectedIsp == "WATEEN") WATEEN_PREFS else PREFS_NAME
    }

    private fun loadInitialPage() {
        val accounts = loadAccounts()
        val active = getSharedPreferences(getPrefsName(), MODE_PRIVATE)
            .getString(KEY_ACTIVE, "") ?: ""

        if (active.isNotEmpty() && accounts.has(active)) {
            activeAccountName = active
            val acc = accounts.getJSONObject(active)
            val cookie = acc.optString("cookie", "")
            if (cookie.isNotEmpty()) {
                val domain = if (selectedIsp == "WATEEN")
                    "https://panel.wateen.com"
                else
                    "https://partner.ebill.pk"
                CookieManager.getInstance().setCookie(domain, cookie)
                CookieManager.getInstance().flush()
            }
            loginDone = true
            val clientsUrl = if (selectedIsp == "WATEEN")
                "https://panel.wateen.com/user/user/all"
            else
                "https://partner.ebill.pk/clients"
            webView.loadUrl(clientsUrl)
        } else {
            val loginUrl = if (selectedIsp == "WATEEN")
                "https://panel.wateen.com/auth.html"
            else
                "https://partner.ebill.pk/logincheck"
            webView.loadUrl(loginUrl)
        }
    }

    private fun loadAccounts(): JSONObject {
        val raw = getSharedPreferences(getPrefsName(), MODE_PRIVATE)
            .getString(KEY_ACCOUNTS, "") ?: ""
        return if (raw.isEmpty()) JSONObject() else JSONObject(raw)
    }

    private fun saveAccounts(accounts: JSONObject) {
        getSharedPreferences(getPrefsName(), MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNTS, accounts.toString())
            .apply()
    }

    private fun setActiveAccount(name: String) {
        getSharedPreferences(getPrefsName(), MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE, name)
            .apply()
        activeAccountName = name
    }

    private fun showAccountListDialog() {
        val accounts = loadAccounts()
        val names = accounts.keys().asSequence().toList()

        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_account_list, null)

        val listView = dialogView.findViewById<android.widget.ListView>(R.id.accountListView)
        val addButton = dialogView.findViewById<Button>(R.id.addAccountButton)

        val title = if (selectedIsp == "WATEEN") "Wateen Accounts" else "Ebone Accounts"

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()

        val adapter = object : ArrayAdapter<String>(this, R.layout.item_account_row, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_account_row, parent, false)

                val name = names[position]
                val nameText = view.findViewById<TextView>(R.id.accountNameText)
                val statusText = view.findViewById<TextView>(R.id.accountStatusText)
                val deleteButton = view.findViewById<Button>(R.id.deleteAccountButton)

                nameText.text = name
                statusText.text = if (name == activeAccountName) "Active" else ""

                view.setOnClickListener {
                    switchToAccount(name)
                    dialog.dismiss()
                }

                deleteButton.setOnClickListener {
                    val updated = loadAccounts()
                    updated.remove(name)
                    saveAccounts(updated)
                    Toast.makeText(
                        this@WebViewLoginActivity,
                        "Account removed",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    showAccountListDialog()
                }

                return view
            }
        }

        listView.adapter = adapter

        addButton.setOnClickListener {
            dialog.dismiss()
            showAddAccountDialog()
        }

        dialog.show()
    }

    private fun showAddAccountDialog() {
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 20)

        val nameInput = EditText(this)
        nameInput.hint = "Account name (e.g. Akmal)"
        layout.addView(nameInput)

        val userInput = EditText(this)
        userInput.hint = if (selectedIsp == "WATEEN") "Wateen username" else "ebill.pk username"
        layout.addView(userInput)

        val passInput = EditText(this)
        passInput.hint = if (selectedIsp == "WATEEN") "Wateen password" else "ebill.pk password"
        passInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(passInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add new account")
            .setView(layout)
            .setPositiveButton("Save and login") { _, _ ->
                val accName = nameInput.text.toString().trim()
                val username = userInput.text.toString().trim()
                val password = passInput.text.toString().trim()

                if (accName.isEmpty() || username.isEmpty() || password.isEmpty()) {
                    Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val accounts = loadAccounts()
                val acc = JSONObject()
                acc.put("username", username)
                acc.put("password", password)
                acc.put("cookie", "")
                accounts.put(accName, acc)
                saveAccounts(accounts)

                activeAccountName = accName
                setActiveAccount(accName)

                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                loginDone = false

                val loginUrl = if (selectedIsp == "WATEEN")
                    "https://panel.wateen.com/auth.html"
                else
                    "https://partner.ebill.pk/logincheck"
                webView.loadUrl(loginUrl)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            val domain = if (selectedIsp == "WATEEN")
                "https://panel.wateen.com"
            else
                "https://partner.ebill.pk"
            CookieManager.getInstance().setCookie(domain, cookie)
            CookieManager.getInstance().flush()
            loginDone = true
            val clientsUrl = if (selectedIsp == "WATEEN")
                "https://panel.wateen.com/user/user/all"
            else
                "https://partner.ebill.pk/clients"
            webView.loadUrl(clientsUrl)
        } else {
            loginDone = false
            val loginUrl = if (selectedIsp == "WATEEN")
                "https://panel.wateen.com/auth.html"
            else
                "https://partner.ebill.pk/logincheck"
            webView.loadUrl(loginUrl)
        }
    }

    private fun handlePageLoaded(url: String) {
        when {
            selectedIsp == "EBONE" &&
                    (url.contains("logincheck") || url.contains("login")) -> {
                loginDone = false
                tryAutoLogin()
            }

            selectedIsp == "WATEEN" && url.contains("auth.html") -> {
                loginDone = false
                tryAutoLogin()
            }

            selectedIsp == "EBONE" && url.contains("partner.ebill.pk") &&
                    url.contains("/clients/client/") -> {
                fetchEboneCustomerDetails()
            }

            selectedIsp == "WATEEN" && url.contains("panel.wateen.com") &&
                    url.contains("/user/user/view/") -> {
                fetchWateenCustomerDetails()
            }

            selectedIsp == "EBONE" && url.contains("partner.ebill.pk") &&
                    !url.contains("/clients/client/") -> {
                loginDone = true
                saveCookieForCurrentAccount("https://partner.ebill.pk")
                webView.evaluateJavascript(
                    "document.querySelectorAll('.modal,.modal-backdrop,.popup')" +
                            ".forEach(function(el){el.style.display='none';});" +
                            "document.body.classList.remove('modal-open');", null
                )
                if (!url.contains("/clients")) {
                    webView.postDelayed({
                        webView.loadUrl("https://partner.ebill.pk/clients")
                    }, 800)
                }
            }

            selectedIsp == "WATEEN" && url.contains("panel.wateen.com") &&
                    !url.contains("auth.html") &&
                    !url.contains("/user/user/view/") -> {
                loginDone = true
                saveCookieForCurrentAccount("https://panel.wateen.com")
                if (!url.contains("/user/user/all")) {
                    webView.postDelayed({
                        webView.loadUrl("https://panel.wateen.com/user/user/all")
                    }, 800)
                }
            }
        }
    }

    private fun saveCookieForCurrentAccount(domain: String) {
        val cookie = CookieManager.getInstance().getCookie(domain)
        if (cookie != null && activeAccountName.isNotEmpty()) {
            val accounts = loadAccounts()
            val acc = if (accounts.has(activeAccountName))
                accounts.getJSONObject(activeAccountName)
            else
                JSONObject()
            acc.put("cookie", cookie)
            accounts.put(activeAccountName, acc)
            saveAccounts(accounts)
            setActiveAccount(activeAccountName)
        }
    }

    private fun tryAutoLogin() {
        if (activeAccountName.isEmpty()) return
        val accounts = loadAccounts()
        if (!accounts.has(activeAccountName)) return

        val acc = accounts.getJSONObject(activeAccountName)
        val username = acc.optString("username", "")
        val password = acc.optString("password", "")

        if (username.isEmpty() || password.isEmpty()) return

        webView.postDelayed({
            webView.evaluateJavascript(
                "(function(){" +
                        "  var u = document.querySelector('input[type=text],input[name=username],input[name=email],#username,#email');" +
                        "  var p = document.querySelector('input[type=password],#password');" +
                        "  var b = document.querySelector('button[type=submit],input[type=submit],.btn-login,#login-btn');" +
                        "  if(u) u.value='" + username + "';" +
                        "  if(p) p.value='" + password + "';" +
                        "  if(u && p && b){ b.click(); return 'submitted'; }" +
                        "  return 'not found';" +
                        "})()", null
            )
        }, 1200)
    }

    private fun fetchEboneCustomerDetails() {
        val script = """
            (function(){
                var userId = '';
                var address = '';
                var phone = '';

                var rows = document.querySelectorAll('table.table-hover tbody tr');
                for (var k=0; k<rows.length; k++){
                    var thFirst = rows[k].querySelector('th');
                    if (thFirst && thFirst.innerText.indexOf('UserID') > -1){
                        var tdFirst = rows[k].querySelector('td');
                        if (tdFirst){
                            userId = (tdFirst.textContent || '').trim();
                        }
                        break;
                    }
                }

                for (var i=0; i<rows.length; i++){
                    var th = rows[i].querySelector('th');
                    if (!th) continue;
                    if (th.innerText.indexOf('Address') > -1 && th.innerText.indexOf('Email') === -1){
                        var allCells = rows[i].children;
                        if (allCells.length >= 2){
                            address = (allCells[1].textContent || '').trim();
                        }
                        if (allCells.length >= 3){
                            phone = (allCells[2].textContent || '').trim();
                            phone = phone.replace(new RegExp('^/+'), '');
                        }
                    }
                }

                return JSON.stringify({userId:userId, address:address, phone:phone});
            })()
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            handleFetchResult(result)
        }
    }

    private fun fetchWateenCustomerDetails() {
        val script = """
            (function(){
                var userId = '';
                var address = '';
                var phone = '';

                var usernameEl = document.querySelector('.h5.font-weight-300');
                if (usernameEl) {
                    userId = (usernameEl.innerText || '').replace('@','').trim();
                }

                var addressEl = document.querySelector('.h5.mt-4');
                if (addressEl) {
                    var rawAddr = (addressEl.textContent || '').trim();
                    address = rawAddr.replace(/[\r\n\t]+/g, ' ').replace(/\s+/g, ' ').trim();
                }

                var listItems = document.querySelectorAll('.list-group-item');
                for (var i=0; i<listItems.length; i++){
                    var icon = listItems[i].querySelector('i');
                    if (!icon) continue;
                    var text = (listItems[i].innerText || '').trim();
                    if (icon.className.indexOf('fa-mobile-alt') > -1 && phone === ''){
                        phone = text;
                    }
                    if (icon.className.indexOf('fa-phone') > -1 && phone === ''){
                        phone = text;
                    }
                }

                if (userId === ''){
                    var urlParts = window.location.href.split('/');
                    for (var j=0; j<urlParts.length; j++){
                        if (urlParts[j] === 'view' && urlParts[j+1]){
                            userId = urlParts[j+1].replace(new RegExp('/','g'),'');
                        }
                    }
                }

                return JSON.stringify({userId:userId, address:address, phone:phone});
            })()
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            handleFetchResult(result)
        }
    }

    private fun handleFetchResult(result: String) {
        try {
            val clean = result
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")

            val userId = Regex("\"userId\":\"(.*?)\"")
                .find(clean)?.groupValues?.get(1) ?: ""
            val address = Regex("\"address\":\"(.*?)\"")
                .find(clean)?.groupValues?.get(1) ?: ""
            val phone = Regex("\"phone\":\"(.*?)\"")
                .find(clean)?.groupValues?.get(1) ?: ""

            if (userId.isNotEmpty() || address.isNotEmpty() || phone.isNotEmpty()) {
                val resultIntent = Intent()
                resultIntent.putExtra("fetched_user_id", userId)
                resultIntent.putExtra("fetched_address", address)
                resultIntent.putExtra("fetched_phone", phone)
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        } catch (e: Exception) {
        }
    }
}