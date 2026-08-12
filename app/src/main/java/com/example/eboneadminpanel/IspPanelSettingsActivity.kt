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

        fun getSavedUsername(context: Context, isp: String): String? {
            return try {
                val json = getPrefs(context).getString(KEY_ACCOUNTS, null) ?: return null
                val arr = JSONArray(json)
                // Step 1: Non-Dealer Account Pehle
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("isp") == isp &&
                        !obj.optBoolean("isDealer", false)) {
                        return obj.getString("username")
                    }
                }
                // Step 2: Dealer Account Bhi Try Karein
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("isp") == isp) {
                        return obj.getString("username")
                    }
                }
                null
            } catch (_: Exception) { null }
        }

        fun getSavedPassword(context: Context, isp: String): String? {
            return try {
                val json = getPrefs(context).getString(KEY_ACCOUNTS, null) ?: return null
                val arr = JSONArray(json)
                // Step 1: Non-Dealer Account Pehle
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("isp") == isp &&
                        !obj.optBoolean("isDealer", false)) {
                        return obj.getString("password")
                    }
                }
                // Step 2: Dealer Account Bhi Try Karein
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.getString("isp") == isp) {
                        return obj.getString("password")
                    }
                }
                null
            } catch (_: Exception) { null }
        }

        private fun getPrefs(context: Context) =
            EncryptedSharedPreferences.create(
                context, PREFS_FILE,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
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
                    "$dealerName (Dealer) · $isp"
                else isp
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
                                if (enteredPassword == password) {
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

    private fun getPrefs() =
        EncryptedSharedPreferences.create(
            this, PREFS_FILE,
            MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
}