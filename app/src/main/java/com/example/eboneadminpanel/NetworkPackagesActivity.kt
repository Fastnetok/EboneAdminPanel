package com.example.eboneadminpanel

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Full breakdown of ALL registered customers (from AddCustomerActivity),
 * grouped by ISP network — EBONE / WATEEN / ZONG. This is the "how many
 * packages have we sold on each network, total" view — separate from the
 * Customer Billing dashboard, which only shows TODAY's SMS-matched
 * payments.
 */
class NetworkPackagesActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private var customersListener: ListenerRegistration? = null
    private val companies = listOf("EBONE", "WATEEN", "ZONG")

    private lateinit var contentContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        startListening()
    }

    // ─────────────── LAYOUT ───────────────

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
            addView(tv("Package Distribution", 16f, Color.WHITE, bold = true))
            addView(tv("All registered customers by network", 12f, Color.parseColor("#B8C6DE")))
        }.also { header.addView(it) }
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12, dp), px(12, dp), px(12, dp), px(12, dp))
        }
        contentContainer.addView(tv("Loading...", 13f, Color.parseColor("#9E9E9E")).also {
            it.gravity = Gravity.CENTER
            it.setPadding(0, px(30, dp), 0, px(30, dp))
        })
        scroll.addView(contentContainer)
        root.addView(scroll)
        return root
    }

    // ─────────────── DATA ───────────────

    private fun startListening() {
        customersListener = db.collection("customers")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                render(snapshot.documents)
            }
    }

    private fun isCustomerActive(doc: DocumentSnapshot): Boolean {
        val activationStatus = doc.getString("activationStatus") ?: "ACTIVE"
        val lastPaymentDate = doc.getLong("lastPaymentDate") ?: return false
        val billingCycleDays = (doc.getLong("billingCycleDays") ?: 30L).toInt()
        if (activationStatus == "PENDING_APPROVAL") return false
        val expiresAt = lastPaymentDate + (billingCycleDays * 24L * 60L * 60L * 1000L)
        return System.currentTimeMillis() < expiresAt
    }

    private fun render(docs: List<DocumentSnapshot>) {
        val dp = resources.displayMetrics.density
        contentContainer.removeAllViews()

        // Group all customers by network
        val byNetwork = mutableMapOf<String, MutableList<DocumentSnapshot>>()
        companies.forEach { byNetwork[it] = mutableListOf() }
        docs.forEach { doc ->
            val isp = doc.getString("ispProvider") ?: "EBONE"
            byNetwork.getOrPut(isp) { mutableListOf() }.add(doc)
        }

        // Grand total card
        val totalCustomers = docs.size
        val totalValue = docs.sumOf { it.getDouble("packagePrice") ?: 0.0 }
        val grandCard = card(dp, Color.parseColor("#0D2E5C"))
        grandCard.addView(tv("Total Customers (All Networks)", 12f, Color.parseColor("#B8C6DE")))
        grandCard.addView(tv("$totalCustomers", 26f, Color.WHITE, bold = true).also {
            it.setPadding(0, px(4, dp), 0, px(2, dp))
        })
        grandCard.addView(tv("Rs %,.0f total package value".format(totalValue), 12f, Color.parseColor("#B8C6DE")))
        contentContainer.addView(grandCard)

        // Per-network cards
        companies.forEach { isp ->
            val custList = byNetwork[isp] ?: emptyList()
            val count = custList.size
            val activeCount = custList.count { isCustomerActive(it) }
            val disabledCount = count - activeCount
            val value = custList.sumOf { it.getDouble("packagePrice") ?: 0.0 }

            val netCard = card(dp, Color.WHITE)
            netCard.isClickable = true; netCard.isFocusable = true
            netCard.setOnClickListener { showCustomerListDialog(isp, custList) }

            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(tv(isp, 15f, Color.parseColor("#111111"), bold = true).also {
                it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            titleRow.addView(tv("$count packages", 13f, Color.parseColor("#1565C0"), bold = true))
            netCard.addView(titleRow)

            val statsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px(10, dp), 0, 0)
            }
            statsRow.addView(miniStat("Active", "$activeCount", "#2E7D32", dp).also {
                it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { m -> m.marginEnd = px(6, dp) }
            })
            statsRow.addView(miniStat("Disabled", "$disabledCount", "#C62828", dp).also {
                it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { m -> m.marginEnd = px(6, dp) }
            })
            statsRow.addView(miniStat("Value", "Rs %,.0f".format(value), "#0D2E5C", dp).also {
                it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            netCard.addView(statsRow)

            netCard.addView(tv("Tap to view customer list ›", 11f, Color.parseColor("#9E9E9E")).also {
                it.setPadding(0, px(8, dp), 0, 0)
            })

            contentContainer.addView(netCard)
        }
    }

    private fun showCustomerListDialog(isp: String, custList: List<DocumentSnapshot>) {
        val dp = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16, dp), px(8, dp), px(16, dp), px(8, dp))
        }
        scroll.addView(container)

        if (custList.isEmpty()) {
            container.addView(tv("No customers on $isp yet.", 13f, Color.parseColor("#9E9E9E")).also {
                it.gravity = Gravity.CENTER; it.setPadding(0, px(20, dp), 0, px(20, dp))
            })
        } else {
            custList.sortedBy { it.getString("customerId") ?: "" }.forEachIndexed { i, doc ->
                val customerId = doc.getString("customerId") ?: doc.id
                val packageId = doc.getString("packageId") ?: "—"
                val price = doc.getDouble("packagePrice") ?: 0.0
                val active = isCustomerActive(doc)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, px(10, dp), 0, px(10, dp))
                    setBackgroundColor(if (i % 2 == 0) Color.parseColor("#FAFAFA") else Color.WHITE)
                }
                val textBlock = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                textBlock.addView(tv(customerId, 13f, Color.parseColor("#111111"), bold = true))
                textBlock.addView(tv(packageId, 11f, Color.parseColor("#757575")))
                row.addView(textBlock)

                val rightBlock = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                }
                rightBlock.addView(tv("Rs %,.0f".format(price), 13f, Color.parseColor("#111111"), bold = true))
                rightBlock.addView(tv(if (active) "Active" else "Disabled", 11f,
                    Color.parseColor(if (active) "#2E7D32" else "#C62828")))
                row.addView(rightBlock)

                container.addView(row)
                container.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#EEEEEE"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                })
            }
        }

        AlertDialog.Builder(this)
            .setTitle("$isp — ${custList.size} Customers")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    // ─────────────── HELPERS ───────────────

    private fun px(v: Int, dp: Float) = (v * dp).toInt()
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }
    private fun card(dp: Float, bg: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f * dp; setColor(bg) }
        setPadding(px(14, dp), px(14, dp), px(14, dp), px(14, dp))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = px(10, dp) }
    }
    private fun miniStat(label: String, value: String, colorHex: String, dp: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 8f * dp; setColor(Color.parseColor("#F4F6FA")) }
        setPadding(px(8, dp), px(8, dp), px(8, dp), px(8, dp))
        addView(tv(value, 13f, Color.parseColor(colorHex), bold = true))
        addView(tv(label, 10f, Color.parseColor("#757575")))
    }

    override fun onDestroy() {
        super.onDestroy()
        customersListener?.remove()
    }
}