package com.example.eboneadminpanel

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DealerPanelActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private var dealersListener: ListenerRegistration? = null
    private var pendingListener: ListenerRegistration? = null

    private lateinit var statDealersText: TextView
    private lateinit var statPendingText: TextView
    private lateinit var statBalanceText: TextView
    private lateinit var pendingList: LinearLayout
    private lateinit var dealerList: LinearLayout

    private var dealerCount = 0
    private var totalBalance = 0.0

    private val paymentAccountNames = listOf(
        "EasyPaisa", "JazzCash", "SadaPay", "Raast ID",
        "Till ID", "Faisal Bank", "Alfalah Bank", "Other Bank"
    )

    // ===================== PALETTE =====================
    private val bgLight = Color.parseColor("#F4F6FA")
    private val navyDark = Color.parseColor("#0D1B3E")
    private val navyMid = Color.parseColor("#1D4ED8")
    private val cardWhite = Color.WHITE
    private val textDark = Color.parseColor("#172033")
    private val textMuted = Color.parseColor("#667085")
    private val borderLight = Color.parseColor("#E4E7EC")
    private val green = Color.parseColor("#12B76A")
    private val orange = Color.parseColor("#F79009")
    private val purple = Color.parseColor("#7A5AF8")
    private val amberBg = Color.parseColor("#FFF4E5")
    private val amberText = Color.parseColor("#B45309")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        observeDealers()
        observePending()
    }

    override fun onDestroy() {
        dealersListener?.remove()
        pendingListener?.remove()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, radiusDp: Int = 20): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun outlinedPill(bg: Int, strokeColor: Int, radiusDp: Int = 12): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
        }

    // ===================== SCREEN =====================

    private fun buildScreen(): ScrollView {
        val outerScroll = ScrollView(this).apply {
            setBackgroundColor(bgLight)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        outerScroll.addView(root)

        root.addView(buildHeader())
        root.addView(buildStatsRow())
        root.addView(sectionTitle("Pending Payments"))
        pendingList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(pendingList)

        root.addView(sectionTitle("Dealers"))
        dealerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        root.addView(dealerList)

        return outerScroll
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(navyDark, navyMid)
            )
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }

        val topRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { finish() }
        })
        topRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Dealer Panel"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Balances, payments & verification"
                textSize = 12f
                setTextColor(Color.parseColor("#B8C2E0"))
            })
        })
        topRow.addView(iconButton("⟳") { scanNow() })
        topRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        topRow.addView(iconButton("+") { showAddDealerDialog() })

        header.addView(topRow)
        return header
    }

    private fun iconButton(symbol: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#2A3B6E"))
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun buildStatsRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(4))
        }

        fun statCard(label: String, colorAccent: Int): Pair<LinearLayout, TextView> {
            val valueText = TextView(this).apply {
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textDark)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(cardWhite)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), borderLight)
                }
                elevation = dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also {
                    it.marginEnd = dp(8)
                }
                addView(View(this@DealerPanelActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(4)).also { it.bottomMargin = dp(8) }
                    background = pill(colorAccent, 4)
                })
                addView(valueText)
                addView(TextView(this@DealerPanelActivity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(textMuted)
                })
            }
            return card to valueText
        }

        val (dealersCard, dealersVal) = statCard("Dealers", navyMid)
        val (pendingCard, pendingVal) = statCard("Pending", orange)
        val (balanceCard, balanceVal) = statCard("Total Balance", green)

        statDealersText = dealersVal
        statPendingText = pendingVal
        statBalanceText = balanceVal

        row.addView(dealersCard)
        row.addView(pendingCard)
        balanceCard.layoutParams = (balanceCard.layoutParams as LinearLayout.LayoutParams).also { it.marginEnd = 0 }
        row.addView(balanceCard)
        return row
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(textDark)
        setPadding(dp(16), dp(20), dp(16), dp(8))
    }

    // ===================== DEALERS (LIVE) =====================

    private fun observeDealers() {
        dealersListener = db.collection("dealers")
            .orderBy("name")
            .addSnapshotListener { query, error ->
                if (error != null || query == null) {
                    Toast.makeText(this, "Dealer list load failed: ${error?.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                dealerList.removeAllViews()
                dealerCount = query.size()
                totalBalance = 0.0

                if (query.isEmpty) {
                    dealerList.addView(emptyState("No dealers yet — tap + to add one"))
                }

                query.documents.forEach { document ->
                    val wateen = document.getDouble("wateenBalance") ?: 0.0
                    val ebone = document.getDouble("eboneBalance") ?: 0.0
                    val zong = document.getDouble("zongBalance") ?: 0.0
                    totalBalance += wateen + ebone + zong
                    dealerList.addView(dealerCard(document.id, document))
                }

                statDealersText.text = dealerCount.toString()
                statBalanceText.text = "Rs. ${"%.0f".format(totalBalance)}"
            }
    }

    private fun dealerCard(dealerId: String, document: com.google.firebase.firestore.DocumentSnapshot): LinearLayout {
        val wateen = document.getDouble("wateenBalance") ?: 0.0
        val ebone = document.getDouble("eboneBalance") ?: 0.0
        val zong = document.getDouble("zongBalance") ?: 0.0
        val isActive = (document.getString("status") ?: "ACTIVE") == "ACTIVE"

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(cardWhite)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), borderLight)
            }
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
            isClickable = true
            setOnClickListener { showDealerDetails(dealerId) }
        }

        val topRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).also { it.marginEnd = dp(10) }
            background = pill(if (isActive) green else Color.parseColor("#D92D20"), 10)
        })
        topRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPanelActivity).apply {
                text = document.getString("name") ?: ""
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textDark)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Code ${document.getString("dealerCode") ?: ""}  •  ${document.getString("mobile") ?: ""}"
                textSize = 12f
                setTextColor(textMuted)
            })
        })
        card.addView(topRow)

        val badgeRow = LinearLayout(this).apply {
            setPadding(0, dp(10), 0, 0)
        }
        badgeRow.addView(networkBalanceBadge("Wateen", wateen, navyMid))
        badgeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        badgeRow.addView(networkBalanceBadge("Ebone", ebone, orange))
        badgeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        badgeRow.addView(networkBalanceBadge("Zong", zong, purple))
        card.addView(badgeRow)

        return card
    }

    private fun networkBalanceBadge(label: String, amount: Double, accent: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = outlinedPill(Color.parseColor("#F9FAFB"), borderLight, 10)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPanelActivity).apply {
                text = label
                textSize = 10f
                setTextColor(accent)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Rs. ${"%.0f".format(amount)}"
                textSize = 13f
                setTextColor(textDark)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
        }

    // ===================== PENDING PAYMENTS (LIVE) =====================

    private fun observePending() {
        pendingListener = db.collection("dealerTransactions")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { query, error ->
                if (error != null || query == null) return@addSnapshotListener

                pendingList.removeAllViews()
                statPendingText.text = query.size().toString()

                if (query.isEmpty) {
                    pendingList.addView(emptyState("No pending dealer payments"))
                    return@addSnapshotListener
                }

                query.documents.forEach { document ->
                    val panel = document.getString("panel") ?: "?"
                    val amount = document.getDouble("amount") ?: 0.0
                    val tid = document.getString("bankTransactionId") ?: ""

                    val row = LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        background = outlinedPill(amberBg, Color.parseColor("#FEC84B"), 12)
                        layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
                    }
                    row.addView(TextView(this).apply {
                        text = "⏳"
                        textSize = 16f
                        setPadding(0, 0, dp(10), 0)
                    })
                    row.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        addView(TextView(this@DealerPanelActivity).apply {
                            text = "$panel  —  Rs. ${"%.0f".format(amount)}"
                            textSize = 13f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(amberText)
                        })
                        addView(TextView(this@DealerPanelActivity).apply {
                            text = "TID: $tid"
                            textSize = 11f
                            setTextColor(textMuted)
                        })
                    })
                    pendingList.addView(row)
                }
            }
    }

    private fun emptyState(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 13f
        setTextColor(textMuted)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(16), dp(8), dp(16))
    }

    // ===================== ACTIONS =====================

    private fun scanNow() {
        Toast.makeText(this, "Scanning SMS inbox for dealer payments…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val matched = DealerPaymentSmsScanner.scanAllPending(this@DealerPanelActivity)
            runOnUiThread {
                Toast.makeText(
                    this@DealerPanelActivity,
                    if (matched > 0) "Matched $matched payment(s)" else "No new matches found",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showAddDealerDialog() {
        val nameInput = EditText(this).apply { hint = "Dealer Name" }
        val mobileInput = EditText(this).apply { hint = "Mobile" }
        val code = (100000..999999).random().toString()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(nameInput)
            addView(mobileInput)
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Code: $code"
                textSize = 22f
                setPadding(0, dp(16), 0, 0)
            })
        }

        AlertDialog.Builder(this)
            .setTitle("Add Dealer")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim()
                val mobile = mobileInput.text.toString().trim()
                if (name.isEmpty() || mobile.isEmpty()) {
                    Toast.makeText(this, "Dealer name and mobile are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val dealerId = db.collection("dealers").document().id
                val dealer = mapOf(
                    "dealerId" to dealerId,
                    "name" to name,
                    "mobile" to mobile,
                    "dealerCode" to code,
                    "deviceId" to "",
                    "status" to "ACTIVE",
                    "wateenBalance" to 0.0,
                    "eboneBalance" to 0.0,
                    "zongBalance" to 0.0,
                    "paymentAccounts" to paymentAccountNames.associateWith { true },
                    "createdAt" to System.currentTimeMillis()
                )

                db.collection("dealers").document(dealerId).set(dealer)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Dealer created. Code: $code", Toast.LENGTH_LONG).show()
                    }
                    .addOnFailureListener { error ->
                        Toast.makeText(this, "Dealer creation failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDealerDetails(dealerId: String) {
        db.collection("dealers").document(dealerId).get()
            .addOnSuccessListener { document ->
                if (!document.exists()) return@addOnSuccessListener

                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), 0, dp(20), 0)
                }

                val activeSwitch = Switch(this).apply {
                    text = "Dealer Active"
                    isChecked = (document.getString("status") ?: "ACTIVE") == "ACTIVE"
                }
                box.addView(activeSwitch)

                val savedAccounts = document.get("paymentAccounts") as? Map<*, *> ?: emptyMap<Any, Any>()
                val switches = mutableMapOf<String, Switch>()
                paymentAccountNames.forEach { accountName ->
                    val accountSwitch = Switch(this).apply {
                        text = accountName
                        isChecked = savedAccounts[accountName] != false
                    }
                    switches[accountName] = accountSwitch
                    box.addView(accountSwitch)
                }

                AlertDialog.Builder(this)
                    .setTitle("Dealer Settings")
                    .setView(box)
                    .setPositiveButton("Save") { _, _ ->
                        val accountStates = switches.mapValues { it.value.isChecked }
                        db.collection("dealers").document(dealerId)
                            .update(
                                mapOf(
                                    "status" to if (activeSwitch.isChecked) "ACTIVE" else "DISABLED",
                                    "paymentAccounts" to accountStates
                                )
                            )
                            .addOnFailureListener { error ->
                                Toast.makeText(this, "Save failed: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
            .addOnFailureListener { error ->
                Toast.makeText(this, "Dealer details load failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
    }
}