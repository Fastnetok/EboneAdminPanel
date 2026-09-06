package com.example.eboneadminpanel

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * NEW, STANDALONE screen — does not touch DealerPanelActivity,
 * IspPanelSettingsActivity, or any other existing file's logic.
 *
 * Lets the admin enable/disable which payment methods show up on the
 * Customer ID App's "Select Payment Method" screen (Easypaisa, JazzCash,
 * SadaPay, Faysal Bank, Raast ID, Bank Alfalah, Other Bank Transfer) —
 * same on/off Switch pattern already used for each dealer's payment
 * accounts in DealerPanelActivity.showDealerDetails(), just applied
 * globally instead of per-dealer.
 *
 * Reads/writes a single Firestore document: appSettings/paymentMethods
 * (boolean fields: easypaisa, jazzcash, sadapay, faysalbank, raastid,
 * bankalfalah, otherbank). PaymentMethodActivity in the Customer ID App
 * already reads this same document and defaults every field to
 * true/visible if the document or a field is missing — so nothing here
 * can break anything already working; it only ever narrows down what
 * shows.
 *
 * Entire screen is built programmatically (same approach as
 * DealerPanelActivity) — no XML layout file needed, so nothing in
 * res/layout is touched by adding this feature.
 */
class PaymentMethodSettingsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    // (Firestore field name, display label)
    private val methods = listOf(
        "easypaisa" to "Easypaisa",
        "jazzcash" to "JazzCash",
        "sadapay" to "SadaPay",
        "faysalbank" to "Faysal Bank",
        "raastid" to "Raast ID",
        "bankalfalah" to "Bank Alfalah",
        "otherbank" to "Other Bank Transfer"
    )

    private val switches = mutableMapOf<String, Switch>()

    private val bgLight = Color.parseColor("#F4F6FA")
    private val navyDark = Color.parseColor("#0D1B3E")
    private val navyMid = Color.parseColor("#1D4ED8")
    private val cardWhite = Color.WHITE
    private val textDark = Color.parseColor("#172033")
    private val textMuted = Color.parseColor("#667085")
    private val borderLight = Color.parseColor("#E4E7EC")
    private val green = Color.parseColor("#12B76A")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        loadCurrentSettings()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildScreen(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgLight)
        }

        // Header — same navy gradient look used across the admin panel's
        // other screens (DealerPanelActivity), for visual consistency.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(navyDark, navyMid)
            )
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { finish() }
        })
        titleRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@PaymentMethodSettingsActivity).apply {
                text = "Payment Methods"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@PaymentMethodSettingsActivity).apply {
                text = "Choose which methods customers can pay with"
                textSize = 12f
                setTextColor(Color.parseColor("#B8C2E0"))
            })
        })
        header.addView(titleRow)
        root.addView(header)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        content.addView(TextView(this).apply {
            text = "Turned-off methods disappear from the Customer ID App's payment screen immediately. Customers already mid-payment on a method you disable are unaffected."
            textSize = 12f
            setTextColor(textMuted)
            setPadding(dp(4), 0, dp(4), dp(16))
        })

        methods.forEach { (field, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    setColor(cardWhite)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), borderLight)
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(textDark)
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            val switch = Switch(this).apply { isChecked = true } // default ON until Firestore loads
            switches[field] = switch
            row.addView(switch)
            content.addView(row)
        }

        content.addView(Button(this).apply {
            text = "Save"
            setBackgroundColor(green)
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) }
            setOnClickListener { saveSettings() }
        })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    /** Loads current on/off state from Firestore. If the document or a
     * field doesn't exist yet, that switch simply stays ON (default),
     * matching PaymentMethodActivity's own default-enabled behavior in
     * the Customer ID App.
     *
     * FIX: the failure case now shows the REAL Firestore error (and logs
     * it) instead of failing silently — needed to diagnose why the
     * Customer ID App wasn't picking up saved changes (most likely a
     * Firestore Security Rules permission issue, since the Customer ID
     * App reads this same document without any signed-in Firebase user,
     * unlike this Admin Panel). */
    private fun loadCurrentSettings() {
        db.collection("appSettings").document("paymentMethods")
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener
                methods.forEach { (field, _) ->
                    val enabled = doc.getBoolean(field) ?: true
                    switches[field]?.isChecked = enabled
                }
            }
            .addOnFailureListener { e ->
                Log.e("PaymentMethodSettings", "Load failed", e)
                Toast.makeText(this, "Could not load current settings: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * FIX: after a successful save, this now returns to the previous
     * screen (Customer Billing) automatically instead of staying open —
     * per explicit instruction: "save karne ke baad go to home aa jana
     * chahiye".
     */
    private fun saveSettings() {
        val updates = methods.associate { (field, _) -> field to (switches[field]?.isChecked ?: true) }
        db.collection("appSettings").document("paymentMethods")
            .set(updates, SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(this, "Saved ✅", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                Log.e("PaymentMethodSettings", "Save failed", e)
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}