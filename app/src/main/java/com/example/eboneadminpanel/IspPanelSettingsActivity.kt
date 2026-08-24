package com.example.eboneadminpanel

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class IspPanelSettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_FILE = "isp_panel_prefs"
        private const val KEY_ACCOUNTS = "all_accounts_json"

        // NEW: master override password for the "delete account" confirmation
        // dialog. If the admin forgets the actual saved ISP panel password
        // (e.g. it was typed into ISP Panel Settings and never written down
        // anywhere else), this fixed code also confirms deletion — so the
        // admin is never locked out of removing a wrong/duplicate entry
        // just because they forgot that specific password. Kept separate
        // from any per-account password; entering EITHER the real saved
        // password OR this code deletes the entry.
        private const val MASTER_OVERRIDE_PASSWORD = "1912"

        /** NEW: entries saved before zones existed have no "zone" field
         * at all — treat those as "Okara" so nothing breaks for
         * existing accounts. */
        private fun accountZone(obj: JSONObject): String = obj.optString("zone", "Okara").ifBlank { "Okara" }

        /**
         * NEW: zone-aware lookup — [zone] defaults to "Okara" for any
         * caller that hasn't been updated to pass a real zone yet.
         * Priority order:
         *   1. Non-dealer account matching BOTH isp AND zone exactly
         *      (e.g. Zong + Renala → RN-Abbas046)
         *   2. Dealer account matching BOTH isp AND zone
         *   3. ONLY when [zone] == "Okara" (the legacy/default zone):
         *      fall back to any non-dealer account for that isp,
         *      regardless of its saved zone — keeps old single-zone
         *      setups (accounts saved before zones existed) working.
         *
         * CRITICAL: for any OTHER explicitly-requested zone (Renala,
         * etc.), there is NO cross-zone fallback — if no exact match
         * exists, this returns null so the caller can fail clearly
         * instead of silently logging in with the WRONG franchise's
         * credentials. A previous version of this fallback caused a
         * real incident: Renala was requested, no Renala-tagged Zong
         * account was found (likely saved with the Zone dropdown left
         * on its default), and the code silently fell back to Okara's
         * account — logging into the wrong franchise without any
         * warning.
         */
        fun getSavedUsername(context: Context, isp: String, zone: String = "Okara"): String? {
            val arr = safeAccountsArray(context) ?: return null
            // Pass 1: non-dealer, exact isp+zone match
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("isp") == isp && !obj.optBoolean("isDealer", false) && accountZone(obj) == zone) {
                        return obj.getString("username")
                    }
                } catch (_: Exception) { /* skip this one malformed entry, keep scanning */ }
            }
            // Pass 2: dealer account, exact isp+zone match
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("isp") == isp && accountZone(obj) == zone) {
                        return obj.getString("username")
                    }
                } catch (_: Exception) { /* skip */ }
            }
            // Pass 3: only for the legacy default zone — any non-dealer
            // account for that isp regardless of its saved zone.
            if (zone.equals("Okara", ignoreCase = true)) {
                for (i in 0 until arr.length()) {
                    try {
                        val obj = arr.getJSONObject(i)
                        if (obj.getString("isp") == isp && !obj.optBoolean("isDealer", false)) {
                            return obj.getString("username")
                        }
                    } catch (_: Exception) { /* skip */ }
                }
            }
            return null
        }

        fun getSavedPassword(context: Context, isp: String, zone: String = "Okara"): String? {
            val arr = safeAccountsArray(context) ?: return null
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("isp") == isp && !obj.optBoolean("isDealer", false) && accountZone(obj) == zone) {
                        return obj.getString("password")
                    }
                } catch (_: Exception) { }
            }
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("isp") == isp && accountZone(obj) == zone) {
                        return obj.getString("password")
                    }
                } catch (_: Exception) { }
            }
            if (zone.equals("Okara", ignoreCase = true)) {
                for (i in 0 until arr.length()) {
                    try {
                        val obj = arr.getJSONObject(i)
                        if (obj.getString("isp") == isp && !obj.optBoolean("isDealer", false)) {
                            return obj.getString("password")
                        }
                    } catch (_: Exception) { }
                }
            }
            return null
        }

        private fun safeAccountsArray(context: Context): JSONArray? {
            return try {
                val json = getPrefs(context).getString(KEY_ACCOUNTS, null) ?: return null
                JSONArray(json)
            } catch (_: Exception) { null }
        }

        /** NEW: diagnostic-only — lists every saved account's isp/zone
         * (and dealer name if applicable) as plain text, so a "no login
         * found for zone X" failure can show exactly what IS actually
         * stored instead of leaving the admin guessing. Never throws;
         * skips any single entry it can't read. */
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

        private fun getPrefs(context: Context): android.content.SharedPreferences {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return try {
                EncryptedSharedPreferences.create(
                    context, PREFS_FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // NEW: self-heal from Android Keystore "VERIFICATION_FAILED"
                // crashes — stale encrypted data (e.g. restored via
                // Android's Auto Backup after an uninstall/reinstall)
                // no longer matches the current Keystore key. Wipe and
                // recreate instead of crashing every time.
                android.util.Log.e("IspPanelSettingsActivity", "Corrupted encrypted prefs — recreating fresh", e)
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
                context.deleteSharedPreferences(PREFS_FILE)
                EncryptedSharedPreferences.create(
                    context, PREFS_FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        }
    }

    private lateinit var accountsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_isp_panel_settings)

        accountsContainer = findViewById(R.id.accountsContainer)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnAddIsp).setOnClickListener {
            showAddDialog(isDealer = false)
        }

        findViewById<Button>(R.id.btnAddDealer).setOnClickListener {
            showAddDialog(isDealer = true)
        }

        renderAccountsList()
    }

    private fun getAccounts(): JSONArray {
        return try {
            val json = getPrefs().getString(KEY_ACCOUNTS, null) ?: return JSONArray()
            JSONArray(json)
        } catch (_: Exception) { JSONArray() }
    }

    private fun saveAccounts(arr: JSONArray) {
        getPrefs().edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    private fun renderAccountsList() {
        accountsContainer.removeAllViews()
        val accounts = getAccounts()

        if (accounts.length() == 0) {
            val empty = TextView(this).apply {
                text = "No accounts saved yet."
                setTextColor(android.graphics.Color.parseColor("#757575"))
                textSize = 13f
                setPadding(8, 8, 8, 8)
            }
            accountsContainer.addView(empty)
            return
        }

        for (i in 0 until accounts.length()) {
            val obj = accounts.getJSONObject(i)
            val isp = obj.getString("isp")
            val username = obj.getString("username")
            val dealerName = obj.optString("dealerName", "")
            val isDealer = obj.optBoolean("isDealer", false)
            // NEW: zone tag — old entries without this field default to
            // "Okara" so nothing looks different until re-saved.
            val zone = obj.optString("zone", "Okara").ifBlank { "Okara" }
            val portalUrl = getPortalUrl(isp)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(android.graphics.Color.WHITE)
                setPadding(16, 16, 16, 16)
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 10)
                layoutParams = params
            }

            val textBlock = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val title = TextView(this).apply {
                text = if (isDealer && dealerName.isNotEmpty())
                    "$dealerName (Dealer) · $isp · $zone"
                else "$isp · $zone"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#0D2E5C"))
            }
            val subtitle = TextView(this).apply {
                text = "$portalUrl · $username"
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#757575"))
            }

            textBlock.addView(title)
            textBlock.addView(subtitle)

            val removeBtn = Button(this).apply {
                text = "🗑"
                textSize = 16f
                setTextColor(android.graphics.Color.parseColor("#C62828"))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(12, 8, 12, 8)
            }

            val password = obj.optString("password", "")
            val index = i
            removeBtn.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete Account?")
                    .setMessage("Are you sure you want to delete this account?")
                    .setPositiveButton("Yes") { _, _ ->
                        val container = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(48, 16, 48, 8)
                        }
                        val label = TextView(this).apply {
                            text = "Enter panel password to confirm deletion"
                            textSize = 13f
                            setTextColor(android.graphics.Color.parseColor("#5F5E5A"))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { it.bottomMargin = 12 }
                        }
                        val passwordRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setBackgroundResource(R.drawable.bg_input_box)
                            setPadding(16, 12, 16, 12)
                        }
                        val lockIcon = TextView(this).apply {
                            text = "🔒"
                            textSize = 16f
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { it.rightMargin = 10 }
                        }
                        val passwordInput = EditText(this).apply {
                            hint = "Panel password"
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            background = null
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                            )
                        }
                        passwordRow.addView(lockIcon)
                        passwordRow.addView(passwordInput)
                        container.addView(label)
                        container.addView(passwordRow)

                        AlertDialog.Builder(this)
                            .setTitle("Confirm Password")
                            .setView(container)
                            .setPositiveButton("Delete") { _, _ ->
                                val enteredPassword = passwordInput.text.toString()
                                // NEW: accept EITHER the account's own saved
                                // password OR the fixed master override
                                // password — so a forgotten panel password
                                // never blocks removing a wrong/duplicate
                                // ISP Panel Settings entry.
                                if (enteredPassword == password ||
                                    enteredPassword == MASTER_OVERRIDE_PASSWORD) {
                                    val arr = getAccounts()
                                    val newArr = JSONArray()
                                    for (j in 0 until arr.length()) {
                                        if (j != index) newArr.put(arr.getJSONObject(j))
                                    }
                                    saveAccounts(newArr)
                                    renderAccountsList()
                                    Toast.makeText(this, "Account deleted", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this, "Incorrect password — account not deleted", Toast.LENGTH_LONG).show()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }

            row.addView(textBlock)
            row.addView(removeBtn)
            accountsContainer.addView(row)

            val divider = View(this).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#EEEEEE"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(0, 0, 0, 10) }
            }
            accountsContainer.addView(divider)
        }
    }

    private fun showAddDialog(isDealer: Boolean) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val ispOptions = arrayOf("EBONE", "WATEEN", "ZONG")

        // NEW: zone spinner — which franchise this login belongs to
        // (Okara/Renala/etc). Keep this list in sync with
        // DealerPanelActivity's zoneNames as new franchises come online.
        val zoneOptions = arrayOf("Okara", "Renala")
        val zoneLabel = TextView(this).apply {
            text = "Zone (Franchise)"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#757575"))
        }
        val zoneSpinner = Spinner(this)
        zoneSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, zoneOptions)

        val ispLabel = TextView(this).apply {
            text = "Select ISP"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#757575"))
        }
        val ispSpinner = Spinner(this)
        ispSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ispOptions)

        val etDealerName = EditText(this).apply {
            hint = "Dealer Name (e.g. Akmal)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            visibility = if (isDealer) View.VISIBLE else View.GONE
        }

        val etUsername = EditText(this).apply {
            hint = "ebill.pk Username"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val etPassword = EditText(this).apply {
            hint = "ebill.pk Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        fun updateHints(isp: String) {
            val portal = getPortalUrl(isp)
            etUsername.hint = "$portal Username"
            etPassword.hint = "$portal Password"
        }

        ispSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateHints(ispOptions[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        updateHints("EBONE")

        layout.addView(zoneLabel)
        layout.addView(zoneSpinner)
        layout.addView(ispLabel)
        layout.addView(ispSpinner)
        if (isDealer) layout.addView(etDealerName)
        layout.addView(etUsername)
        layout.addView(etPassword)

        AlertDialog.Builder(this)
            .setTitle(if (isDealer) "Add New Dealer Account" else "Add New ISP Account")
            .setView(layout)
            .setPositiveButton("Save & Login") { _, _ ->
                val isp = ispSpinner.selectedItem.toString()
                val zone = zoneSpinner.selectedItem.toString()
                val dealerName = etDealerName.text.toString().trim()
                val username = etUsername.text.toString().trim()
                val password = etPassword.text.toString()

                if (username.isEmpty() || password.isEmpty()) {
                    Toast.makeText(this, "Username and password required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (isDealer && dealerName.isEmpty()) {
                    Toast.makeText(this, "Dealer name required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val obj = JSONObject().apply {
                    put("isp", isp)
                    put("zone", zone)
                    put("username", username)
                    put("password", password)
                    put("isDealer", isDealer)
                    if (isDealer) put("dealerName", dealerName)
                }

                val arr = getAccounts()
                arr.put(obj)
                saveAccounts(arr)
                renderAccountsList()
                Toast.makeText(this, "Account saved ✅", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getPortalUrl(isp: String) = when (isp) {
        "WATEEN" -> "panel.wateen.com"
        "ZONG"   -> "turbonet.zong.com.pk"
        else     -> "ebill.pk"
    }

    private fun getPrefs(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return try {
            EncryptedSharedPreferences.create(
                this, PREFS_FILE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.e("IspPanelSettingsActivity", "Corrupted encrypted prefs — recreating fresh", e)
            getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
            deleteSharedPreferences(PREFS_FILE)
            EncryptedSharedPreferences.create(
                this, PREFS_FILE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}