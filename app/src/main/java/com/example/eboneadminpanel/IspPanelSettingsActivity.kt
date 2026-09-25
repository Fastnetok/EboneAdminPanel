package com.example.eboneadminpanel

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class IspPanelSettingsActivity : AppCompatActivity() {

    private lateinit var accountsContainer: LinearLayout

    companion object {
        private const val PREFS_FILE = "isp_panel_prefs"
        private const val KEY_ACCOUNTS = "all_accounts_json"
        private const val MASTER_OVERRIDE_PASSWORD = "1912"

        private fun accountZone(obj: JSONObject): String = obj.optString("zone", "Okara").ifBlank { "Okara" }

        private const val ZONG_OKARA_COMPLAINT_ACCOUNT = "ABBAS046"

        private fun normalizedUsername(value: String): String =
            value.uppercase().filter { it.isLetterOrDigit() }

        private fun preferredZongOkaraComplaintAccount(arr: JSONArray): JSONObject? {
            // Pass 1: exact username ABBAS046 regardless of zone tag
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val isZong = obj.optString("isp").equals("ZONG", ignoreCase = true)
                    val isFranchiseAccount = !obj.optBoolean("isDealer", false)
                    val isAbbas046 = normalizedUsername(obj.optString("username")) == ZONG_OKARA_COMPLAINT_ACCOUNT
                    if (isZong && isFranchiseAccount && isAbbas046) return obj
                } catch (_: Exception) { }
            }
            // Pass 2: any Zong Okara account
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val isZong = obj.optString("isp").equals("ZONG", ignoreCase = true)
                    val isOkara = accountZone(obj).equals("Okara", ignoreCase = true)
                    val isFranchiseAccount = !obj.optBoolean("isDealer", false)
                    val isAbbas046 = normalizedUsername(obj.optString("username")) == ZONG_OKARA_COMPLAINT_ACCOUNT
                    if (isZong && isOkara && isFranchiseAccount && !isAbbas046) return obj
                } catch (_: Exception) { }
            }
            return null
        }

        fun getSavedUsername(context: Context, isp: String, zone: String = "Okara"): String? {
            val arr = safeAccountsArray(context) ?: return null

            if (isp.equals("ZONG", ignoreCase = true)) {
                if (zone.equals("Okara", ignoreCase = true)) {
                    preferredZongOkaraComplaintAccount(arr)?.let { return it.optString("username") }
                } else if (zone.equals("Renala", ignoreCase = true)) {
                    for (i in 0 until arr.length()) {
                        try {
                            val obj = arr.getJSONObject(i)
                            val uname = normalizedUsername(obj.optString("username"))
                            if (obj.optString("isp").equals("ZONG", ignoreCase = true) &&
                                !obj.optBoolean("isDealer", false) &&
                                uname != ZONG_OKARA_COMPLAINT_ACCOUNT) {
                                return obj.getString("username")
                            }
                        } catch (_: Exception) { }
                    }
                    return null
                }
            }
            
            // Pass 1: Non-dealer, exact ISP + exact Zone match (case-insensitive)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) && 
                        !obj.optBoolean("isDealer", false) && 
                        accountZone(obj).equals(zone, ignoreCase = true)) {
                        return obj.getString("username")
                    }
                } catch (_: Exception) { }
            }

            // Pass 2: Fallback for legacy (no zone field) — ONLY if requesting Okara
            if (zone.equals("Okara", ignoreCase = true)) {
                for (i in 0 until arr.length()) {
                    try {
                        val obj = arr.getJSONObject(i)
                        if (obj.optString("isp").equals(isp, ignoreCase = true) && 
                            !obj.optBoolean("isDealer", false) && 
                            !obj.has("zone")) {
                            return obj.getString("username")
                        }
                    } catch (_: Exception) { }
                }
            }

            // Pass 3: Dealer account, exact isp+zone match (last resort)
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) && 
                        obj.optBoolean("isDealer", false) && 
                        accountZone(obj).equals(zone, ignoreCase = true)) {
                        return obj.getString("username")
                    }
                } catch (_: Exception) { }
            }
            
            return null
        }

        fun getSavedPassword(context: Context, isp: String, zone: String = "Okara"): String? {
            val arr = safeAccountsArray(context) ?: return null

            if (isp.equals("ZONG", ignoreCase = true)) {
                if (zone.equals("Okara", ignoreCase = true)) {
                    preferredZongOkaraComplaintAccount(arr)?.let { return it.optString("password") }
                } else if (zone.equals("Renala", ignoreCase = true)) {
                    for (i in 0 until arr.length()) {
                        try {
                            val obj = arr.getJSONObject(i)
                            val uname = normalizedUsername(obj.optString("username"))
                            if (obj.optString("isp").equals("ZONG", ignoreCase = true) &&
                                !obj.optBoolean("isDealer", false) &&
                                uname != ZONG_OKARA_COMPLAINT_ACCOUNT) {
                                return obj.getString("password")
                            }
                        } catch (_: Exception) { }
                    }
                    return null
                }
            }
            
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) && 
                        !obj.optBoolean("isDealer", false) && 
                        accountZone(obj).equals(zone, ignoreCase = true)) {
                        return obj.getString("password")
                    }
                } catch (_: Exception) { }
            }

            if (zone.equals("Okara", ignoreCase = true)) {
                for (i in 0 until arr.length()) {
                    try {
                        val obj = arr.getJSONObject(i)
                        if (obj.optString("isp").equals(isp, ignoreCase = true) && 
                            !obj.optBoolean("isDealer", false) && 
                            !obj.has("zone")) {
                            return obj.getString("password")
                        }
                    } catch (_: Exception) { }
                }
            }

            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) && 
                        obj.optBoolean("isDealer", false) && 
                        accountZone(obj).equals(zone, ignoreCase = true)) {
                        return obj.getString("password")
                    }
                } catch (_: Exception) { }
            }
            
            return null
        }

        fun getDealerUsername(context: Context, isp: String, zone: String, dealerName: String): String? {
            val arr = safeAccountsArray(context) ?: return null
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        obj.optBoolean("isDealer", false) &&
                        accountZone(obj).equals(zone, ignoreCase = true) &&
                        obj.optString("dealerName", "").equals(dealerName, ignoreCase = true)
                    ) {
                        return obj.getString("username")
                    }
                } catch (_: Exception) { }
            }
            return null
        }

        fun getDealerPassword(context: Context, isp: String, zone: String, dealerName: String): String? {
            val arr = safeAccountsArray(context) ?: return null
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("isp").equals(isp, ignoreCase = true) &&
                        obj.optBoolean("isDealer", false) &&
                        accountZone(obj).equals(zone, ignoreCase = true) &&
                        obj.optString("dealerName", "").equals(dealerName, ignoreCase = true)
                    ) {
                        return obj.getString("password")
                    }
                } catch (_: Exception) { }
            }
            return null
        }

        private fun safeAccountsArray(context: Context): JSONArray? {
            return try {
                val json = getPrefs(context).getString(KEY_ACCOUNTS, null) ?: return null
                JSONArray(json)
            } catch (_: Exception) { null }
        }

        fun debugListAccounts(context: Context): String {
            val arr = safeAccountsArray(context) ?: return "(no accounts saved)"
            val parts = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val isp = obj.optString("isp", "?")
                    val zone = accountZone(obj)
                    val isDealer = obj.optBoolean("isDealer", false)
                    val dealerName = obj.optString("dealerName", "")
                    parts.add(
                        if (isDealer) "$isp/$zone(dealer:$dealerName)" else "$isp/$zone"
                    )
                } catch (_: Exception) {
                    parts.add("(unreadable entry #$i)")
                }
            }
            return if (parts.isEmpty()) "(no accounts saved)" else parts.joinToString(", ")
        }

        private fun getPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return try {
                EncryptedSharedPreferences.create(
                    context, PREFS_FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e("IspPanelSettingsActivity", "Corrupted encrypted prefs — recreating fresh", e)
                context.getSharedPreferences(PREFS_FILE, MODE_PRIVATE).edit().clear().commit()
                context.getSharedPreferences(PREFS_FILE, MODE_PRIVATE)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_isp_panel_settings)

        accountsContainer = findViewById(R.id.accountsContainer)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAddIsp).setOnClickListener { showAddAccountDialog() }
        findViewById<Button>(R.id.btnAddDealer).setOnClickListener { showAddAccountDialog() }

        loadAccountsUI()
    }

    private fun loadAccountsUI() {
        accountsContainer.removeAllViews()
        val accounts = loadAllAccountsJson()
        val keys = accounts.keys()

        while (keys.hasNext()) {
            val name = keys.next()
            val obj = accounts.getJSONObject(name)
            val isp = obj.optString("isp", "EBONE")
            val zone = accountZone(obj)
            val username = obj.optString("username", "")
            val isDealer = obj.optBoolean("isDealer", false)
            val dealerName = obj.optString("dealerName", "")

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(24, 24, 24, 24)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 16) }
            }

            val titleTv = TextView(this).apply {
                text = if (isDealer) "$isp ($zone) — Dealer: $dealerName" else "$isp ($zone) — Franchise ($name)"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1B5E20"))
            }

            val detailsTv = TextView(this).apply {
                text = "Username: $username\nPassword: ••••••••"
                textSize = 14f
                setTextColor(Color.parseColor("#37474F"))
            }

            val deleteBtn = Button(this).apply {
                text = "Delete Account"
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(Color.WHITE)
                textSize = 12f
                setOnClickListener { showDeleteConfirmDialog(name) }
            }

            card.addView(titleTv)
            card.addView(detailsTv)
            card.addView(deleteBtn)
            accountsContainer.addView(card)
        }

        if (accounts.length() == 0) {
            val empty = TextView(this).apply {
                text = "No ISP panel accounts saved yet. Click below to add one."
                textSize = 15f
                setTextColor(Color.parseColor("#546E7A"))
                setPadding(16, 16, 16, 16)
            }
            accountsContainer.addView(empty)
        }
    }

    private fun loadAllAccountsJson(): JSONObject {
        val json = getPrefs(this).getString(KEY_ACCOUNTS, null) ?: return JSONObject()
        return try { JSONObject(json) } catch (_: Exception) { JSONObject() }
    }

    private fun saveAllAccountsJson(json: JSONObject) {
        getPrefs(this).edit().putString(KEY_ACCOUNTS, json.toString()).apply()
    }

    private fun showAddAccountDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val nameInput = EditText(this).apply { hint = "Account Nickname (e.g. Main Okara)" }
        val ispSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@IspPanelSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("EBONE", "WATEEN", "ZONG")
            )
        }

        val zoneSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@IspPanelSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Okara", "Renala")
            )
        }

        val isDealerBox = CheckBox(this).apply { text = "Is this a Dealer Account?" }
        val dealerNameInput = EditText(this).apply {
            hint = "Dealer Name (e.g. Akmal)"
            visibility = View.GONE
        }

        isDealerBox.setOnCheckedChangeListener { _, checked ->
            dealerNameInput.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val usernameInput = EditText(this).apply { hint = "ISP Panel Username" }
        val passwordInput = EditText(this).apply {
            hint = "ISP Panel Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(TextView(this).apply { text = "Account Nickname:" })
        layout.addView(nameInput)
        layout.addView(TextView(this).apply { text = "Select ISP:" })
        layout.addView(ispSpinner)
        layout.addView(TextView(this).apply { text = "Select Zone / Branch:" })
        layout.addView(zoneSpinner)
        layout.addView(isDealerBox)
        layout.addView(dealerNameInput)
        layout.addView(TextView(this).apply { text = "Username:" })
        layout.addView(usernameInput)
        layout.addView(TextView(this).apply { text = "Password:" })
        layout.addView(passwordInput)

        AlertDialog.Builder(this)
            .setTitle("Add ISP Panel Account")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val isp = ispSpinner.selectedItem.toString()
                val zone = zoneSpinner.selectedItem.toString()
                val isDealer = isDealerBox.isChecked
                val dealerName = dealerNameInput.text.toString().trim()
                val username = usernameInput.text.toString().trim()
                val password = passwordInput.text.toString().trim()

                if (name.isEmpty() || username.isEmpty() || password.isEmpty() || (isDealer && dealerName.isEmpty())) {
                    Toast.makeText(this, "Please fill in all required fields.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val all = loadAllAccountsJson()
                val obj = JSONObject().apply {
                    put("isp", isp)
                    put("zone", zone)
                    put("username", username)
                    put("password", password)
                    put("isDealer", isDealer)
                    if (isDealer) put("dealerName", dealerName)
                }
                all.put(name, obj)
                saveAllAccountsJson(all)
                loadAccountsUI()
                Toast.makeText(this, "Account saved successfully.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmDialog(accountName: String) {
        val input = EditText(this).apply {
            hint = "Enter Password or Master Pin (1912)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Delete Account: $accountName")
            .setMessage("Enter the account password or master override pin (1912) to delete:")
            .setView(input)
            .setPositiveButton("Delete") { _, _ ->
                val entered = input.text.toString().trim()
                val all = loadAllAccountsJson()
                val obj = all.optJSONObject(accountName)
                val realPassword = obj?.optString("password", "") ?: ""

                if (entered == realPassword || entered == MASTER_OVERRIDE_PASSWORD) {
                    all.remove(accountName)
                    saveAllAccountsJson(all)
                    loadAccountsUI()
    Toast.makeText(this, "Product/Account deleted.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Incorrect password or pin.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
