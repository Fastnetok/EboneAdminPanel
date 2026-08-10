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

class OfficeGeofenceActivity : AppCompatActivity() {

    private val db = FirebaseDatabase.getInstance()
    private lateinit var etLat: EditText
    private lateinit var etLng: EditText
    private lateinit var etRadius: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvCurrentSettings: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        loadCurrentGeofence()
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
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp; setColor(Color.parseColor("#E3F2FD")) }
            setPadding(px(14,dp), px(12,dp), px(14,dp), px(12,dp))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = px(10,dp) }

        // Set Geofence card
        val setCard = card(dp)
        setCard.addView(tv("Set New Office Location", 14f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = lp().also { m -> m.bottomMargin = px(12,dp) }
        })

        setCard.addView(tv("Latitude", 12f, Color.parseColor("#757575")).also { it.layoutParams = lp().also { m -> m.bottomMargin = px(4,dp) } })
        etLat = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutParams = lp().also { it.bottomMargin = px(10,dp) }
        }
        setCard.addView(etLat)

        setCard.addView(tv("Longitude", 12f, Color.parseColor("#757575")).also { it.layoutParams = lp().also { m -> m.bottomMargin = px(4,dp) } })
        etLng = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutParams = lp().also { it.bottomMargin = px(10,dp) }
        }
        setCard.addView(etLng)

        setCard.addView(tv("Radius (meters)", 12f, Color.parseColor("#757575")).also { it.layoutParams = lp().also { m -> m.bottomMargin = px(4,dp) } })

        val radiusRow = LinearLayout(this).apply {
            layoutParams = lp().also { it.bottomMargin = px(14,dp) }
        }
        etRadius = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = px(8,dp) }
        }
        radiusRow.addView(etRadius)
        setCard.addView(radiusRow)

            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp().also { it.bottomMargin = px(14,dp) }
            listOf("50m","100m","200m","500m").forEach { label ->
                    setTextColor(Color.parseColor("#1565C0"))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = px(4,dp) }
                    setOnClickListener { etRadius.setText(label.replace("m","")) }
            }

        Button(this).apply {
            setTextColor(Color.parseColor("#E65100"))
            layoutParams = lp().also { it.bottomMargin = px(8,dp) }
            setOnClickListener { copyFromDashboardGeofence() }
        }.also { setCard.addView(it) }

        Button(this).apply {
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

        root.addView(scroll)
        return root
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
                } else {
                    tvStatus.setTextColor(Color.parseColor("#E65100"))
                }
            }
    }

    private fun saveGeofence() {
        val lat = etLat.text.toString().toDoubleOrNull()
        val lng = etLng.text.toString().toDoubleOrNull()
        val radius = etRadius.text.toString().toDoubleOrNull()

            .addOnSuccessListener {
                tvStatus.setTextColor(Color.parseColor("#2E7D32"))
                tvCurrentSettings.text = "Lat: $lat\nLng: $lng\nRadius: ${radius.toInt()}m"
                tvCurrentSettings.setTextColor(Color.parseColor("#2E7D32"))
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