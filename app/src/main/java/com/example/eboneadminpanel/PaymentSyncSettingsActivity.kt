package com.example.eboneadminpanel

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * "Payment Sync Settings" — reached from CustomerBillingActivity's
 * top-left hamburger icon (previously that icon just called finish(),
 * duplicating the system back button for no reason). Lets the admin:
 *   - Turn background auto-sync on/off
 *   - Choose how often it runs (15 / 30 / 45 / 60 min — 15 is
 *     Android's own minimum for periodic background work)
 *   - Trigger an immediate manual sync ("Sync Now")
 */
class PaymentSyncSettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "payment_sync_prefs"
        private const val KEY_ENABLED = "auto_sync_enabled"
        private const val KEY_INTERVAL = "auto_sync_interval_minutes"

        fun isAutoSyncEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun getIntervalMinutes(context: Context): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_INTERVAL, 15L)

        /** Call once at app startup (e.g. from EboneAdminApp) to make sure
         * the worker is scheduled according to the last saved settings. */
        fun applySavedSchedule(context: Context) {
            if (isAutoSyncEnabled(context)) {
                PaymentSyncWorker.schedule(context, getIntervalMinutes(context))
            } else {
                PaymentSyncWorker.cancel(context)
            }
        }
    }

    private lateinit var switchEnabled: Switch
    private lateinit var radioGroup: RadioGroup
    private lateinit var tvLastSync: TextView
    private lateinit var btnSyncNow: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    private fun getPrefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6FA"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0D2E5C"))
            setPadding(px(16, dp), px(48, dp), px(16, dp), px(16, dp))
        }
        ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            background = null
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(px(28, dp), px(28, dp))
            setOnClickListener { finish() }
        }.also { header.addView(it) }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = px(12, dp) }
            addView(tv("Payment Sync Settings", 16f, Color.WHITE, bold = true))
            addView(tv("Auto-check pending payments against SMS", 12f, Color.parseColor("#B8C6DE")))
        }.also { header.addView(it) }
        root.addView(header)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16, dp), px(16, dp), px(16, dp), px(16, dp))
        }
        scroll.addView(content)

        // Toggle card
        val toggleCard = card(dp)
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toggleRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(tv("Auto-Sync Pending Payments", 14f, Color.parseColor("#111111"), bold = true))
            addView(tv("Automatically re-check SMS inbox for waiting payments", 12f, Color.parseColor("#757575")).also {
                it.setPadding(0, px(2, dp), 0, 0)
            })
        })
        switchEnabled = Switch(this)
        toggleRow.addView(switchEnabled)
        toggleCard.addView(toggleRow)
        content.addView(toggleCard)

        // Interval card
        val intervalCard = card(dp)
        intervalCard.addView(tv("Check Every", 13f, Color.parseColor("#757575")).also {
            it.setPadding(0, 0, 0, px(8, dp))
        })
        radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val options = listOf(15L to "15 minutes", 30L to "30 minutes", 45L to "45 minutes", 60L to "1 hour")
        options.forEach { (minutes, label) ->
            RadioButton(this).apply {
                id = minutes.toInt()
                text = label
                textSize = 14f
                setPadding(0, px(8, dp), 0, px(8, dp))
            }.also { radioGroup.addView(it) }
        }
        intervalCard.addView(radioGroup)
        intervalCard.addView(tv(
            "Note: Android does not allow background checks more often than every 15 minutes — this is a system battery-saving limit, the same for every app.",
            11f, Color.parseColor("#9E9E9E")
        ).also { it.setPadding(0, px(10, dp), 0, 0) })
        content.addView(intervalCard)

        // Manual sync card
        val manualCard = card(dp)
        manualCard.addView(tv("Manual Sync", 14f, Color.parseColor("#111111"), bold = true))
        manualCard.addView(tv("Check right now instead of waiting for the schedule", 12f, Color.parseColor("#757575")).also {
            it.setPadding(0, px(2, dp), 0, px(12, dp))
        })
        btnSyncNow = Button(this).apply {
            text = "🔄 Sync Now"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f * dp; setColor(Color.parseColor("#1565C0")) }
            setOnClickListener { runManualSync() }
        }
        manualCard.addView(btnSyncNow)
        tvLastSync = tv("", 11f, Color.parseColor("#9E9E9E")).also {
            it.gravity = Gravity.CENTER
            it.setPadding(0, px(8, dp), 0, 0)
        }
        manualCard.addView(tvLastSync)
        content.addView(manualCard)

        // Save button
        Button(this).apply {
            text = "Save Settings"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f * dp; setColor(Color.parseColor("#2E7D32")) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = px(8, dp) }
            setOnClickListener { saveSettings() }
        }.also { content.addView(it) }

        root.addView(scroll)

        // Load saved values into the UI
        val prefs = getPrefs()
        switchEnabled.isChecked = prefs.getBoolean(KEY_ENABLED, true)
        val savedInterval = prefs.getLong(KEY_INTERVAL, 15L)
        radioGroup.check(savedInterval.toInt())

        return root
    }

    private fun runManualSync() {
        btnSyncNow.isEnabled = false
        btnSyncNow.text = "Checking SMS inbox..."
        Thread {
            val matched = PaymentSmsScanner.scanAllPending(this)
            runOnUiThread {
                btnSyncNow.isEnabled = true
                btnSyncNow.text = "🔄 Sync Now"
                tvLastSync.text = if (matched > 0)
                    "✅ $matched payment(s) activated just now"
                else
                    "No matching payments found in SMS inbox"
            }
        }.start()
    }

    private fun saveSettings() {
        val enabled = switchEnabled.isChecked
        val checkedId = radioGroup.checkedRadioButtonId
        val interval = if (checkedId > 0) checkedId.toLong() else 15L

        getPrefs().edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putLong(KEY_INTERVAL, interval)
            .apply()

        if (enabled) {
            PaymentSyncWorker.schedule(this, interval)
            Toast.makeText(this, "Auto-sync enabled — checking every $interval min", Toast.LENGTH_SHORT).show()
        } else {
            PaymentSyncWorker.cancel(this)
            Toast.makeText(this, "Auto-sync disabled", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    // ─────────────── HELPERS ───────────────

    private fun px(v: Int, dp: Float) = (v * dp).toInt()
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }
    private fun card(dp: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f * dp; setColor(Color.WHITE) }
        setPadding(px(14, dp), px(14, dp), px(14, dp), px(14, dp))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = px(12, dp) }
    }
}