package com.example.eboneadminpanel

import android.content.Intent
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

    // NEW: remembers which EditText (inside whichever dialog is open) to
    // fill once WebViewLoginActivity's FETCH_DEALER_ID flow returns a
    // numeric Wateen dealer ID.
    private var pendingFetchIdInput: EditText? = null
    private val REQUEST_FETCH_DEALER_ID = 7001

    // NEW: live franchise balances (franchiseSettings/balances doc) —
    // separate from any individual dealer's balance. Updated whenever
    // the 💰 Check Balance action successfully reads the Ebone panel.
    private var franchiseBalancesListener: ListenerRegistration? = null
    private lateinit var franchiseBalancesRow: LinearLayout

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
        // NEW: root is no longer wrapped in an outer ScrollView. The
        // header, stats, franchise balances, and pending-payments
        // section stay fixed on screen; only the Dealers list below
        // scrolls (in its own ScrollView with weight=1, filling
        // whatever vertical space is left).
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgLight)
        }

        root.addView(buildHeader())
        root.addView(buildStatsRow())

        // NEW: wrapped in a horizontal scroll — with Zong now able to
        // show both "Zong (Okara)" and "Zong (Renala)" chips alongside
        // Ebone/Wateen, that's up to 4 chips, which can look cramped on
        // narrow screens if forced into a fixed-width row. Scrolls
        // instead of squeezing.
        val franchiseBalancesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        franchiseBalancesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        franchiseBalancesScroll.addView(franchiseBalancesRow)
        root.addView(franchiseBalancesScroll)

        root.addView(sectionTitle("Pending Payments"))
        pendingList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(pendingList)

        // NEW: Dealers section is its own independently-scrollable area
        // — layout_height=0 + weight=1 makes it fill all remaining
        // vertical space, and scrolling inside it never moves the
        // header/stats/pending section above.
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

        // Title row — back arrow + title/subtitle ONLY. Icon buttons
        // moved to their own scrollable row below, so this text never
        // gets squeezed/wrapped no matter how many action icons exist.
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
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Balances, payments & verification"
                textSize = 12f
                setTextColor(Color.parseColor("#B8C2E0"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        })
        header.addView(titleRow)

        // NEW: icon action row — horizontally scrollable so it never
        // forces the title to wrap and never overflows off-screen, no
        // matter how many action icons this screen ends up with.
        val iconScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setPadding(0, dp(14), 0, 0)
        }
        // NEW: wrapper centers the icon row horizontally when it fits
        // within the screen width (fillViewport stretches this wrapper
        // to at least the visible width); if the icons ever overflow a
        // narrow screen, the HorizontalScrollView still scrolls normally.
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
        // NEW: debug-only — opens the Ebone panel logged in, with the tap
        // inspector active, and NO auto-activate customer ID (so it just
        // sits on /clients for manual tapping/exploration). Temporary
        // testing aid for confirming franchise-balance-transfer selectors
        // before any real automation is wired up — safe to remove once
        // selectors are confirmed.
        iconRow.addView(iconButton("🔍") { inspectEbonePanel() })
        iconRow.addView(spacerH(8))
        // Send Dealer Payment — permanent screen (Dealer → Company →
        // Amount → Send). Replaces the earlier temporary test dialog.
        iconRow.addView(iconButton("💸") { openSendPaymentScreen() })
        iconRow.addView(spacerH(8))
        // NEW: reads the Ebone franchise balance dropdown and saves it +
        // fires a low-balance notification if under the configured
        // threshold.
        iconRow.addView(iconButton("💰") { checkEboneBalance() })
        iconRow.addView(spacerH(8))
        // NEW: opens the Payment Log / transaction history screen.
        iconRow.addView(iconButton("📄") { startActivity(Intent(this, DealerPaymentLogActivity::class.java)) })
        iconRow.addView(spacerH(8))
        // NEW: low-balance notification threshold settings, per panel.
        iconRow.addView(iconButton("⚙") { showThresholdSettingsDialog() })
        iconRow.addView(spacerH(8))
        // NEW: per-zone service settings — which ISPs (Ebone/Wateen/
        // Zong) are actually enabled for each franchise/zone (e.g.
        // Renala only runs Zong right now while Okara runs all three).
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
        // NEW: zone tag chip — small, muted, reuses the existing palette
        // (Okara = navy, Renala = purple) so Renala dealers are visually
        // distinguishable at a glance without looking like a mismatched
        // color splash. Defaults missing zone to "Okara".
        val zone = document.getString("zone")?.ifBlank { null } ?: "Okara"
        val zoneColor = if (zone == "Okara") navyMid else purple
        topRow.addView(TextView(this).apply {
            text = zone
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
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
        // NEW: also fetch VERIFIED (SMS-matched today, waiting to be
        // sent on the panel) and NEEDS_REVIEW (matched an OLDER SMS —
        // held for manual confirmation, not auto-credited) alongside
        // PENDING (waiting for SMS match) ones.
        pendingListener = db.collection("dealerTransactions")
            .whereIn("status", listOf("PENDING", "VERIFIED", "NEEDS_REVIEW"))
            .addSnapshotListener { query, error ->
                if (error != null || query == null) return@addSnapshotListener

                pendingList.removeAllViews()

                // NEW: a VERIFIED row that's already been sent
                // (transferStatus == "TRANSFERRED") is done — don't
                // count or show it here.
                val relevantDocs = query.documents.filter { doc ->
                    val status = doc.getString("status")
                    val transferStatus = doc.getString("transferStatus") ?: ""
                    status == "PENDING" || status == "NEEDS_REVIEW" ||
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

                    when (status) {
                        "PENDING" -> pendingList.addView(
                            swipeToDeleteWrapper(
                                waitingRow(document.id, panel, amount, tid)
                            ) {
                                deletePendingPayment(document.id)
                            }
                        )

                        "NEEDS_REVIEW" -> pendingList.addView(
                            swipeToDeleteWrapper(
                                needsReviewRow(
                                    document.id,
                                    panel,
                                    amount,
                                    tid,
                                    document
                                )
                            ) {
                                deletePendingPayment(document.id)
                            }
                        )

                        "VERIFIED" -> {
                            /*
                             * LIVE/TODAY verified payments must never expose
                             * the old manual Send Now chain. The verifier is
                             * already responsible for starting the existing
                             * DEALER_TOPUP automation.
                             */
                            when (transferStatus) {
                                "AUTO_SENDING" -> pendingList.addView(
                                    swipeToDeleteWrapper(
                                        autoSendingRow(
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

    /** NEW: permanently deletes a dealerTransactions record from
     * Firestore — used by the swipe-to-delete action on the Pending
     * Payments list. This is a real, permanent delete (not a status
     * change) — there is no undo once confirmed. */
    private fun deletePendingPayment(transactionId: String) {
        db.collection("dealerTransactions").document(transactionId).delete()
            .addOnSuccessListener {
                Toast.makeText(this, "Payment record deleted", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * NEW: wraps a row in a swipe-left-to-reveal-delete container —
     * swipe the row left to reveal a red 🗑 trash icon on the right,
     * tap it to permanently delete (with confirmation).
     *
     * Uses a proper onInterceptTouchEvent-based FrameLayout instead of a
     * plain OnTouchListener on the row: the parent only steals the
     * touch away from children once it detects a real horizontal drag
     * (past a small threshold), so a short tap on a child button (like
     * "Send Now") still registers as a normal click, but a swipe
     * gesture starting ANYWHERE on the row — including on top of that
     * button — works smoothly, no dead zones.
     */
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

            override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        startTranslation = contentRow.translationX
                        dragging = false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (!dragging &&
                            kotlin.math.abs(dx) > touchSlopPx &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy)
                        ) {
                            // A real horizontal drag — take over from
                            // whichever child (e.g. the Send Now button)
                            // would otherwise have received this touch.
                            dragging = true
                        }
                        if (dragging) return true
                    }
                }
                return false
            }

            override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (dragging) {
                            val dx = ev.x - downX
                            contentRow.translationX = (startTranslation + dx).coerceIn(-revealedPx, 0f)
                            return true
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            val shouldReveal = contentRow.translationX < -revealedPx / 2
                            contentRow.animate()
                                .translationX(if (shouldReveal) -revealedPx else 0f)
                                .setDuration(150)
                                .start()
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

    /**
     * NEW: a payment whose matching SMS was found, but that SMS is from
     * an EARLIER day than today (only found because the match window
     * was widened past "today"). Per policy, this is held — NOT
     * auto-credited — and shown distinctly so the admin can look at the
     * matched SMS text/date and manually confirm before any balance
     * moves.
     */
    private fun needsReviewRow(
        transactionId: String,
        panel: String,
        amount: Double,
        tid: String,
        document: com.google.firebase.firestore.DocumentSnapshot
    ): LinearLayout {
        val matchedSmsDate = document.getLong("matchedSmsDate")
        val matchedSmsBody = document.getString("matchedSmsBody") ?: ""
        val dateText = matchedSmsDate?.let {
            java.text.SimpleDateFormat("dd MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it))
        } ?: "unknown date"

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(Color.parseColor("#F3E8FF"), purple, 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = "$panel  —  Rs. ${"%.0f".format(amount)}"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
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

    /** NEW: admin confirms an older-SMS match is genuinely valid — only
     * then does it get credited via the same shared
     * DealerPaymentVerifier used by the automatic (today-only) paths. */
    private fun confirmNeedsReview(transactionId: String, document: com.google.firebase.firestore.DocumentSnapshot) {
        AlertDialog.Builder(this)
            .setTitle("Confirm this payment?")
            .setMessage("This will credit the dealer's balance based on the matched SMS shown. Only confirm if you've checked it's genuine.")
            .setPositiveButton("Confirm & Credit") { _, _ ->
                val data = document.data ?: return@setPositiveButton
                DealerPaymentVerifier.verifyAndCredit(this, transactionId, data) { success ->
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (success) "Confirmed and credited" else "Failed to confirm — try again",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun waitingRow(transactionId: String, panel: String, amount: Double, tid: String): LinearLayout {
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
                text = "TID: $tid  •  Waiting for SMS match"
                textSize = 11f
                setTextColor(textMuted)
            })
        })
        // NEW: per-payment retry button — the fallback for when live SMS
        // auto-detection (DealerPaymentSmsReceiver) didn't fire in time
        // (network delay, phone busy on a call, etc). Automatic
        // detection stays the priority path; this just gives a direct,
        // one-tap way to re-check THIS specific payment without having
        // to scroll up to a separate global button. Re-scans the inbox
        // (same underlying check as the global ⟳) — if this
        // transaction's SMS is found now, it auto-verifies and
        // auto-launches the panel transfer immediately, same as the
        // live/automatic path.
        row.addView(TextView(this).apply {
            text = "🔄"
            textSize = 18f
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setOnClickListener { retryMatchForOnePayment(transactionId) }
        })
        return row
    }

    /** NEW: re-scans the SMS inbox, scoped conceptually to this one
     * payment (the underlying scanner checks all PENDING transactions
     * in one pass, which is safe and fast — only THIS one will actually
     * change if its SMS is found). Shows a small per-tap status instead
     * of the generic global scan message. */
    private fun retryMatchForOnePayment(transactionId: String) {
        Toast.makeText(this, "Checking for this payment's SMS…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            DealerPaymentSmsScanner.scanAllPending(this@DealerPanelActivity)
            db.collection("dealerTransactions").document(transactionId).get()
                .addOnSuccessListener { doc ->
                    val stillPending = doc.getString("status") == "PENDING"
                    runOnUiThread {
                        Toast.makeText(
                            this@DealerPanelActivity,
                            if (stillPending) "Still no matching SMS found for this payment." else "Matched! Processing…",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }
    }

    private fun autoSendingRow(
        panel: String,
        amount: Double,
        tid: String
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(
                Color.parseColor("#E8F8EE"),
                green,
                12
            )
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.bottomMargin = dp(8)
            }
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
                text = "$panel  —  Rs. ${"%.0f".format(amount)}"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
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

    private fun autoFailedRow(
        panel: String,
        amount: Double,
        tid: String,
        error: String
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(
                Color.parseColor("#FEECEC"),
                Color.parseColor("#D92D20"),
                12
            )
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.bottomMargin = dp(8)
            }
        }

        row.addView(TextView(this).apply {
            text = "⚠ $panel  —  Rs. ${"%.0f".format(amount)}"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#B42318"))
        })

        row.addView(TextView(this).apply {
            text = if (error.isBlank()) {
                "TID: $tid  •  Automatic transfer failed"
            } else {
                "TID: $tid  •  $error"
            }
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, dp(3), 0, 0)
        })

        return row
    }

    /** NEW: a payment already verified by SMS matching but not yet sent
     * on the Ebone panel. Shows a "Send Now" button that opens
     * SendDealerPaymentActivity pre-filled and locked to this dealer/
     * panel/amount, so the admin only has to confirm — the actual
     * amount/dealer can't be silently changed from what SMS verified. */
    private fun sendNowRow(transactionId: String, dealerId: String, panel: String, amount: Double, tid: String): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outlinedPill(Color.parseColor("#E8F8EE"), green, 12)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DealerPanelActivity).apply {
                text = "$panel  —  Rs. ${"%.0f".format(amount)}"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#18794E"))
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = "TID: $tid  •  Verified — ready to send"
                textSize = 11f
                setTextColor(textMuted)
            })
        })
        row.addView(Button(this).apply {
            text = "Send Now"
            setBackgroundColor(green)
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener {
                val intent = Intent(this@DealerPanelActivity, SendDealerPaymentActivity::class.java).apply {
                    putExtra("prefill_dealer_id", dealerId)
                    putExtra("prefill_panel", panel)
                    putExtra("prefill_amount", amount.toString())
                    putExtra("prefill_transaction_id", transactionId)
                }
                startActivity(intent)
            }
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

    /** NEW: debug-only — logs into the chosen panel with the tap
     * inspector active, no auto-activate customer ID, so you can freely
     * tap anywhere (dealer list, dropdown, balance display, submit
     * button etc.) and see each element's tag/id/name/class in a Toast
     * + Logcat. Purely for confirming selectors — does not touch any
     * transaction or balance. */
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

    /** NEW: launches WebViewLoginActivity's FETCH_DEALER_ID flow for
     * Wateen — searches the dealer list by [dealerName] and, on
     * success, auto-fills [targetInput] with the numeric ID found. Lets
     * the admin add a dealer's Wateen ID by name instead of having to
     * find/type the internal numeric ID themselves. */
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
        }
    }

    /** NEW: opens the permanent Send Dealer Payment screen (blank —
     * admin picks dealer/company/amount themselves). */
    private fun openSendPaymentScreen() {
        startActivity(Intent(this, SendDealerPaymentActivity::class.java))
    }

    /** NEW: asks which panel to check, then triggers
     * WebViewLoginActivity's CHECK_BALANCE flow — logs in, reads the
     * franchise balance, saves it to franchiseSettings/balances, and
     * fires a low-balance notification if under the configured
     * threshold. Zong asks which zone too, since it's the only service
     * currently running in more than one franchise (Okara + Renala). */
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
        val intent = Intent(this, WebViewLoginActivity::class.java).apply {
            putExtra("selected_isp", isp)
            putExtra("manual_action", "CHECK_BALANCE")
            putExtra("target_zone", zone)
        }
        startActivity(intent)
    }

    /** NEW: live display of franchise-level balances (separate from any
     * individual dealer's balance) — reads franchiseSettings/balances,
     * updated whenever 💰 Check Balance successfully runs. Ebone/Wateen
     * are Okara-only for now; Zong shows both Okara's and (once
     * checked at least once) Renala's balance as separate chips, since
     * Zong is the only service currently running in more than one zone. */
    private fun observeFranchiseBalances() {
        franchiseBalancesListener = db.collection("franchiseSettings").document("balances")
            .addSnapshotListener { snapshot, _ ->
                franchiseBalancesRow.removeAllViews()
                val ebone = snapshot?.getDouble("eboneBalance")
                val wateen = snapshot?.getDouble("wateenBalance")
                val zongOkara = snapshot?.getDouble("zongBalance")
                // NEW: zone-suffixed field written by
                // FranchiseBalanceManager for any zone other than Okara.
                val zongRenala = snapshot?.getDouble("zongBalance_Renala")

                franchiseBalancesRow.addView(franchiseBalanceChip("Ebone", ebone, orange))
                franchiseBalancesRow.addView(franchiseBalanceChip("Wateen", wateen, navyMid))
                franchiseBalancesRow.addView(franchiseBalanceChip("Zong (Okara)", zongOkara, purple))
                // NEW: only show the Renala chip once it's actually been
                // checked at least once — no point cluttering the row
                // with an empty chip before Admin has ever pressed 💰
                // for Renala.
                if (zongRenala != null) {
                    franchiseBalancesRow.addView(franchiseBalanceChip("Zong (Renala)", zongRenala, Color.parseColor("#7C3AED")))
                }
            }
    }

    private fun franchiseBalanceChip(label: String, value: Double?, accent: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = outlinedPill(Color.parseColor("#F9FAFB"), borderLight, 10)
            // NEW: fixed width instead of weight=1 — inside a
            // HorizontalScrollView there's no bounded parent width for
            // weight to distribute against, so weight-based chips would
            // collapse. A fixed width also keeps every chip readable
            // even when 4 are shown at once (Ebone/Wateen/Zong-Okara/
            // Zong-Renala).
            layoutParams = LinearLayout.LayoutParams(dp(120), -2).also { it.marginEnd = dp(8) }
            addView(TextView(this@DealerPanelActivity).apply {
                text = "$label Franchise"
                textSize = 10f
                setTextColor(accent)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@DealerPanelActivity).apply {
                text = if (value == null) "Not checked yet" else "Rs. ${"%,.0f".format(kotlin.math.abs(value))}"
                textSize = 13f
                setTextColor(textDark)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
        }

    /** NEW: admin-configurable low-balance notification thresholds, one
     * per panel. Saved to franchiseSettings/balances
     * (eboneLowBalanceThreshold / wateenLowBalanceThreshold /
     * zongLowBalanceThreshold), read by FranchiseBalanceManager
     * whenever a balance is checked. A threshold of 0 or blank means
     * "no alert configured" for that panel. */
    private fun showThresholdSettingsDialog() {
        val docRef = db.collection("franchiseSettings").document("balances")
        docRef.get().addOnSuccessListener { snapshot ->
            val eboneInput = EditText(this).apply {
                hint = "Ebone low-balance alert (Rs.)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText((snapshot.getDouble("eboneLowBalanceThreshold") ?: 0.0).let { if (it > 0) it.toLong().toString() else "" })
            }
            val wateenInput = EditText(this).apply {
                hint = "Wateen low-balance alert (Rs.)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText((snapshot.getDouble("wateenLowBalanceThreshold") ?: 0.0).let { if (it > 0) it.toLong().toString() else "" })
            }
            val zongInput = EditText(this).apply {
                hint = "Zong low-balance alert (Rs.)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText((snapshot.getDouble("zongLowBalanceThreshold") ?: 0.0).let { if (it > 0) it.toLong().toString() else "" })
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(TextView(this@DealerPanelActivity).apply {
                    text = "You'll get a notification whenever a panel's balance drops below its number here."
                    textSize = 12f
                    setTextColor(textMuted)
                    setPadding(0, 0, 0, dp(12))
                })
                addView(eboneInput)
                addView(wateenInput)
                addView(zongInput)
            }

            AlertDialog.Builder(this)
                .setTitle("Low Balance Alerts")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val updates = mapOf(
                        "eboneLowBalanceThreshold" to (eboneInput.text.toString().trim().toDoubleOrNull() ?: 0.0),
                        "wateenLowBalanceThreshold" to (wateenInput.text.toString().trim().toDoubleOrNull() ?: 0.0),
                        "zongLowBalanceThreshold" to (zongInput.text.toString().trim().toDoubleOrNull() ?: 0.0)
                    )
                    docRef.set(updates, com.google.firebase.firestore.SetOptions.merge())
                        .addOnSuccessListener { Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }
                        .addOnFailureListener { e -> Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** NEW: per-zone (franchise) service toggles — e.g. Okara runs
     * Ebone+Wateen+Zong, Renala only runs Zong right now. Writes to
     * Firestore "zoneServiceConfig/{zone}" (eboneEnabled/
     * wateenEnabled/zongEnabled booleans), read by the Dealer Panel app
     * to grey out unavailable services for a dealer's zone. Missing
     * doc/fields default to enabled=true — so nothing changes for a
     * zone until Admin explicitly disables something there. */
    private fun showZoneServiceSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Which zone?")
            .setItems(zoneNames.toTypedArray()) { _, which ->
                showZoneServiceTogglesFor(zoneNames[which])
            }
            .show()
    }

    private fun showZoneServiceTogglesFor(zone: String) {
        val docRef = db.collection("zoneServiceConfig").document(zone)
        docRef.get().addOnSuccessListener { snapshot ->
            val eboneSwitch = Switch(this).apply {
                text = "Ebone"
                isChecked = snapshot.getBoolean("eboneEnabled") ?: true
            }
            val wateenSwitch = Switch(this).apply {
                text = "Wateen"
                isChecked = snapshot.getBoolean("wateenEnabled") ?: true
            }
            val zongSwitch = Switch(this).apply {
                text = "Zong"
                isChecked = snapshot.getBoolean("zongEnabled") ?: true
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(TextView(this@DealerPanelActivity).apply {
                    text = "Turn off any service not running yet for $zone. Dealers in this zone won't be able to select a disabled service."
                    textSize = 12f
                    setTextColor(textMuted)
                    setPadding(0, 0, 0, dp(12))
                })
                addView(eboneSwitch)
                addView(wateenSwitch)
                addView(zongSwitch)
            }

            AlertDialog.Builder(this)
                .setTitle("$zone — Enabled Services")
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    docRef.set(
                        mapOf(
                            "eboneEnabled" to eboneSwitch.isChecked,
                            "wateenEnabled" to wateenSwitch.isChecked,
                            "zongEnabled" to zongSwitch.isChecked
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
                        .addOnSuccessListener { Toast.makeText(this, "$zone services updated", Toast.LENGTH_SHORT).show() }
                        .addOnFailureListener { e -> Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Could not load $zone settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

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

    // NEW: known zones/franchises. Each has its own Franchise login per
    // ISP (set in ISP Panel Settings) and its own enabled-services list
    // (set via Zone Service Settings). Add more names here as new
    // franchises come online.
    private val zoneNames = listOf("Okara", "Renala")

    private fun showAddDealerDialog() {
        val nameInput = EditText(this).apply { hint = "Dealer Name" }
        val mobileInput = EditText(this).apply { hint = "Mobile" }
        val eboneIdInput = EditText(this).apply { hint = "Ebone Panel Dealer ID (e.g. Akmal)" }
        val wateenIdInput = EditText(this).apply { hint = "Wateen Panel Dealer ID" }
        val zongIdInput = EditText(this).apply { hint = "Zong Panel Dealer ID" }
        val code = (100000..999999).random().toString()

        // NEW: Zone tag — determines which franchise login (Okara's
        // Abbas046 vs Renala's RN-Abbas046) and which enabled-services
        // list this dealer follows. Defaults to "Okara" so existing
        // dealers/behaviour are unaffected until explicitly changed.
        val zoneSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@DealerPanelActivity, android.R.layout.simple_spinner_dropdown_item, zoneNames)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(nameInput)
            addView(mobileInput)
            addView(TextView(this@DealerPanelActivity).apply {
                text = "Zone (Franchise Tag)"
                textSize = 12f
                setTextColor(textMuted)
                setPadding(0, dp(14), 0, dp(4))
            })
            addView(zoneSpinner)
            addView(TextView(this@DealerPanelActivity).apply {
                text = "ISP Panel Dealer IDs (leave blank if not known yet)"
                textSize = 12f
                setTextColor(textMuted)
                setPadding(0, dp(14), 0, dp(4))
            })
            addView(eboneIdInput)
            // NEW: "Fetch" button — searches Wateen's dealer list by the
            // name typed above and auto-fills the numeric ID, so the
            // admin never has to manually find/type Wateen's internal
            // numeric dealer ID (e.g. 2146).
            addView(LinearLayout(this@DealerPanelActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(wateenIdInput.also {
                    it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(Button(this@DealerPanelActivity).apply {
                    text = "🔎 Fetch"
                    textSize = 12f
                    setOnClickListener {
                        val name = nameInput.text.toString().trim()
                        if (name.isEmpty()) {
                            Toast.makeText(this@DealerPanelActivity, "Type the dealer's name first", Toast.LENGTH_SHORT).show()
                        } else {
                            fetchWateenDealerId(wateenIdInput, name)
                        }
                    }
                })
            })
            addView(zongIdInput)
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
                val zone = zoneNames[zoneSpinner.selectedItemPosition]

                val dealerId = db.collection("dealers").document().id
                val dealer = mapOf(
                    "dealerId" to dealerId,
                    "name" to name,
                    "mobile" to mobile,
                    "dealerCode" to code,
                    "deviceId" to "",
                    "status" to "ACTIVE",
                    "zone" to zone,
                    "wateenBalance" to 0.0,
                    "eboneBalance" to 0.0,
                    "zongBalance" to 0.0,
                    "eboneDealerId" to eboneIdInput.text.toString().trim(),
                    "wateenDealerId" to wateenIdInput.text.toString().trim(),
                    "zongDealerId" to zongIdInput.text.toString().trim(),
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

                // NEW: Zone (Franchise Tag) — lets Admin assign/change
                // which zone this dealer belongs to (e.g. tag an
                // existing dealer as "Renala" instead of the default
                // "Okara"). Determines which franchise login and
                // enabled-services list this dealer follows.
                box.addView(TextView(this@DealerPanelActivity).apply {
                    text = "Zone (Franchise Tag)"
                    textSize = 12f
                    setTextColor(textMuted)
                    setPadding(0, dp(10), 0, dp(4))
                })
                val currentZone = document.getString("zone")?.ifBlank { null } ?: "Okara"
                val zoneSpinner = Spinner(this).apply {
                    adapter = ArrayAdapter(this@DealerPanelActivity, android.R.layout.simple_spinner_dropdown_item, zoneNames)
                    setSelection(zoneNames.indexOf(currentZone).coerceAtLeast(0))
                }
                box.addView(zoneSpinner)

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
                        val selectedZone = zoneNames[zoneSpinner.selectedItemPosition]
                        db.collection("dealers").document(dealerId)
                            .update(
                                mapOf(
                                    "status" to if (activeSwitch.isChecked) "ACTIVE" else "DISABLED",
                                    "zone" to selectedZone,
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