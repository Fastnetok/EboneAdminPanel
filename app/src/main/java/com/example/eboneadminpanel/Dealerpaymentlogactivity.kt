package com.example.eboneadminpanel

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Payment Log / A-to-Z History screen — full audit trail of every
 * dealer payment, sourced from "dealerTransactions" (not a separate
 * log), since that collection already carries every stage:
 *   - submittedAt   : when the dealer submitted the payment in-app
 *   - status/verifiedAt : when (if) the SMS-matching engine verified it
 *   - transferStatus/transferredAt : when (if) it was actually sent on
 *     the Ebone panel via "Send Now" / the Send Payment screen
 *
 * Filters: This Month (default), Last Month, or a Custom date range —
 * applied to submittedAt, i.e. when the dealer originally paid.
 *
 * Dealer names are resolved from the "dealers" collection and cached
 * in-memory for the lifetime of this screen (avoids refetching the
 * same dealer's name for every row).
 */
class DealerPaymentLogActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val dealerNameCache = mutableMapOf<String, String>()

    private lateinit var listContainer: LinearLayout
    private lateinit var totalText: TextView
    private lateinit var filterLabel: TextView

    private var rangeStart: Long = 0L
    private var rangeEnd: Long = Long.MAX_VALUE

    private val textDark = Color.parseColor("#172033")
    private val textMuted = Color.parseColor("#667085")
    private val borderLight = Color.parseColor("#E4E7EC")
    private val navyMid = Color.parseColor("#1D4ED8")
    private val green = Color.parseColor("#12B76A")
    private val orange = Color.parseColor("#F79009")
    private val cardWhite = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        setThisMonthRange()
        loadLog()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildScreen(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F4F6FA")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }
        scroll.addView(root)

        val headerRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { finish() }
        })
        headerRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = "Payment History"
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textDark)
            })
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = "Submitted → SMS verified → Sent on panel"
                textSize = 11f
                setTextColor(textMuted)
            })
        })
        root.addView(headerRow)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(16)) })

        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterRow.addView(filterChip("This Month") { setThisMonthRange(); loadLog() })
        filterRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        filterRow.addView(filterChip("Last Month") { setLastMonthRange(); loadLog() })
        filterRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        filterRow.addView(filterChip("Custom") { pickCustomRange() })
        root.addView(filterRow)

        filterLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(textMuted)
            setPadding(dp(2), dp(10), 0, dp(4))
        }
        root.addView(filterLabel)

        totalText = TextView(this).apply {
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(navyMid)
            setPadding(dp(2), 0, 0, dp(16))
        }
        root.addView(totalText)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        return scroll
    }

    private fun filterChip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(navyMid)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(10), dp(4), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), borderLight)
        }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        setOnClickListener { onClick() }
    }

    private fun setThisMonthRange() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        rangeEnd = System.currentTimeMillis()
        filterLabel.text = "This Month (${formatDate(rangeStart)} — today)"
    }

    private fun setLastMonthRange() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.add(Calendar.MONTH, -1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        cal.add(Calendar.MILLISECOND, -1)
        rangeEnd = cal.timeInMillis
        filterLabel.text = "Last Month (${formatDate(rangeStart)} — ${formatDate(rangeEnd)})"
    }

    private fun pickCustomRange() {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y1, m1, d1 ->
            val startCal = Calendar.getInstance().apply {
                set(y1, m1, d1, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }
            DatePickerDialog(this, { _, y2, m2, d2 ->
                val endCal = Calendar.getInstance().apply {
                    set(y2, m2, d2, 23, 59, 59); set(Calendar.MILLISECOND, 999)
                }
                rangeStart = startCal.timeInMillis
                rangeEnd = endCal.timeInMillis
                filterLabel.text = "Custom (${formatDate(rangeStart)} — ${formatDate(rangeEnd)})"
                loadLog()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                .also { it.setTitle("End date") }
                .show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
            .also { it.setTitle("Start date") }
            .show()
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(millis))

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(Date(millis))

    /**
     * NEW: reads the full "dealerTransactions" collection for the
     * selected date range (filtered on submittedAt — when the dealer
     * originally paid) and renders one row per record, each showing
     * every stage that has happened so far: submitted → verified →
     * sent-on-panel. This IS the A-to-Z audit trail — nothing here is
     * a separate/duplicate log.
     */
    private fun loadLog() {
        listContainer.removeAllViews()
        totalText.text = "Loading…"

        db.collection("dealerTransactions")
            .whereGreaterThanOrEqualTo("submittedAt", rangeStart)
            .whereLessThanOrEqualTo("submittedAt", rangeEnd)
            .orderBy("submittedAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                listContainer.removeAllViews()
                if (snapshot.isEmpty) {
                    totalText.text = "No payments in this range"
                    listContainer.addView(TextView(this).apply {
                        text = "Nothing submitted yet for this period."
                        setTextColor(textMuted)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(16), 0, dp(16))
                    })
                    return@addOnSuccessListener
                }

                var total = 0.0
                var verifiedCount = 0
                for (doc in snapshot.documents) {
                    val amount = doc.getDouble("amount") ?: 0.0
                    total += amount
                    if (doc.getString("status") == "VERIFIED") verifiedCount++
                    renderRowWithDealerName(doc)
                }
                totalText.text = "Rs. ${"%,.0f".format(total)} total  •  ${snapshot.size()} submitted  •  $verifiedCount verified"
            }
            .addOnFailureListener { e ->
                totalText.text = "Failed to load: ${e.message}"
            }
    }

    private fun renderRowWithDealerName(doc: DocumentSnapshot) {
        val dealerId = doc.getString("dealerId") ?: ""
        if (dealerId.isEmpty()) {
            listContainer.addView(logRow(doc, "Unknown dealer"))
            return
        }
        val cached = dealerNameCache[dealerId]
        if (cached != null) {
            listContainer.addView(logRow(doc, cached))
            return
        }
        db.collection("dealers").document(dealerId).get()
            .addOnSuccessListener { dealerDoc ->
                val name = dealerDoc.getString("name") ?: dealerId
                dealerNameCache[dealerId] = name
                listContainer.addView(logRow(doc, name))
            }
            .addOnFailureListener {
                listContainer.addView(logRow(doc, dealerId))
            }
    }

    private fun logRow(doc: DocumentSnapshot, dealerName: String): LinearLayout {
        val panel = doc.getString("panel") ?: "?"
        val amount = doc.getDouble("amount") ?: 0.0
        val tid = doc.getString("bankTransactionId") ?: ""
        val status = doc.getString("status") ?: "PENDING"
        val submittedAt = doc.getLong("submittedAt") ?: 0L
        val verifiedAt = doc.getLong("verifiedAt")
        val transferStatus = doc.getString("transferStatus") ?: ""
        val transferredAt = doc.getLong("transferredAt")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(cardWhite)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), borderLight)
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }

        val topRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = dealerName
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textDark)
            })
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = "$panel  •  TID: $tid"
                textSize = 11f
                setTextColor(textMuted)
            })
        })
        topRow.addView(TextView(this).apply {
            text = "Rs. ${"%,.0f".format(amount)}"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(navyMid)
        })
        card.addView(topRow)

        card.addView(View(this).apply {
            setBackgroundColor(borderLight)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).also { it.topMargin = dp(10); it.bottomMargin = dp(8) }
        })

        card.addView(stageLine("Submitted", formatTime(submittedAt), textMuted))
        card.addView(
            if (verifiedAt != null)
                stageLine("SMS Verified", formatTime(verifiedAt), green)
            else
                stageLine("SMS Verified", "Waiting…", orange)
        )
        card.addView(
            if (transferStatus == "TRANSFERRED" && transferredAt != null)
                stageLine("Sent on $panel panel", formatTime(transferredAt), green)
            else if (status == "VERIFIED")
                stageLine("Sent on $panel panel", "Not sent yet", orange)
            else
                stageLine("Sent on $panel panel", "—", textMuted)
        )

        return card
    }

    private fun stageLine(label: String, value: String, valueColor: Int): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = label
                textSize = 11f
                setTextColor(textMuted)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(TextView(this@DealerPaymentLogActivity).apply {
                text = value
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(valueColor)
            })
        }
}