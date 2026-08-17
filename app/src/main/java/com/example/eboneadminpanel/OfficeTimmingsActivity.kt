package com.example.eboneadminpanel

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase

class OfficeTimmingsActivity : AppCompatActivity() {

    private val db = FirebaseDatabase.getInstance()

    // Current values
    private var startHour = 10; private var startMinute = 0
    private var endHour = 22;   private var endMinute = 0
    private var gracePeriod = 15
    private var preShiftWindow = 60
    private var postShiftWindow = 60
    private var complaintRadius = 500

    // UI
    private lateinit var tvStartTime: TextView
    private lateinit var tvEndTime: TextView
    private lateinit var tvGrace: TextView
    private lateinit var tvPreShift: TextView
    private lateinit var tvPostShift: TextView
    private lateinit var tvComplaintRadius: TextView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        loadSettings()
    }

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
            setPadding(px(16,dp), px(48,dp), px(16,dp), px(16,dp))
        }
        ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            background = null; setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(px(28,dp), px(28,dp))
            setOnClickListener { finish() }
        }.also { header.addView(it) }
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = px(12,dp) }
        }
        titleBlock.addView(tv("Office Timings", 16f, Color.WHITE, bold = true))
        titleBlock.addView(tv("Attendance rules & windows", 12f, Color.parseColor("#B8C6DE")))
        header.addView(titleBlock)

        // Requests Button — top right
        Button(this).apply {
            text = "Requests"
            textSize = 10f; setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(Color.parseColor("#1A3F7A"))
            }
            setPadding(px(10,dp), px(6,dp), px(10,dp), px(6,dp))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { showLeaveRequestsHistory() }
        }.also { header.addView(it) }
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12,dp), px(12,dp), px(12,dp), px(12,dp))
        }
        scroll.addView(content)

        // ── Office Hours Card ──
        val officeCard = card(dp)
        officeCard.addView(sectionHeader("Office Hours", dp))
        officeCard.addView(divider(dp))
        officeCard.addView(settingRow("Start Time", "", dp) { tvStartTime = it; it.setOnClickListener { showTimePicker("start") } })
        officeCard.addView(divider(dp))
        officeCard.addView(settingRow("End Time", "", dp) { tvEndTime = it; it.setOnClickListener { showTimePicker("end") } })
        content.addView(officeCard)

        // ── Windows Card ──
        val windowCard = card(dp)
        windowCard.addView(sectionHeader("Check-In / Check-Out Windows", dp))
        windowCard.addView(tv("Customise how early or late attendance is allowed", 11f, Color.parseColor("#9E9E9E")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(8,dp) }
        })
        windowCard.addView(divider(dp))

        // Grace Period
        windowCard.addView(settingRowWithInfo(
            "Grace Period",
            "Arrival ke baad kitne minute tak ON TIME count ho",
            dp) { tvGrace = it; it.setOnClickListener { showMinutePicker("grace", "Grace Period (min)", gracePeriod) } })
        windowCard.addView(divider(dp))

        // Pre-Shift Window
        windowCard.addView(settingRowWithInfo(
            "Pre-Shift Window",
            "Office start se pehle kitne minute tak check-in allow ho",
            dp) { tvPreShift = it; it.setOnClickListener { showMinutePicker("preshift", "Pre-Shift Window (min)", preShiftWindow) } })
        windowCard.addView(divider(dp))

        // Post-Shift Window
        windowCard.addView(settingRowWithInfo(
            "Post-Shift Window",
            "Office end ke baad kitne minute tak check-out allow ho",
            dp) { tvPostShift = it; it.setOnClickListener { showMinutePicker("postshift", "Post-Shift Window (min)", postShiftWindow) } })
        content.addView(windowCard)

        // ── Complaint Radius Card ──
        val radiusCard = card(dp)
        radiusCard.addView(sectionHeader("Complaint Geofence", dp))
        radiusCard.addView(tv("Complaint address se kitne meter radius mein employee ho to field attendance allow ho", 11f, Color.parseColor("#9E9E9E")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(8,dp) }
        })
        radiusCard.addView(divider(dp))
        radiusCard.addView(settingRowWithInfo(
            "Complaint Radius",
            "Field attendance ka circle size (meters)",
            dp) { tvComplaintRadius = it; it.setOnClickListener { showMeterPicker() } })
        content.addView(radiusCard)

        // ── Info Card ──
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp; setColor(Color.parseColor("#E3F2FD")) }
            setPadding(px(14,dp), px(12,dp), px(14,dp), px(12,dp))
            layoutParams = llp().also { it.bottomMargin = px(10,dp) }
        }
        infoCard.addView(tv("Example — Agar Settings:", 12f, Color.parseColor("#1565C0"), bold = true).also { it.layoutParams = llp().also { m -> m.bottomMargin = px(6,dp) } })
        infoCard.addView(tv("Office Start: 10:00 AM", 12f, Color.parseColor("#333333")))
        infoCard.addView(tv("Pre-Shift: 60 min  →  9:00 AM Se Allow", 12f, Color.parseColor("#2E7D32")))
        infoCard.addView(tv("Grace Period: 15 min  →  10:15 AM Tak ON TIME", 12f, Color.parseColor("#E65100")))
        infoCard.addView(tv("10:16 AM Baad  →  LATE (Deduction Start)", 12f, Color.parseColor("#C62828")).also { it.layoutParams = llp().also { m -> m.topMargin = px(2,dp) } })
        content.addView(infoCard)

        // Save Button
        Button(this).apply {
            text = "Save Settings"
            textSize = 15f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp; setColor(Color.parseColor("#1565C0")) }
            layoutParams = llp().also { it.bottomMargin = px(8,dp) }
            setOnClickListener { saveSettings() }
        }.also { content.addView(it) }

        tvStatus = tv("", 13f, Color.parseColor("#2E7D32")).also {
            it.gravity = Gravity.CENTER
            it.layoutParams = llp()
        }
        content.addView(tvStatus)

        root.addView(scroll)
        return root
    }

    // ── Helpers for rows ──

    private fun settingRow(label: String, hint: String, dp: Float, init: (TextView) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(12,dp), 0, px(12,dp))
        }
        row.addView(tv(label, 14f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val valueView = tv("—", 14f, Color.parseColor("#1565C0"), bold = true)
        init(valueView)
        row.addView(valueView)
        row.addView(tv("  ▾", 14f, Color.parseColor("#AAAAAA")))
        return row
    }

    private fun settingRowWithInfo(label: String, info: String, dp: Float, init: (TextView) -> Unit): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = false }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(12,dp), 0, px(4,dp))
        }
        val labelBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        labelBlock.addView(tv(label, 14f, Color.parseColor("#111111"), bold = true))
        labelBlock.addView(tv(info, 10f, Color.parseColor("#9E9E9E")).also { it.layoutParams = llp().also { m -> m.topMargin = px(2,dp) } })
        val valueView = tv("—", 14f, Color.parseColor("#1565C0"), bold = true)
        init(valueView)
        row.addView(labelBlock)
        row.addView(valueView)
        row.addView(tv("  ▾", 14f, Color.parseColor("#AAAAAA")))
        row.isClickable = true; row.isFocusable = true
        outer.addView(row)
        return outer
    }

    private fun sectionHeader(title: String, dp: Float): TextView {
        return tv(title, 14f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4,dp) }
        }
    }

    // ── Pickers ──

    private fun showTimePicker(type: String) {
        val dp = resources.displayMetrics.density
        val isStart = type == "start"
        val currentHour = if (isStart) startHour else endHour
        val currentMin = if (isStart) startMinute else endMinute

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(px(24,dp), px(16,dp), px(24,dp), px(8,dp))
        }
        val hourPicker = NumberPicker(this).apply {
            minValue = 0; maxValue = 23
            displayedValues = (0..23).map { String.format("%02d", it) }.toTypedArray()
            value = currentHour
        }
        val sep = tv(" : ", 18f, Color.parseColor("#333333"), bold = true)
        val minPicker = NumberPicker(this).apply {
            minValue = 0; maxValue = 59
            displayedValues = (0..59).map { String.format("%02d", it) }.toTypedArray()
            value = currentMin
        }
        layout.addView(hourPicker); layout.addView(sep); layout.addView(minPicker)

        AlertDialog.Builder(this)
            .setTitle(if (isStart) "Set Start Time" else "Set End Time")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                if (isStart) { startHour = hourPicker.value; startMinute = minPicker.value; tvStartTime.text = formatTime(startHour, startMinute) }
                else { endHour = hourPicker.value; endMinute = minPicker.value; tvEndTime.text = formatTime(endHour, endMinute) }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showMinutePicker(type: String, title: String, currentVal: Int) {
        val dp = resources.displayMetrics.density
        val et = EditText(this).apply {
            hint = "Minutes"; inputType = InputType.TYPE_CLASS_NUMBER
            setText("$currentVal")
            setPadding(px(16,dp), px(12,dp), px(16,dp), px(12,dp))
        }

        // Quick select buttons
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24,dp), px(8,dp), px(24,dp), px(8,dp)) }
        layout.addView(et)
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = llp().also { it.topMargin = px(8,dp) }
        }
        listOf(15, 30, 45, 60, 90).forEach { mins ->
            Button(this).apply {
                text = "${mins}m"; textSize = 11f
                setTextColor(Color.parseColor("#1565C0"))
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 8f*(resources.displayMetrics.density); setColor(Color.parseColor("#E3F2FD")) }
                setPadding(px(8,dp), px(4,dp), px(8,dp), px(4,dp))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = px(4,dp) }
                setOnClickListener { et.setText("$mins") }
            }.also { quickRow.addView(it) }
        }
        layout.addView(quickRow)

        AlertDialog.Builder(this).setTitle(title).setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val value = et.text.toString().toIntOrNull() ?: return@setPositiveButton
                when (type) {
                    "grace" -> { gracePeriod = value; tvGrace.text = "${value} min" }
                    "preshift" -> { preShiftWindow = value; tvPreShift.text = "${value} min" }
                    "postshift" -> { postShiftWindow = value; tvPostShift.text = "${value} min" }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showMeterPicker() {
        val dp = resources.displayMetrics.density
        val et = EditText(this).apply {
            hint = "Meters"; inputType = InputType.TYPE_CLASS_NUMBER
            setText("$complaintRadius")
            setPadding(px(16,dp), px(12,dp), px(16,dp), px(12,dp))
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24,dp), px(8,dp), px(24,dp), px(8,dp)) }
        layout.addView(et)
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = llp().also { it.topMargin = px(8,dp) }
        }
        listOf(100, 200, 500, 1000).forEach { m ->
            Button(this).apply {
                text = "${m}m"; textSize = 11f
                setTextColor(Color.parseColor("#1565C0"))
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 8f*(resources.displayMetrics.density); setColor(Color.parseColor("#E3F2FD")) }
                setPadding(px(8,dp), px(4,dp), px(8,dp), px(4,dp))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = px(4,dp) }
                setOnClickListener { et.setText("$m") }
            }.also { quickRow.addView(it) }
        }
        layout.addView(quickRow)

        AlertDialog.Builder(this).setTitle("Complaint Radius (meters)").setView(layout)
            .setPositiveButton("OK") { _, _ ->
                complaintRadius = et.text.toString().toIntOrNull() ?: return@setPositiveButton
                tvComplaintRadius.text = "$complaintRadius m"
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Firebase ──

    private fun loadSettings() {
        db.getReference("officeSettings").get().addOnSuccessListener { snap ->
            startHour = (snap.child("startHour").value as? Long)?.toInt() ?: 10
            startMinute = (snap.child("startMinute").value as? Long)?.toInt() ?: 0
            endHour = (snap.child("endHour").value as? Long)?.toInt() ?: 22
            endMinute = (snap.child("endMinute").value as? Long)?.toInt() ?: 0
            gracePeriod = (snap.child("gracePeriodMinutes").value as? Long)?.toInt() ?: 15
            preShiftWindow = (snap.child("preShiftMinutes").value as? Long)?.toInt() ?: 60
            postShiftWindow = (snap.child("postShiftMinutes").value as? Long)?.toInt() ?: 60
            complaintRadius = (snap.child("complaintRadiusMeters").value as? Long)?.toInt() ?: 500

            tvStartTime.text = formatTime(startHour, startMinute)
            tvEndTime.text = formatTime(endHour, endMinute)
            tvGrace.text = "$gracePeriod min"
            tvPreShift.text = "$preShiftWindow min"
            tvPostShift.text = "$postShiftWindow min"
            tvComplaintRadius.text = "$complaintRadius m"
        }
    }

    private fun saveSettings() {
        val data = mapOf(
            "startHour" to startHour,
            "startMinute" to startMinute,
            "endHour" to endHour,
            "endMinute" to endMinute,
            "gracePeriodMinutes" to gracePeriod,
            "preShiftMinutes" to preShiftWindow,
            "postShiftMinutes" to postShiftWindow,
            "complaintRadiusMeters" to complaintRadius
        )
        db.getReference("officeSettings").setValue(data)
            .addOnSuccessListener {
                tvStatus.text = "Settings saved! ✅"
                tvStatus.setTextColor(Color.parseColor("#2E7D32"))
            }
            .addOnFailureListener { e ->
                tvStatus.text = "Failed: ${e.message}"
                tvStatus.setTextColor(Color.parseColor("#C62828"))
            }
    }

    // ── Helpers ──

    private fun showLeaveRequestsHistory() {
        val dp = resources.displayMetrics.density
        val scroll = android.widget.ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(8,dp), px(8,dp), px(8,dp), px(8,dp))
        }
        scroll.addView(container)

        val loadTv = android.widget.TextView(this).apply {
            text = "Loading..."; textSize = 13f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, px(20,dp), 0, px(20,dp))
        }
        container.addView(loadTv)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this@OfficeTimmingsActivity)
            .setTitle("Leave Requests")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()

        val leaveRef = db.getReference("earlyLeaveRequests")
        leaveRef.addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snap: com.google.firebase.database.DataSnapshot) {
                container.removeAllViews()
                var count = 0

                for (req in snap.children) {
                    val reqKey = req.key ?: continue
                    val name = req.child("employeeName").value?.toString() ?: continue
                    val reason = req.child("reason").value?.toString() ?: ""
                    val status = req.child("status").value?.toString() ?: ""
                    val reqAt = (req.child("requestedAt").value as? Long) ?: 0L
                    val timeStr = if (reqAt > 0) java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(reqAt)) else ""

                    count++
                    val card = android.widget.LinearLayout(this@OfficeTimmingsActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            cornerRadius = 8f * dp
                            setColor(Color.WHITE)
                            setStroke(px(1,dp), Color.parseColor("#EEEEEE"))
                        }
                        setPadding(px(12,dp), px(10,dp), px(12,dp), px(10,dp))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.bottomMargin = px(8,dp) }
                    }

                    // Name + Status row
                    val topRow = android.widget.LinearLayout(this@OfficeTimmingsActivity).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    topRow.addView(android.widget.TextView(this@OfficeTimmingsActivity).apply {
                        text = name; textSize = 14f
                        setTextColor(Color.parseColor("#111111"))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })

                    val badgeColor = when(status) {
                        "APPROVED" -> Color.parseColor("#2E7D32")
                        "REJECTED" -> Color.parseColor("#C62828")
                        else -> Color.parseColor("#E65100")
                    }
                    val badgeBg = when(status) {
                        "APPROVED" -> Color.parseColor("#E8F5E9")
                        "REJECTED" -> Color.parseColor("#FFEBEE")
                        else -> Color.parseColor("#FFF3E0")
                    }
                    topRow.addView(android.widget.TextView(this@OfficeTimmingsActivity).apply {
                        text = status; textSize = 10f
                        setTextColor(badgeColor)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            cornerRadius = 10f * dp; setColor(badgeBg)
                        }
                        setPadding(px(8,dp), px(3,dp), px(8,dp), px(3,dp))
                    })
                    card.addView(topRow)

                    card.addView(android.widget.TextView(this@OfficeTimmingsActivity).apply {
                        text = reason; textSize = 12f
                        setTextColor(Color.parseColor("#555555"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.topMargin = px(4,dp) }
                    })
                    if (timeStr.isNotEmpty()) card.addView(android.widget.TextView(this@OfficeTimmingsActivity).apply {
                        text = timeStr; textSize = 11f
                        setTextColor(Color.parseColor("#9E9E9E"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.topMargin = px(2,dp) }
                    })

                    // Approve/Reject buttons for PENDING
                    if (status == "PENDING") {
                        val btnRow = android.widget.LinearLayout(this@OfficeTimmingsActivity).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.END
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { it.topMargin = px(6,dp) }
                        }
                        android.widget.Button(this@OfficeTimmingsActivity).apply {
                            text = "Approve"; textSize = 11f
                            setTextColor(Color.parseColor("#2E7D32"))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = 8f * dp; setColor(Color.parseColor("#E8F5E9"))
                            }
                            setPadding(px(12,dp), px(4,dp), px(12,dp), px(4,dp))
                            minHeight = 0; minimumHeight = 0
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { it.marginEnd = px(8,dp) }
                            setOnClickListener {
                                db.getReference("earlyLeaveRequests").child(reqKey)
                                    .updateChildren(mapOf("status" to "APPROVED", "respondedAt" to System.currentTimeMillis()))
                                    .addOnSuccessListener { dialog.dismiss(); showLeaveRequestsHistory() }
                            }
                        }.also { btnRow.addView(it) }
                        android.widget.Button(this@OfficeTimmingsActivity).apply {
                            text = "Reject"; textSize = 11f
                            setTextColor(Color.parseColor("#C62828"))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = 8f * dp; setColor(Color.parseColor("#FFEBEE"))
                            }
                            setPadding(px(12,dp), px(4,dp), px(12,dp), px(4,dp))
                            minHeight = 0; minimumHeight = 0
                            setOnClickListener {
                                db.getReference("earlyLeaveRequests").child(reqKey)
                                    .updateChildren(mapOf("status" to "REJECTED", "respondedAt" to System.currentTimeMillis()))
                                    .addOnSuccessListener { dialog.dismiss(); showLeaveRequestsHistory() }
                            }
                        }.also { btnRow.addView(it) }
                        card.addView(btnRow)
                    }
                    container.addView(card)
                }

                if (count == 0) {
                    container.addView(android.widget.TextView(this@OfficeTimmingsActivity).apply {
                        text = "No leave requests found."
                        textSize = 13f; setTextColor(Color.parseColor("#9E9E9E"))
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, px(20,dp), 0, px(20,dp))
                    })
                }
            }
            override fun onCancelled(e: com.google.firebase.database.DatabaseError) {}
        })
    }

    private fun formatTime(hour: Int, min: Int): String {
        val h = if (hour > 12) hour - 12 else if (hour == 0) 12 else hour
        val ampm = if (hour >= 12) "PM" else "AM"
        return "$h:${String.format("%02d", min)} $ampm"
    }

    private fun px(v: Int, dp: Float) = (v * dp).toInt()
    private fun llp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }
    private fun card(dp: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp; setColor(Color.WHITE) }
        setPadding(px(14,dp), px(12,dp), px(14,dp), px(12,dp))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = px(10,dp) }
    }
    private fun divider(dp: Float) = View(this).apply {
        setBackgroundColor(Color.parseColor("#F0F0F0"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }
}