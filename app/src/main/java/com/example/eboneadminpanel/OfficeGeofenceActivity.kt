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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class OfficeGeofenceActivity : AppCompatActivity() {

    private val db = FirebaseDatabase.getInstance()
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etRadius: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvCurrentSettings: TextView
    private lateinit var employeeContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        loadCurrentGeofence()
        loadEmployeesWithAttendance()
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
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = px(12,dp) }
            addView(tv("Office Geofence", 16f, Color.WHITE, bold = true))
            addView(tv("Set office location for attendance", 12f, Color.parseColor("#B8C6DE")))
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

        // Current settings card
        val currentCard = card(dp)
        currentCard.addView(tv("Current Geofence", 13f, Color.parseColor("#757575")).also {
            it.layoutParams = lp().also { m -> m.bottomMargin = px(8,dp) }
        })
        tvCurrentSettings = tv("Loading...", 14f, Color.parseColor("#111111"))
        currentCard.addView(tvCurrentSettings)
        content.addView(currentCard)

        // Info card
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp; setColor(Color.parseColor("#E3F2FD")) }
            setPadding(px(14,dp), px(12,dp), px(14,dp), px(12,dp))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = px(10,dp) }
            addView(tv("Google Maps → Long press on office location → Copy lat,lng", 12f, Color.parseColor("#1565C0")))
        }.also { content.addView(it) }

        // Set Geofence card
        val setCard = card(dp)
        setCard.addView(tv("Set New Office Location", 14f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = lp().also { m -> m.bottomMargin = px(12,dp) }
        })

        setCard.addView(tv("Latitude", 12f, Color.parseColor("#757575")).also { it.layoutParams = lp().also { m -> m.bottomMargin = px(4,dp) } })
        etLat = EditText(this).apply {
            hint = "e.g. 30.8109"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutParams = lp().also { it.bottomMargin = px(10,dp) }
        }
        setCard.addView(etLat)

        setCard.addView(tv("Longitude", 12f, Color.parseColor("#757575")).also { it.layoutParams = lp().also { m -> m.bottomMargin = px(4,dp) } })
        etLng = EditText(this).apply {
            hint = "e.g. 73.4471"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutParams = lp().also { it.bottomMargin = px(10,dp) }
        }
        setCard.addView(etLng)

        setCard.addView(tv("Radius (meters)", 12f, Color.parseColor("#757575")).also { it.layoutParams = lp().also { m -> m.bottomMargin = px(4,dp) } })

        val radiusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp().also { it.bottomMargin = px(14,dp) }
        }
        etRadius = EditText(this).apply {
            hint = "e.g. 200"; inputType = InputType.TYPE_CLASS_NUMBER; setText("200")
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = px(8,dp) }
        }
        radiusRow.addView(etRadius)
        radiusRow.addView(tv("meters", 12f, Color.parseColor("#9E9E9E")))
        setCard.addView(radiusRow)

        // Quick radius buttons
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp().also { it.bottomMargin = px(14,dp) }
            listOf("50m","100m","200m","500m").forEach { label ->
                Button(this@OfficeGeofenceActivity).apply {
                    text = label; textSize = 11f
                    setTextColor(Color.parseColor("#1565C0"))
                    background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 8f*dp; setColor(Color.parseColor("#E3F2FD")) }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = px(4,dp) }
                    setOnClickListener { etRadius.setText(label.replace("m","")) }
                }.also { addView(it) }
            }
        }.also { setCard.addView(it) }

        // Copy from Dashboard
        Button(this).apply {
            text = "Copy from Dashboard Geofence"; textSize = 13f
            setTextColor(Color.parseColor("#E65100"))
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp; setColor(Color.WHITE); setStroke(px(1,dp), Color.parseColor("#E65100")) }
            layoutParams = lp().also { it.bottomMargin = px(8,dp) }
            setOnClickListener { copyFromDashboardGeofence() }
        }.also { setCard.addView(it) }

        // Save
        Button(this).apply {
            text = "Save Geofence"; textSize = 14f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp; setColor(Color.parseColor("#1565C0")) }
            layoutParams = lp()
            setOnClickListener { saveGeofence() }
        }.also { setCard.addView(it) }
        content.addView(setCard)

        // Status
        tvStatus = tv("", 13f, Color.parseColor("#2E7D32")).also {
            it.gravity = Gravity.CENTER
            it.layoutParams = lp().also { m -> m.topMargin = px(8,dp) }
        }
        content.addView(tvStatus)

        // Employee Attendance List Card
        val empCard = card(dp)
        empCard.addView(tv("Today's Employee Attendance", 14f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = lp().also { m -> m.bottomMargin = px(8,dp) }
        })
        employeeContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        empCard.addView(employeeContainer)
        content.addView(empCard)

        root.addView(scroll)
        return root
    }

    private fun loadEmployeesWithAttendance() {
        val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val dp = resources.displayMetrics.density

        db.getReference("employees").get().addOnSuccessListener { empSnap ->
            employeeContainer.removeAllViews()
            if (!empSnap.exists()) {
                employeeContainer.addView(tv("No employees found", 12f, Color.parseColor("#9E9E9E")))
                return@addOnSuccessListener
            }

            for (emp in empSnap.children) {
                val deviceId = emp.key ?: continue
                val name = emp.child("employeeName").value?.toString() ?: "Unknown"

                db.getReference("attendance").child(deviceId).child(todayKey).get()
                    .addOnSuccessListener { attSnap ->
                        val checkIn = attSnap.child("checkInTime").value?.toString() ?: ""
                        val checkOut = attSnap.child("checkOutTime").value?.toString() ?: ""
                        val status = attSnap.child("status").value?.toString() ?: ""

                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, px(8,dp), 0, px(8,dp))
                            background = android.graphics.drawable.ColorDrawable(Color.parseColor("#FAFAFA"))
                            layoutParams = lp().also { it.bottomMargin = px(4,dp) }
                        }

                        val nameView = tv(name, 13f, Color.parseColor("#111111"), bold = true).also {
                            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val statusColor = when(status) {
                            "ON_TIME" -> Color.parseColor("#2E7D32")
                            "LATE" -> Color.parseColor("#E65100")
                            "OVERTIME" -> Color.parseColor("#1565C0")
                            else -> Color.parseColor("#9E9E9E")
                        }
                        val statusText = when {
                            checkIn.isNotEmpty() && checkOut.isNotEmpty() -> "$checkIn → $checkOut"
                            checkIn.isNotEmpty() -> "In: $checkIn"
                            else -> "Not marked"
                        }
                        val statusView = tv(statusText, 11f, statusColor)

                        row.addView(nameView)
                        row.addView(statusView)

                        // Divider
                        val divider = View(this).apply {
                            setBackgroundColor(Color.parseColor("#EEEEEE"))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                        }

                        runOnUiThread {
                            employeeContainer.addView(row)
                            employeeContainer.addView(divider)
                        }
                    }
            }
        }
    }

    private fun loadCurrentGeofence() {
        db.getReference("attendanceGeofence").get().addOnSuccessListener { snap ->
            val lat = snap.child("lat").value?.toString() ?: ""
            val lng = snap.child("lng").value?.toString() ?: ""
            val radius = snap.child("radius").value?.toString() ?: ""
            if (lat.isEmpty()) {
                tvCurrentSettings.text = "No geofence set yet"
                tvCurrentSettings.setTextColor(Color.parseColor("#9E9E9E"))
            } else {
                tvCurrentSettings.text = "Lat: $lat\nLng: $lng\nRadius: ${radius}m"
                tvCurrentSettings.setTextColor(Color.parseColor("#2E7D32"))
                etLat.setText(lat); etLng.setText(lng); etRadius.setText(radius)
            }
        }
    }

    private fun copyFromDashboardGeofence() {
        db.getReference("dashboardGeofences").child("active").get()
            .addOnSuccessListener { snap ->
                val lat = snap.child("lat").value?.toString() ?: ""
                val lng = snap.child("lng").value?.toString() ?: ""
                val radius = snap.child("radius").value?.toString() ?: ""
                if (lat.isEmpty()) {
                    showMsg("No dashboard geofence found.")
                } else {
                    etLat.setText(lat); etLng.setText(lng); etRadius.setText(radius)
                    tvStatus.text = "Copied! Press Save to confirm."
                    tvStatus.setTextColor(Color.parseColor("#E65100"))
                }
            }
    }

    private fun saveGeofence() {
        val lat = etLat.text.toString().toDoubleOrNull()
        val lng = etLng.text.toString().toDoubleOrNull()
        val radius = etRadius.text.toString().toDoubleOrNull()
        if (lat == null || lng == null) { showMsg("Valid latitude/longitude required."); return }
        if (radius == null || radius <= 0) { showMsg("Valid radius required."); return }

        db.getReference("attendanceGeofence").setValue(mapOf("lat" to lat, "lng" to lng, "radius" to radius))
            .addOnSuccessListener {
                tvStatus.text = "Saved! Lat: $lat, Lng: $lng, Radius: ${radius.toInt()}m"
                tvStatus.setTextColor(Color.parseColor("#2E7D32"))
                tvCurrentSettings.text = "Lat: $lat\nLng: $lng\nRadius: ${radius.toInt()}m"
                tvCurrentSettings.setTextColor(Color.parseColor("#2E7D32"))
                loadEmployeesWithAttendance()
            }
            .addOnFailureListener { e -> showMsg("Save failed: ${e.message}") }
    }

    private fun lp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun px(v: Int, dp: Float) = (v * dp).toInt()
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
    private fun showMsg(msg: String) { AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show() }
}