package com.example.eboneadminpanel

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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
    private var activeStatFilter: String? = null // NEW: tracks which top card is selected

    private val dealerNameCache = HashMap<String, String>()

    private var pendingFetchIdInput: EditText? = null
    private val REQUEST_FETCH_DEALER_ID = 7001

    private var franchiseBalancesListener: ListenerRegistration? = null
    private lateinit var franchiseBalancesRow: LinearLayout

    private val REQUEST_CHECK_BALANCE = 7002
    private val autoUpdateQueue = mutableListOf<Pair<String, String>>()
    private var isAutoUpdating = false
    private var isBackgroundMode = false
    private var dealerSearchQuery = ""

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
        
        // Initial load of background mode preference
        val prefs = getSharedPreferences("dealer_panel_prefs", MODE_PRIVATE)
        isBackgroundMode = prefs.getBoolean("is_background_mode", false)
        
        setContentView(buildScreen())
        observeDealers()
        observePending()
        observeFranchiseBalances()
    }

    override fun onDestroy() {
        dealersListener?.remove()
        pendingListener?.remove()
        franchiseBalancesListener?.remove()
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

    private fun buildScreen(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgLight)
        }

        root.addView(buildHeader())
        root.addView(buildStatsRow())

        val franchiseBalancesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        franchiseBalancesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        franchiseBalancesScroll.addView(franchiseBalancesRow)
        root.addView(franchiseBalancesScroll)

        // NEW: Search Dealers bar
        val searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchInput = EditText(this).apply {
            hint = "Search dealers by name..."
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = outlinedPill(Color.WHITE, borderLight, 10)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    dealerSearchQuery = s?.toString()?.lowercase() ?: ""
                    observeDealers() // Trigger re-filter
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchContainer.addView(searchInput)
        root.addView(searchContainer)

        root.addView(sectionTitle("Pending Payments"))
        pendingList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(pendingList)

        val dealersScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val dealersContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        dealersContent.addView(sectionTitle("Dealers"))
        dealerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        dealersContent.addView(dealerList)
        dealersScroll.addView(dealersContent)
        root.addView(dealersScroll)

        return root
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(navyDark, navyMid)
            )
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }

        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
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
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Dealer Panel"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Balances, payments & verification"
                textSize = 12f
                setTextColor(Color.parseColor("#B8C2E0"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        })
        header.addView(titleRow)

        val iconScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setPadding(0, dp(14), 0, 0)
        }
        val iconRowWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        iconRow.addView(iconButton("⟳") { scanNow() })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("🔍") { inspectEbonePanel() })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("💸") { openSendPaymentScreen() })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("💰") { checkEboneBalance() })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("📄") { startActivity(Intent(this, DealerPaymentLogActivity::class.java)) })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("⚙") { showThresholdSettingsDialog() })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("🏷") { showZoneServiceSettingsDialog() })
        iconRow.addView(spacerH(8))
        iconRow.addView(iconButton("+") { showAddDealerDialog() })
        iconRowWrapper.addView(iconRow)
        iconScroll.addView(iconRowWrapper)
        header.addView(iconScroll)

        return header
    }

    private fun spacerH(widthDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
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

    private var statsContainer: LinearLayout? = null
    
    private fun buildStatsRow(): LinearLayout {
        val container = LinearLayout(this).apply {
            id = View.generateViewId()
            statsContainer = this
            orientation = LinearLayout.VERTICAL
        }
        container.addView(buildStatsCards())
        return container
    }

    private fun buildStatsCards(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(4))
        }

        fun statCard(label: String, colorAccent: Int, type: String): Pair<LinearLayout, TextView> {
            val valueText = TextView(this).apply {
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textDark)
            }
            
            val isSelected = activeStatFilter == type
            
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = outlinedPill(
                    if (isSelected) Color.parseColor("#EFF6FF") else cardWhite, 
                    if (isSelected) Color.parseColor("#2563EB") else borderLight, 
                    14
                )
                elevation = if(isSelected) dp(4).toFloat() else dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also {
                    it.marginEnd = dp(8)
                }
                
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = AppCompatResources.getDrawable(context, outValue.resourceId)
                isClickable = true

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
                
                setOnClickListener {
                    activeStatFilter = if (activeStatFilter == type) null else type
                    refreshStatsRow()
                }
            }
            return card to valueText
        }

        val (dealersCard, dealersVal) = statCard("Dealers", navyMid, "DEALERS")
        val (pendingCard, pendingVal) = statCard("Pending", orange, "PENDING")
        val (balanceCard, balanceVal) = statCard("Total Balance", green, "TOTAL")

        statDealersText = dealersVal
        statPendingText = pendingVal
        statBalanceText = balanceVal

        row.addView(dealersCard)
        row.addView(pendingCard)
        balanceCard.layoutParams = (balanceCard.layoutParams as LinearLayout.LayoutParams).also { it.marginEnd = 0 }
        row.addView(balanceCard)
        return row
    }

    private fun refreshStatsRow() {
        statsContainer?.let {
            it.removeAllViews()
            it.addView(buildStatsCards())
            // Also refresh lists based on selection
            observeDealers()
            observePending()
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
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

                var index = 1
                query.documents.forEach { document ->
                    val name = document.getString("name") ?: ""
                    if (dealerSearchQuery.isNotEmpty() && !name.lowercase().contains(dealerSearchQuery)) {
                        return@forEach
                    }

                    val wateen = document.getDouble("wateenBalance") ?: 0.0
                    val ebone = document.getDouble("eboneBalance") ?: 0.0
                    val zong = document.getDouble("zongBalance") ?: 0.0
                    totalBalance += wateen + ebone + zong
                    dealerNameCache[document.id] = document.getString("name") ?: document.id
                    dealerList.addView(dealerCard(document.id, document, index++))
                }

                statDealersText.text = dealerCount.toString()
                statBalanceText.text = "Rs. ${"%.0f".format(totalBalance)}"
            }
    }

    private fun dealerCard(dealerId: String, document: DocumentSnapshot, index: Int): LinearLayout {
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
                setTypeface(null, Typeface.BOLD)
                setTextColor(textDark)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Code ${document.getString("dealerCode") ?: ""}  •  ${document.getString("mobile") ?: ""}"
                textSize = 12f
                setTextColor(textMuted)
            })
        })
        val zone = document.getString("zone")?.ifBlank { null } ?: "Okara"
        val zoneColor = if (zone == "Okara") navyMid else purple
        topRow.addView(TextView(this).apply {
            text = zone
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(zoneColor)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = outlinedPill(Color.WHITE, zoneColor, 10)
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
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Rs. ${"%.0f".format(amount)}"
                textSize = 13f
                setTextColor(textDark)
                setTypeface(null, Typeface.BOLD)
            })
        }

    // ===================== PENDING PAYMENTS (LIVE) =====================

    private fun observePending() {
        pendingListener = db.collection("dealerTransactions")
            .whereIn("status", listOf("PENDING", "VERIFIED", "NEEDS_REVIEW", "REJECTED_DUPLICATE"))
            .addSnapshotListener { query, error ->
                if (error != null || query == null) return@addSnapshotListener

                pendingList.removeAllViews()

                val relevantDocs = query.documents.filter { doc ->
                    val status = doc.getString("status")
                    val transferStatus = doc.getString("transferStatus") ?: ""
                    status == "PENDING" || status == "NEEDS_REVIEW" || status == "REJECTED_DUPLICATE" ||
                            (status == "VERIFIED" && transferStatus != "TRANSFERRED")
                }

                statPendingText.text = relevantDocs.size.toString()

                if (relevantDocs.isEmpty()) {
                    pendingList.addView(emptyState("No pending dealer payments"))
                    return@addSnapshotListener
                }

                relevantDocs.forEach { document ->
                    val status = document.getString("status") ?: "PENDING"
                    val transferStatus = document.getString("transferStatus") ?: ""
                    val panel = document.getString("panel") ?: "?"
                    val amount = document.getDouble("amount") ?: 0.0
                    val tid = document.getString("bankTransactionId") ?: ""
                    val dealerId = document.getString("dealerId") ?: ""
                    val dealerName = dealerNameCache[dealerId] ?: "Dealer"

                    when (status) {
                        "PENDING" -> pendingList.addView(
                            swipeToDeleteWrapper(
                                waitingRow(document.id, dealerName, panel, amount, tid)
                            ) {
                                deletePendingPayment(document.id)
                            }
                        )

                        "NEEDS_REVIEW" -> pendingList.addView(
                            swipeToDeleteWrapper(
                                needsReviewRow(
                                    document.id,
                                    dealerName,
                                    panel,
                                    amount,
                                    tid,
                                    document
                                )
                            ) {
                                deletePendingPayment(document.id)
                            }
                        )

                        "REJECTED_DUPLICATE" -> pendingList.addView(
                            swipeToDeleteWrapper(
                                duplicateRejectedRow(
                                    dealerName = dealerName,
                                    panel = panel,
                                    amount = amount,
                                    tid = tid,
                                    message = document.getString("duplicateMessage").orEmpty()
                                )
                            ) {
                                deletePendingPayment(document.id)
                            }
                        )

                        "VERIFIED" -> {
                            when (transferStatus) {
                                "AUTO_SENDING", "AUTO_CLAIMED" -> pendingList.addView(
                                    swipeToDeleteWrapper(
                                        autoSendingRow(
                                            dealerName,
                                            panel,
                                            amount,
                                            tid
                                        )
                                    ) {
                                        deletePendingPayment(document.id)
                                    }
                                )

                                "AUTO_FAILED" -> pendingList.addView(
                                    swipeToDeleteWrapper(
                                        autoFailedRow(
                                            dealerName = dealerName,
                                            panel = panel,
                                            amount = amount,
                                            tid = tid,
                                            error = document.getString("transferError")
                                                .orEmpty()
                                        )
                                    ) {
                                        deletePendingPayment(document.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
    }

    private fun deletePendingPayment(transactionId: String) {
        db.collection("dealerTransactions").document(transactionId).delete()
            .addOnSuccessListener {
                Toast.makeText(this, "Payment record deleted", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun swipeToDeleteWrapper(contentRow: LinearLayout, onDelete: () -> Unit): FrameLayout {
        val revealedPx = dp(72).toFloat()
        val deleteBg = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#D92D20"))
            layoutParams = FrameLayout.LayoutParams(dp(72), FrameLayout.LayoutParams.MATCH_PARENT).also {
                it.gravity = Gravity.END
            }
            isClickable = true
            addView(TextView(this@DealerPanelActivity).apply {
                text = "🗑"
                textSize = 20f
                setTextColor(Color.WHITE)
            })
        }
        contentRow.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val touchSlopPx = dp(10).toFloat()
        val frame = object : FrameLayout(this) {
            private var downX = 0f
            private var downY = 0f
            private var startTranslation = 0f
            private var dragging = false
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        startTranslation = contentRow.translationX
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (!dragging && abs(dx) > touchSlopPx && abs(dx) > abs(dy)) {
                            dragging = true
                        }
                        if (dragging) return true
                    }
                }
                return false
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        if (dragging) {
                            val dx = ev.x - downX
                            contentRow.translationX = (startTranslation + dx).coerceIn(-revealedPx, 0f)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            val shouldReveal = contentRow.translationX < -revealedPx / 2
                            contentRow.animate().translationX(if (shouldReveal) -revealedPx else 0f).setDuration(150).start()
                            dragging = false
                            return true
                        }
                    }
                }
                return false
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        frame.addView(deleteBg)
        frame.addView(contentRow)
        deleteBg.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete permanently?")
                .setMessage("This will permanently remove this payment record. This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> onDelete() }
                .setNegativeButton("Cancel") { _, _ ->
                    contentRow.animate().translationX(0f).setDuration(150).start()
                }
                .show()
        }
        return frame
    }

    private fun needsReviewRow(
        transactionId: String,
        dealerName: String,
        panel: String,
        amount: Double,
        tid: String,
        document: DocumentSnapshot
    ): LinearLayout {
        val matchedSmsDate = document.getLong("matchedSmsDate")
        val matchedSmsBody = document.getString("matchedSmsBody") ?: ""
        val dateText = matchedSmsDate?.let {
            SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(it))
        } ?: "unknown date"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(Color.parseColor("#F3E8FF"), purple, 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = "$dealerName  •  $panel  —  Rs. ${"%.0f".format(amount)}"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(purple)
        })
        row.addView(TextView(this).apply {
            text = "TID: $tid  •  Matched SMS is from $dateText, not today"
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, dp(2), 0, dp(8))
        })
        if (matchedSmsBody.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = matchedSmsBody
                textSize = 11f
                setTextColor(textDark)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = outlinedPill(Color.WHITE, borderLight, 8)
                setPadding(0, 0, 0, dp(8))
            })
        }
        row.addView(Button(this).apply {
            text = "Review & Verify"
            setBackgroundColor(purple)
            setTextColor(Color.WHITE)
            textSize = 12f
            setOnClickListener { confirmNeedsReview(transactionId, document) }
        })
        return row
    }

    private fun confirmNeedsReview(transactionId: String, document: DocumentSnapshot) {
        AlertDialog.Builder(this)
            .setTitle("Confirm this payment?")
            .setMessage("This will credit the dealer's balance based on the matched SMS shown. Only confirm if you've checked it's genuine.")
            .setPositiveButton("Confirm & Credit") { _, _ ->
                val data = document.data ?: return@setPositiveButton
                DealerPaymentVerifier.verifyAndCredit(this, transactionId, data, "manual_confirm_needs_review") { success ->
                    runOnUiThread {
                        Toast.makeText(this, if (success) "Confirmed and credited" else "Failed to confirm — try again", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun waitingRow(transactionId: String, dealerName: String, panel: String, amount: Double, tid: String): LinearLayout {
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
                text = "$dealerName  •  $panel  —  Rs. ${"%.0f".format(amount)}"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(amberText)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "TID: $tid  •  Waiting for SMS match"
                textSize = 11f
                setTextColor(textMuted)
            })
        })
        row.addView(TextView(this).apply {
            text = "🔄"
            textSize = 18f
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setOnClickListener { retryMatchForOnePayment(transactionId) }
        })
        return row
    }

    private fun retryMatchForOnePayment(transactionId: String) {
        Toast.makeText(this, "Checking for this payment's SMS…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            DealerPaymentSmsScanner.scanAllPending(this@DealerPanelActivity, "manual_retry_button")
            db.collection("dealerTransactions").document(transactionId).get()
                .addOnSuccessListener { doc ->
                    val stillPending = doc.getString("status") == "PENDING"
                    runOnUiThread {
                        Toast.makeText(this@DealerPanelActivity, if (stillPending) "Still no matching SMS found for this payment." else "Matched! Processing…", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private fun autoSendingRow(dealerName: String, panel: String, amount: Double, tid: String): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(Color.parseColor("#E8F8EE"), green, 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = "✓"
            textSize = 18f
            setTextColor(green)
            setPadding(0, 0, dp(10), 0)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPanelActivity).apply {
                text = "$dealerName  •  $panel  —  Rs. ${"%.0f".format(amount)}"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#18794E"))
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "TID: $tid  •  Verified — automatic transfer in progress"
                textSize = 11f
                setTextColor(textMuted)
            })
        })
        return row
    }

    private fun autoFailedRow(dealerName: String, panel: String, amount: Double, tid: String, error: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(Color.parseColor("#FEECEC"), Color.parseColor("#D92D20"), 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = "⚠ $dealerName  •  $panel  —  Rs. ${"%.0f".format(amount)}"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#B42318"))
        })
        row.addView(TextView(this).apply {
            text = if (error.isBlank()) "TID: $tid  •  Automatic transfer failed" else "TID: $tid  •  $error"
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, dp(3), 0, 0)
        })
        return row
    }

    private fun duplicateRejectedRow(dealerName: String, panel: String, amount: Double, tid: String, message: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(Color.parseColor("#FFF7E6"), Color.parseColor("#B54708"), 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = "⛔ $dealerName  •  $panel  —  Rs. ${"%.0f".format(amount)}"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#B54708"))
        })
        row.addView(TextView(this).apply {
            text = if (message.isBlank()) "TID: $tid  •  Rejected — this TID was already used elsewhere" else "TID: $tid  •  $message"
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, dp(3), 0, 0)
        })
        return row
    }

    private fun emptyState(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 13f
        setTextColor(textMuted)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(16), dp(8), dp(16))
    }

    // ===================== ACTIONS =====================

    private fun inspectEbonePanel() {
        AlertDialog.Builder(this)
            .setTitle("Inspect which panel?")
            .setItems(arrayOf("Ebone", "Wateen")) { _, which ->
                val isp = if (which == 0) "EBONE" else "WATEEN"
                val intent = Intent(this, WebViewLoginActivity::class.java).apply {
                    putExtra("selected_isp", isp)
                    putExtra("debug_tap_inspector", true)
                }
                startActivity(intent)
            }
            .show()
    }

    private fun fetchWateenDealerId(targetInput: EditText, dealerName: String) {
        pendingFetchIdInput = targetInput
        val intent = Intent(this, WebViewLoginActivity::class.java).apply {
            putExtra("selected_isp", "WATEEN")
            putExtra("manual_action", "FETCH_DEALER_ID")
            putExtra("dealer_search_name", dealerName)
        }
        startActivityForResult(intent, REQUEST_FETCH_DEALER_ID)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FETCH_DEALER_ID && resultCode == RESULT_OK) {
            val fetchedId = data?.getStringExtra("fetched_dealer_id")
            if (!fetchedId.isNullOrBlank()) {
                pendingFetchIdInput?.setText(fetchedId)
                Toast.makeText(this, "Found Wateen ID: $fetchedId", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not find that dealer on Wateen", Toast.LENGTH_LONG).show()
            }
            pendingFetchIdInput = null
        } else if (requestCode == REQUEST_CHECK_BALANCE) {
            if (isAutoUpdating) {
                Handler(Looper.getMainLooper()).postDelayed({ processNextAutoUpdate() }, 1200)
            }
        }
    }

    private fun openSendPaymentScreen() {
        startActivity(Intent(this, SendDealerPaymentActivity::class.java))
    }

    private fun checkEboneBalance() {
        AlertDialog.Builder(this)
            .setTitle("Check balance for which panel?")
            .setItems(arrayOf("Ebone", "Wateen", "Zong")) { _, which ->
                val isp = when (which) { 0 -> "EBONE"; 1 -> "WATEEN"; else -> "ZONG" }
                if (isp == "ZONG") {
                    AlertDialog.Builder(this)
                        .setTitle("Which zone?")
                        .setItems(zoneNames.toTypedArray()) { _, zoneWhich ->
                            launchCheckBalance(isp, zoneNames[zoneWhich])
                        }
                        .show()
                } else {
                    launchCheckBalance(isp, "Okara")
                }
            }
            .show()
    }

    private fun launchCheckBalance(isp: String, zone: String) {
        if (isBackgroundMode) {
            showStatusNotification("Checking $isp Balance...", "$zone پینل سے بیلنس پڑھا جا رہا ہے")
            BackgroundBalanceUpdater.checkBalance(this, isp, zone) { balance ->
                runOnUiThread {
                    if (balance != null) {
                        // SAVE balance to Firebase so chips update
                        FranchiseBalanceManager.updateBalance(isp, balance, zone) {
                            FranchiseBalanceManager.showUpdateNotification(this, isp, balance, zone)
                        }
                    } else {
                        showStatusNotification("$isp Update Failed", "بیلنس چیک کرنے میں دشواری پیش آئی")
                    }
                    if (isAutoUpdating) processNextAutoUpdate()
                }
            }
            return
        }

        val targetActivity = WebViewRouter.getTargetActivity(isp, zone)
        val intent = Intent(this, targetActivity).apply {
            putExtra("selected_isp", isp)
            putExtra("manual_action", "CHECK_BALANCE")
            putExtra("target_zone", zone)
        }
        if (isAutoUpdating) {
            startActivityForResult(intent, REQUEST_CHECK_BALANCE)
        } else {
            startActivity(intent)
        }
    }

    private fun showStatusNotification(title: String, message: String) {
        val channelId = "auto_update_status_v4" // Fresh channel to ensure all settings (sound, priority) are applied
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Update Status", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Panel updates and balance alerts"
                enableLights(true)
                lightColor = Color.GREEN
                enableVibration(true)
                setBypassDnd(true)
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 1000, 200, 1000))
            .setAutoCancel(true)
            .build()
            
        val notificationId = if (title.contains("Updated")) (System.currentTimeMillis().toInt()) else 7005
        manager.notify(notificationId, notification)
    }

    private fun startAutoUpdate() {
        if (isAutoUpdating) return
        autoUpdateQueue.clear()
        autoUpdateQueue.add("EBONE" to "Okara")
        autoUpdateQueue.add("WATEEN" to "Okara")
        autoUpdateQueue.add("ZONG" to "Okara")
        autoUpdateQueue.add("ZONG" to "Renala")
        isAutoUpdating = true
        observeFranchiseBalances()
        if (isBackgroundMode) showStatusNotification("Auto Update Started", "تمام پینلز کا بیلنس بیک گراؤنڈ میں اپ ڈیٹ کیا جا رہا ہے")
        Toast.makeText(this, "Starting Auto Update...", Toast.LENGTH_SHORT).show()
        processNextAutoUpdate()
    }

    private fun processNextAutoUpdate() {
        if (autoUpdateQueue.isEmpty()) {
            isAutoUpdating = false
            observeFranchiseBalances()
            if (isBackgroundMode) showStatusNotification("Auto Update Completed", "تمام پینلز کامیابی سے اپ ڈیٹ ہو گئے ہیں")
            Toast.makeText(this, "Auto Update completed!", Toast.LENGTH_LONG).show()
            return
        }
        val next = autoUpdateQueue.removeAt(0)
        launchCheckBalance(next.first, next.second)
    }

    private fun observeFranchiseBalances() {
        franchiseBalancesListener = db.collection("franchiseSettings").document("balances")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null || !snapshot.exists()) {
                    franchiseBalancesRow.removeAllViews()
                    franchiseBalancesRow.addView(emptyState("No balance data found"))
                    franchiseBalancesRow.addView(autoUpdateChip())
                    franchiseBalancesRow.addView(updateModeChip())
                    return@addSnapshotListener
                }
                
                franchiseBalancesRow.removeAllViews()
                val ebone = snapshot.getDouble("eboneBalance")
                val wateen = snapshot.getDouble("wateenBalance")
                val zongOkara = snapshot.getDouble("zongBalance")
                val zongRenala = snapshot.getDouble("zongBalance_Renala")
                
                val eboneTh = snapshot.getDouble("eboneLowBalanceThreshold") ?: 0.0
                val wateenTh = snapshot.getDouble("wateenLowBalanceThreshold") ?: 0.0
                val zongOkaraTh = snapshot.getDouble("zongLowBalanceThreshold") ?: 0.0
                val zongRenalaTh = snapshot.getDouble("zongLowBalanceThreshold_Renala") ?: 0.0

                franchiseBalancesRow.addView(franchiseBalanceChip("Ebone", ebone, orange, "EBONE", "Okara", eboneTh))
                franchiseBalancesRow.addView(franchiseBalanceChip("Wateen", wateen, navyMid, "WATEEN", "Okara", wateenTh))
                franchiseBalancesRow.addView(franchiseBalanceChip("Zong", zongOkara, purple, "ZONG", "Okara", zongOkaraTh))
                if (zongRenala != null) {
                    franchiseBalancesRow.addView(franchiseBalanceChip("Zong", zongRenala, Color.parseColor("#7C3AED"), "ZONG", "Renala", zongRenalaTh))
                }
                franchiseBalancesRow.addView(autoUpdateChip())
                franchiseBalancesRow.addView(updateModeChip())
            }
    }

    private fun franchiseBalanceChip(label: String, value: Double?, accent: Int, isp: String = "", zone: String = "Okara", threshold: Double? = 0.0): LinearLayout {
        // Use absolute value for comparison because some panels show negative balance for credit
        val absValue = if (value != null) abs(value) else 0.0
        val safeThreshold = threshold ?: 0.0
        val isLow = value != null && safeThreshold > 0 && absValue < safeThreshold
        val bgColor = if (isLow) Color.parseColor("#FEE2E2") else Color.parseColor("#F9FAFB")
        val strokeColor = if (isLow) Color.parseColor("#EF4444") else borderLight
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = outlinedPill(bgColor, strokeColor, 10)
            layoutParams = LinearLayout.LayoutParams(dp(125), -2).also { it.marginEnd = dp(8) }
            
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = AppCompatResources.getDrawable(context, outValue.resourceId)
            isClickable = true
            isFocusable = true
            
            addView(TextView(this@DealerPanelActivity).apply {
                text = "$label\n${if(zone == "Okara") "Franchise" else zone}"
                textSize = 10f
                setTextColor(if (isLow) Color.parseColor("#B91C1C") else accent)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                val displayValue = if (value == null) "Not checked yet" else "Rs. ${"%,.0f".format(
                    abs(value)
                )}"
                text = displayValue
                textSize = 13f
                setTextColor(if (isLow) Color.parseColor("#B91C1C") else textDark)
                setTypeface(null, Typeface.BOLD)
            })
            setOnClickListener {
                launchCheckBalance(isp, zone)
                val status = if(isLow) "LOW" else "OK"
                Toast.makeText(context, "$label $zone: Rs. ${absValue.toInt()} (Limit: ${safeThreshold.toInt()}) [$status]", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun autoUpdateChip(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val bgColor = if (isAutoUpdating) Color.parseColor("#F5F3FF") else Color.parseColor("#EEF2FF")
            val borderColor = if (isAutoUpdating) Color.parseColor("#7C3AED") else Color.parseColor("#4F46E5")
            val textColor = if (isAutoUpdating) Color.parseColor("#7C3AED") else Color.parseColor("#4F46E5")
            background = outlinedPill(bgColor, borderColor, 10)
            layoutParams = LinearLayout.LayoutParams(dp(125), -2).also { it.marginEnd = dp(8) }
            addView(TextView(this@DealerPanelActivity).apply {
                text = if (isAutoUpdating) "⌛" else "⚡"
                textSize = 18f
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = if (isAutoUpdating) "Updating..." else "Auto Update"
                textSize = 10f
                setTextColor(textColor)
                setTypeface(null, Typeface.BOLD)
            })
            setOnClickListener { startAutoUpdate() }
        }

    private fun updateModeChip(): LinearLayout {
        val prefs = getSharedPreferences("dealer_panel_prefs", MODE_PRIVATE)
        isBackgroundMode = prefs.getBoolean("is_background_mode", false)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val bgColor = if (isBackgroundMode) Color.parseColor("#FDF2F2") else Color.parseColor("#ECFDF5")
            val textColor = if (isBackgroundMode) Color.parseColor("#991B1B") else Color.parseColor("#065F46")
            background = outlinedPill(bgColor, textColor, 10)
            layoutParams = LinearLayout.LayoutParams(dp(125), -2).also { it.marginEnd = dp(8) }
            val icon = TextView(this@DealerPanelActivity).apply {
                text = if (isBackgroundMode) "👤" else "👁️"
                textSize = 18f
            }
            val label = TextView(this@DealerPanelActivity).apply {
                text = if (isBackgroundMode) "Background" else "WebView"
                textSize = 10f
                setTextColor(textColor)
                setTypeface(null, Typeface.BOLD)
            }
            addView(icon)
            addView(label)
            setOnClickListener {
                isBackgroundMode = !isBackgroundMode
                prefs.edit().putBoolean("is_background_mode", isBackgroundMode).apply()
                observeFranchiseBalances() 
                val msg = if (isBackgroundMode) "Auto Update اب بیک گراؤنڈ میں چلے گا" else "Auto Update اب ویب ویو میں چلے گا"
                Toast.makeText(this@DealerPanelActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showThresholdSettingsDialog() {
        val docRef = db.collection("franchiseSettings").document("balances")
        docRef.get().addOnSuccessListener { snapshot ->
            val eboneInput = EditText(this).apply {
                hint = "Ebone (Okara) alert (Rs.)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((snapshot.getDouble("eboneLowBalanceThreshold") ?: 0.0).let { if (it > 0) it.toLong().toString() else "" })
            }
            val wateenInput = EditText(this).apply {
                hint = "Wateen (Okara) alert (Rs.)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((snapshot.getDouble("wateenLowBalanceThreshold") ?: 0.0).let { if (it > 0) it.toLong().toString() else "" })
            }
            val zongOkaraInput = EditText(this).apply {
                hint = "Zong (Okara) alert (Rs.)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((snapshot.getDouble("zongLowBalanceThreshold") ?: 0.0).let { if (it > 0) it.toLong().toString() else "" })
            }
            val zongRenalaInput = EditText(this).apply {
                hint = "Zong (Renala) alert (Rs.)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((snapshot.getDouble("zongLowBalanceThreshold_Renala") ?: 0.0).let { if (it > 0) it.toLong().toString() else "" })
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(TextView(this@DealerPanelActivity).apply {
                    text = "Balance limits set karein. Jab balance is se kam hoga to chip red ho jayegi."
                    textSize = 12f
                    setTextColor(textMuted)
                    setPadding(0, 0, 0, dp(12))
                })
                addView(eboneInput)
                addView(wateenInput)
                addView(zongOkaraInput)
                addView(zongRenalaInput)
            }

            AlertDialog.Builder(this)
                .setTitle("Low Balance Alerts")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val updates = mapOf(
                        "eboneLowBalanceThreshold" to (eboneInput.text.toString().trim().toDoubleOrNull() ?: 0.0),
                        "wateenLowBalanceThreshold" to (wateenInput.text.toString().trim().toDoubleOrNull() ?: 0.0),
                        "zongLowBalanceThreshold" to (zongOkaraInput.text.toString().trim().toDoubleOrNull() ?: 0.0),
                        "zongLowBalanceThreshold_Renala" to (zongRenalaInput.text.toString().trim().toDoubleOrNull() ?: 0.0)
                    )
                    docRef.set(updates, SetOptions.merge())
                        .addOnSuccessListener { Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showZoneServiceSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Which zone?")
            .setItems(zoneNames.toTypedArray()) { _, which -> showZoneServiceTogglesFor(zoneNames[which]) }
            .show()
    }

    private fun showZoneServiceTogglesFor(zone: String) {
        val docRef = db.collection("zoneServiceConfig").document(zone)
        docRef.get().addOnSuccessListener { snapshot ->
            val eboneSwitch = Switch(this).apply { text = "Ebone"; isChecked = snapshot.getBoolean("eboneEnabled") ?: true }
            val wateenSwitch = Switch(this).apply { text = "Wateen"; isChecked = snapshot.getBoolean("wateenEnabled") ?: true }
            val zongSwitch = Switch(this).apply { text = "Zong"; isChecked = snapshot.getBoolean("zongEnabled") ?: true }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(TextView(this@DealerPanelActivity).apply {
                    text = "Turn off any service not running yet for $zone. Dealers in this zone won't be able to select a disabled service."
                    textSize = 12f
                    setTextColor(textMuted)
                    setPadding(0, 0, 0, dp(12))
                })
                addView(eboneSwitch); addView(wateenSwitch); addView(zongSwitch)
            }
            AlertDialog.Builder(this)
                .setTitle("$zone — Enabled Services")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    docRef.set(mapOf("eboneEnabled" to eboneSwitch.isChecked, "wateenEnabled" to wateenSwitch.isChecked, "zongEnabled" to zongSwitch.isChecked), SetOptions.merge())
                        .addOnSuccessListener { Toast.makeText(this, "$zone services updated", Toast.LENGTH_SHORT).show() }
                        .addOnFailureListener { e -> Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }.addOnFailureListener { e -> Toast.makeText(this, "Could not load $zone settings: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun scanNow() {
        Toast.makeText(this, "Scanning SMS inbox for dealer payments…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val matched = DealerPaymentSmsScanner.scanAllPending(this@DealerPanelActivity, "manual_scan_now_button")
            runOnUiThread { Toast.makeText(this@DealerPanelActivity, if (matched > 0) "Matched $matched payment(s)" else "No new matches found", Toast.LENGTH_LONG).show() }
        }
    }

    private val zoneNames = listOf("Okara", "Renala")

    private fun showAddDealerDialog() {
        val nameInput = EditText(this).apply { hint = "Dealer Name" }
        val mobileInput = EditText(this).apply { hint = "Mobile" }
        val eboneIdInput = EditText(this).apply { hint = "Ebone Panel Dealer ID (e.g. Akmal)" }
        val wateenIdInput = EditText(this).apply { hint = "Wateen Panel Dealer ID" }
        val zongIdInput = EditText(this).apply { hint = "Zong Panel Dealer ID" }
        val code = (100000..999999).random().toString()
        val zoneSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@DealerPanelActivity, android.R.layout.simple_spinner_dropdown_item, zoneNames) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(nameInput); addView(mobileInput)
            addView(TextView(this@DealerPanelActivity).apply { text = "Zone (Franchise Tag)"; textSize = 12f; setTextColor(textMuted); setPadding(0, dp(14), 0, dp(4)) })
            addView(zoneSpinner)
            addView(TextView(this@DealerPanelActivity).apply { text = "ISP Panel Dealer IDs (leave blank if not known yet)"; textSize = 12f; setTextColor(textMuted); setPadding(0, dp(14), 0, dp(4)) })
            addView(eboneIdInput)
            addView(LinearLayout(this@DealerPanelActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(wateenIdInput.also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                addView(Button(this@DealerPanelActivity).apply { text = "🔎 Fetch"; textSize = 12f; setOnClickListener {
                    val name = nameInput.text.toString().trim()
                    if (name.isEmpty()) Toast.makeText(this@DealerPanelActivity, "Type the dealer's name first", Toast.LENGTH_SHORT).show()
                    else fetchWateenDealerId(wateenIdInput, name)
                }})
            })
            addView(zongIdInput)
            addView(TextView(this@DealerPanelActivity).apply { text = "Code: $code"; textSize = 22f; setPadding(0, dp(16), 0, 0) })
        }
        AlertDialog.Builder(this).setTitle("Add Dealer").setView(box).setPositiveButton("Create") { _, _ ->
            val name = nameInput.text.toString().trim()
            val mobile = mobileInput.text.toString().trim()
            if (name.isEmpty() || mobile.isEmpty()) { Toast.makeText(this, "Dealer name and mobile are required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            val zone = zoneNames[zoneSpinner.selectedItemPosition]
            val dealerId = db.collection("dealers").document().id
            val dealer = mapOf("dealerId" to dealerId, "name" to name, "mobile" to mobile, "dealerCode" to code, "deviceId" to "", "status" to "ACTIVE", "zone" to zone, "wateenBalance" to 0.0, "eboneBalance" to 0.0, "zongBalance" to 0.0, "eboneDealerId" to eboneIdInput.text.toString().trim(), "wateenDealerId" to wateenIdInput.text.toString().trim(), "zongDealerId" to zongIdInput.text.toString().trim(), "paymentAccounts" to paymentAccountNames.associateWith { true }, "createdAt" to System.currentTimeMillis())
            db.collection("dealers").document(dealerId).set(dealer).addOnSuccessListener { Toast.makeText(this, "Dealer created. Code: $code", Toast.LENGTH_LONG).show() }.addOnFailureListener { error -> Toast.makeText(this, "Dealer creation failed: ${error.message}", Toast.LENGTH_LONG).show() }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showDealerDetails(dealerId: String) {
        db.collection("dealers").document(dealerId).get().addOnSuccessListener { document ->
            if (!document.exists()) return@addOnSuccessListener
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
            val activeSwitch = Switch(this).apply { text = "Dealer Active"; isChecked = (document.getString("status") ?: "ACTIVE") == "ACTIVE" }
            box.addView(activeSwitch)
            box.addView(TextView(this@DealerPanelActivity).apply { text = "Zone (Franchise Tag)"; textSize = 12f; setTextColor(textMuted); setPadding(0, dp(10), 0, dp(4)) })
            val currentZone = document.getString("zone")?.ifBlank { null } ?: "Okara"
            val zoneSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@DealerPanelActivity, R.layout.simple_spinner_dropdown_item, zoneNames); setSelection(zoneNames.indexOf(currentZone).coerceAtLeast(0)) }
            box.addView(zoneSpinner)
            val savedAccounts = document.get("paymentAccounts") as? Map<*, *> ?: emptyMap<Any, Any>()
            val switches = mutableMapOf<String, Switch>()
            paymentAccountNames.forEach { accountName -> val accountSwitch = Switch(this).apply { text = accountName; isChecked = savedAccounts[accountName] != false }; switches[accountName] = accountSwitch; box.addView(accountSwitch) }
            AlertDialog.Builder(this).setTitle("Dealer Settings").setView(box).setPositiveButton("Save") { _, _ ->
                val accountStates = switches.mapValues { it.value.isChecked }
                val selectedZone = zoneNames[zoneSpinner.selectedItemPosition]
                db.collection("dealers").document(dealerId).update(mapOf("status" to if (activeSwitch.isChecked) "ACTIVE" else "DISABLED", "zone" to selectedZone, "paymentAccounts" to accountStates)).addOnFailureListener { error -> Toast.makeText(this, "Save failed: ${error.message}", Toast.LENGTH_LONG).show() }
            }.setNegativeButton("Close", null).show()
        }.addOnFailureListener { error -> Toast.makeText(this, "Dealer details load failed: ${error.message}", Toast.LENGTH_LONG).show() }
    }
}
