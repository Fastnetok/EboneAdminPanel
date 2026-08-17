package com.example.eboneadminpanel

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Main landing screen for the Dealer/Franchise side of EboneAdminPanel —
 * follows the reference design: header, 3 stat cards, Receiving Accounts
 * summary, Quick Menu grid, Recent Payments feed, bottom nav.
 *
 * LIVE (real Firestore data):
 *   - Total Dealers, Total Collection (this month), Pending Payments
 *   - Recent Payments feed (from dealerTransactions)
 *
 * PLACEHOLDER (not built yet — tapping shows "Coming soon"):
 *   - Receiving Accounts management, Network & Zones, Complaints, Reports
 *   - "Dealers" IS wired up (→ DealerPanelActivity)
 */
class DealerDashboardActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private var dealersListener: ListenerRegistration? = null
    private var paymentsListener: ListenerRegistration? = null

    private lateinit var totalDealersText: TextView
    private lateinit var totalCollectionText: TextView
    private lateinit var pendingPaymentsText: TextView
    private lateinit var recentPaymentsList: LinearLayout

    // ===================== PALETTE (matches reference screenshot) =====================
    private val headerBlue = Color.parseColor("#0D3B8C")
    private val bgLight = Color.parseColor("#F4F6FA")
    private val cardWhite = Color.WHITE
    private val textDark = Color.parseColor("#1A2233")
    private val textMuted = Color.parseColor("#6B7280")
    private val borderLight = Color.parseColor("#E5E8EE")
    private val blueAccent = Color.parseColor("#1D4ED8")
    private val greenAccent = Color.parseColor("#16A34A")
    private val orangeAccent = Color.parseColor("#F59E0B")
    private val purpleAccent = Color.parseColor("#7C3AED")
    private val quickMenuBg = Color.parseColor("#F0F3FB")

    private val avatarColors = listOf(blueAccent, greenAccent, purpleAccent, orangeAccent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        observeDealerStats()
        observeRecentPayments()
    }

    override fun onDestroy() {
        dealersListener?.remove()
        paymentsListener?.remove()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    // ===================== SCREEN LAYOUT =====================

    private fun buildScreen(): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgLight)
        }

        outer.addView(buildHeader())

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(content)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "Dashboard"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textDark)
        })
        content.addView(TextView(this).apply {
            text = "Welcome back, Admin!"
            textSize = 14f
            setTextColor(textMuted)
            setPadding(0, dp(2), 0, dp(16))
        })

        content.addView(buildStatsRow())
        content.addView(spacer(16))
        content.addView(buildReceivingAccountsCard())
        content.addView(spacer(16))
        content.addView(buildQuickMenuCard())
        content.addView(spacer(16))
        content.addView(buildRecentPaymentsCard())
        content.addView(spacer(16))

        outer.addView(buildBottomNav())
        return outer
    }

    private fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(heightDp))
    }

    // ===================== HEADER =====================

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerBlue)
            setPadding(dp(16), dp(44), dp(16), dp(20))
        }

        header.addView(TextView(this).apply {
            text = "☰"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(16), 0)

            setOnClickListener {
                startActivity(
                    Intent(
                        this@DealerDashboardActivity,
                        DealerMenuActivity::class.java
                    )
                )
            }
        })

        header.addView(TextView(this).apply {
            text = "Ebone Franchise Panel"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        header.addView(TextView(this).apply {
            text = "🔔"
            textSize = 20f
            setOnClickListener {
                Toast.makeText(this@DealerDashboardActivity, "Notifications — coming soon", Toast.LENGTH_SHORT).show()
            }
        })

        return header
    }

    // ===================== STAT CARDS =====================

    private fun buildStatsRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun card(icon: String, iconBg: Int, label: String): Pair<LinearLayout, TextView> {
            val valueText = TextView(this).apply {
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textDark)
                setPadding(0, dp(10), 0, dp(2))
            }
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = GradientDrawable().apply {
                    setColor(cardWhite)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), borderLight)
                }
                elevation = dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(8) }

                addView(TextView(this@DealerDashboardActivity).apply {
                    text = icon
                    textSize = 16f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    background = rounded(iconBg, 18)
                })
                addView(valueText)
                addView(TextView(this@DealerDashboardActivity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(textMuted)
                })
            }
            return c to valueText
        }

        val (dealersCard, dealersVal) = card("👥", blueAccent, "Total Dealers")
        val (collectionCard, collectionVal) = card("💰", greenAccent, "Total Collection")
        val (pendingCard, pendingVal) = card("⏱", orangeAccent, "Pending Payments")

        totalDealersText = dealersVal
        totalCollectionText = collectionVal
        pendingPaymentsText = pendingVal

        row.addView(dealersCard)
        row.addView(collectionCard)
        pendingCard.layoutParams = (pendingCard.layoutParams as LinearLayout.LayoutParams).also { it.marginEnd = 0 }
        row.addView(pendingCard)
        return row
    }

    // ===================== RECEIVING ACCOUNTS =====================

    private fun buildReceivingAccountsCard(): LinearLayout {
        val card = whiteCard()

        val headerRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(this).apply {
            text = "Receiving Accounts"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textDark)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = "Manage ›"
            textSize = 13f
            setTextColor(blueAccent)
            setOnClickListener {
                Toast.makeText(this@DealerDashboardActivity, "Manage Receiving Accounts — coming soon", Toast.LENGTH_SHORT).show()
            }
        })
        card.addView(headerRow)

        val accountsRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.parseColor("#EEF2FF"), 10)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) }
        }
        accountsRow.addView(TextView(this).apply {
            text = "🏦  Faysal Bank"
            textSize = 13f
            setTextColor(textDark)
        })
        accountsRow.addView(TextView(this).apply {
            text = "  |  "
            setTextColor(textMuted)
        })
        accountsRow.addView(TextView(this).apply {
            text = "👛  Jazz Till ID"
            textSize = 13f
            setTextColor(textDark)
        })
        card.addView(accountsRow)

        return card
    }

    // ===================== QUICK MENU =====================

    private fun buildQuickMenuCard(): LinearLayout {
        val card = whiteCard()
        card.addView(TextView(this).apply {
            text = "Quick Menu"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textDark)
        })

        fun menuItem(icon: String, label: String, iconColor: Int, onClick: () -> Unit): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                setOnClickListener { onClick() }
                addView(TextView(this@DealerDashboardActivity).apply {
                    text = icon
                    textSize = 20f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
                    background = rounded(quickMenuBg, 14)
                })
                addView(TextView(this@DealerDashboardActivity).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(textDark)
                    setPadding(0, dp(6), 0, 0)
                })
            }

        val row1 = LinearLayout(this).apply { setPadding(0, dp(14), 0, 0) }
        row1.addView(menuItem("👥", "Dealers", blueAccent) {
            startActivity(Intent(this, DealerPanelActivity::class.java))
        })
        row1.addView(menuItem("💳", "Payments", greenAccent) {
            Toast.makeText(this, "Payments history — coming soon", Toast.LENGTH_SHORT).show()
        })
        row1.addView(menuItem("👛", "Receiving\nAccounts", purpleAccent) {
            Toast.makeText(this, "Receiving Accounts — coming soon", Toast.LENGTH_SHORT).show()
        })
        row1.addView(menuItem("🌐", "Network &\nZones", orangeAccent) {
            Toast.makeText(this, "Network & Zones — coming soon", Toast.LENGTH_SHORT).show()
        })
        card.addView(row1)

        val row2 = LinearLayout(this).apply { setPadding(0, dp(4), 0, 0) }
        row2.addView(menuItem("⚠️", "Complaints", Color.parseColor("#DC2626")) {
            Toast.makeText(this, "Dealer complaints — coming soon", Toast.LENGTH_SHORT).show()
        })
        row2.addView(menuItem("📊", "Reports", purpleAccent) {
            Toast.makeText(this, "Reports — coming soon", Toast.LENGTH_SHORT).show()
        })
        row2.addView(menuItem("＋", "More", textMuted) {
            Toast.makeText(this, "More — coming soon", Toast.LENGTH_SHORT).show()
        })
        row2.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        card.addView(row2)

        return card
    }

    // ===================== RECENT PAYMENTS =====================

    private fun buildRecentPaymentsCard(): LinearLayout {
        val card = whiteCard()

        val headerRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(this).apply {
            text = "Recent Payments"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textDark)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = "View All"
            textSize = 13f
            setTextColor(blueAccent)
            setOnClickListener {
                Toast.makeText(this@DealerDashboardActivity, "Full payments list — coming soon", Toast.LENGTH_SHORT).show()
            }
        })
        card.addView(headerRow)

        recentPaymentsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(recentPaymentsList)

        return card
    }

    private fun whiteCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(cardWhite)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), borderLight)
        }
        elevation = dp(1).toFloat()
    }

    // ===================== LIVE DATA =====================

    private fun observeDealerStats() {
        dealersListener = db.collection("dealers")
            .addSnapshotListener { query, _ ->
                totalDealersText.text = (query?.size() ?: 0).toString()
            }

        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        val monthStart = cal.timeInMillis

        db.collection("dealerTransactions")
            .whereEqualTo("status", "VERIFIED")
            .whereGreaterThanOrEqualTo("submittedAt", monthStart)
            .addSnapshotListener { query, _ ->
                val total = query?.documents?.sumOf { it.getDouble("amount") ?: 0.0 } ?: 0.0
                totalCollectionText.text = "Rs. ${"%,.0f".format(total)}"
            }

        db.collection("dealerTransactions")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { query, _ ->
                val total = query?.documents?.sumOf { it.getDouble("amount") ?: 0.0 } ?: 0.0
                pendingPaymentsText.text = "Rs. ${"%,.0f".format(total)}"
            }
    }

    private fun observeRecentPayments() {
        paymentsListener = db.collection("dealerTransactions")
            .orderBy("submittedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(6)
            .addSnapshotListener { query, error ->
                if (error != null || query == null) return@addSnapshotListener
                recentPaymentsList.removeAllViews()

                if (query.isEmpty) {
                    recentPaymentsList.addView(TextView(this).apply {
                        text = "No payments yet"
                        setTextColor(textMuted)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(16), 0, dp(16))
                    })
                    return@addSnapshotListener
                }

                query.documents.forEachIndexed { index, doc ->
                    fetchDealerNameAndBuildRow(doc, index)
                }
            }
    }

    private fun fetchDealerNameAndBuildRow(doc: com.google.firebase.firestore.DocumentSnapshot, colorIndex: Int) {
        val dealerId = doc.getString("dealerId") ?: ""
        val panel = doc.getString("panel") ?: "?"
        val amount = doc.getDouble("amount") ?: 0.0
        val status = doc.getString("status") ?: "PENDING"
        val submittedAt = doc.getLong("submittedAt") ?: System.currentTimeMillis()

        db.collection("dealers").document(dealerId).get()
            .addOnSuccessListener { dealerDoc ->
                val dealerName = dealerDoc.getString("name") ?: "Dealer"
                recentPaymentsList.addView(
                    paymentRow(panel, dealerName, amount, status, submittedAt, colorIndex)
                )
            }
            .addOnFailureListener {
                recentPaymentsList.addView(
                    paymentRow(panel, "Dealer", amount, status, submittedAt, colorIndex)
                )
            }
    }

    private fun paymentRow(
        panel: String, dealerName: String, amount: Double,
        status: String, submittedAt: Long, colorIndex: Int
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        row.addView(TextView(this).apply {
            text = panel.take(1).uppercase()
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).also { it.marginEnd = dp(10) }
            background = rounded(avatarColors[colorIndex % avatarColors.size], 18)
        })

        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerDashboardActivity).apply {
                text = panel
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textDark)
            })
            addView(TextView(this@DealerDashboardActivity).apply {
                text = dealerName
                textSize = 11f
                setTextColor(textMuted)
            })
        })

        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(TextView(this@DealerDashboardActivity).apply {
                text = "Rs. ${"%,.0f".format(amount)}"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textDark)
                gravity = Gravity.END
            })
            addView(TextView(this@DealerDashboardActivity).apply {
                text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(submittedAt))
                textSize = 10f
                setTextColor(textMuted)
                gravity = Gravity.END
            })
        })

        row.addView(TextView(this).apply {
            text = if (status == "VERIFIED") "Paid" else "Pending"
            textSize = 11f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setTextColor(if (status == "VERIFIED") greenAccent else orangeAccent)
            background = rounded(
                if (status == "VERIFIED") Color.parseColor("#E8F8EE") else Color.parseColor("#FFF4E5"),
                12
            )
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(10) }
        })

        return row
    }

    // ===================== BOTTOM NAV =====================

    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            setBackgroundColor(cardWhite)
            setPadding(0, dp(10), 0, dp(10))
            elevation = dp(4).toFloat()
        }

        fun navItem(icon: String, label: String, active: Boolean, onClick: () -> Unit): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                isClickable = true
                setOnClickListener { onClick() }
                addView(TextView(this@DealerDashboardActivity).apply {
                    text = icon
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(if (active) blueAccent else textMuted)
                })
                addView(TextView(this@DealerDashboardActivity).apply {
                    text = label
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(if (active) blueAccent else textMuted)
                })
            }

        nav.addView(navItem("🏠", "Dashboard", true) { })
        nav.addView(navItem("👥", "Dealers", false) {
            startActivity(Intent(this, DealerPanelActivity::class.java))
        })
        nav.addView(navItem("💳", "Payments", false) {
            Toast.makeText(this, "Payments — coming soon", Toast.LENGTH_SHORT).show()
        })
        nav.addView(navItem("⚠️", "Complaints", false) {
            Toast.makeText(this, "Complaints — coming soon", Toast.LENGTH_SHORT).show()
        })
        nav.addView(navItem("⋯", "More", false) {
            Toast.makeText(this, "More — coming soon", Toast.LENGTH_SHORT).show()
        })

        return nav
    }
}