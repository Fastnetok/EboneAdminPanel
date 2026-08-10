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
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class AppVersionActivity : AppCompatActivity() {

    private val db = FirebaseDatabase.getInstance()
    private lateinit var listContainer: LinearLayout
    private lateinit var tvLatestVersion: TextView
    private lateinit var tvUpToDate: TextView
    private lateinit var tvOutdated: TextView
    private lateinit var tvNeverOpened: TextView
    private var latestVersion = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        loadData()
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
        titleBlock.addView(tv("App Version Status", 16f, Color.WHITE, bold = true))
        titleBlock.addView(tv("Employee app update tracker", 12f, Color.parseColor("#B8C6DE")))
        header.addView(titleBlock)

        // Set Latest Version Button
        Button(this).apply {
            text = "Set Latest"
            textSize = 10f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 8f*dp; setColor(Color.parseColor("#1A3F7A")) }
            setPadding(px(10,dp), px(6,dp), px(10,dp), px(6,dp))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { showSetVersionDialog() }
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

        // Latest version info card
        val infoCard = card(dp)
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        infoRow.addView(tv("Latest Version:", 13f, Color.parseColor("#555555")).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvLatestVersion = tv("Not Set", 14f, Color.parseColor("#1565C0"), bold = true)
        infoRow.addView(tvLatestVersion)
        infoCard.addView(infoRow)
        content.addView(infoCard)

        // Stats Row
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = px(10,dp) }
        }
        tvUpToDate = tv("—", 22f, Color.parseColor("#2E7D32"), bold = true).also { it.gravity = Gravity.CENTER }
        tvOutdated = tv("—", 22f, Color.parseColor("#C62828"), bold = true).also { it.gravity = Gravity.CENTER }
        tvNeverOpened = tv("—", 22f, Color.parseColor("#9E9E9E"), bold = true).also { it.gravity = Gravity.CENTER }

        statsRow.addView(miniStatBox(tvUpToDate, "Up to Date ✅", "#E8F5E9", "#A5D6A7", dp).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { m -> m.marginEnd = px(6,dp) }
        })
        statsRow.addView(miniStatBox(tvOutdated, "Outdated ⚠️", "#FFEBEE", "#FFCDD2", dp).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { m -> m.marginEnd = px(6,dp) }
        })
        statsRow.addView(miniStatBox(tvNeverOpened, "Never ❓", "#F5F5F5", "#E0E0E0", dp).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        content.addView(statsRow)

        // Employee List Card
        val listCard = card(dp)
        listCard.addView(tv("Employee Version Details", 14f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(8,dp) }
        })
        listCard.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = llp().also { it.height = 1; it.bottomMargin = px(4,dp) }
        })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(listContainer)
        content.addView(listCard)

        root.addView(scroll)
        return root
    }

    private fun loadData() {
        // Load latest version from Firebase
        db.getReference("appConfig").child("latestVersion").get()
            .addOnSuccessListener { snap ->
                latestVersion = snap.value?.toString() ?: ""
                tvLatestVersion.text = if (latestVersion.isNotEmpty()) latestVersion else "Not Set"
                loadEmployeeVersions()
            }
            .addOnFailureListener { loadEmployeeVersions() }
    }

    private fun loadEmployeeVersions() {
        val dp = resources.displayMetrics.density
        db.getReference("ApprovedDevices").get()
            .addOnSuccessListener { snap ->
                listContainer.removeAllViews()
                var upToDate = 0; var outdated = 0; var never = 0
                var rowCount = 0

                for (device in snap.children) {
                    val empName = device.child("employeeName").value?.toString() ?: continue
                    val appVersion = device.child("appVersion").value?.toString() ?: ""
                    val lastSeen = (device.child("lastVersionUpdate").value as? Long) ?: 0L

                    val isLatest = latestVersion.isNotEmpty() && appVersion == latestVersion
                    val hasVersion = appVersion.isNotEmpty()

                    when {
                        !hasVersion -> never++
                        isLatest -> upToDate++
                        else -> outdated++
                    }

                    rowCount++
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setBackgroundColor(if (rowCount % 2 == 0) Color.parseColor("#FAFAFA") else Color.WHITE)
                        setPadding(0, px(12,dp), 0, px(12,dp))
                    }

                    val infoBlock = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    infoBlock.addView(tv(empName, 14f, Color.parseColor("#111111"), bold = true))

                    val versionText = if (hasVersion) "v$appVersion" else "Never opened app"
                    val versionColor = when {
                        !hasVersion -> Color.parseColor("#9E9E9E")
                        isLatest -> Color.parseColor("#2E7D32")
                        else -> Color.parseColor("#E65100")
                    }
                    infoBlock.addView(tv(versionText, 12f, versionColor).also {
                        it.layoutParams = llp().also { m -> m.topMargin = px(2,dp) }
                    })

                    if (lastSeen > 0) {
                        val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(lastSeen))
                        infoBlock.addView(tv("Updated: $dateStr", 10f, Color.parseColor("#9E9E9E")).also {
                            it.layoutParams = llp().also { m -> m.topMargin = px(1,dp) }
                        })
                    }

                    val badgeText: String; val badgeColor: Int; val badgeBg: Int
                    when {
                        !hasVersion -> { badgeText = "❓"; badgeColor = Color.parseColor("#9E9E9E"); badgeBg = Color.parseColor("#F5F5F5") }
                        isLatest -> { badgeText = "✅ Latest"; badgeColor = Color.parseColor("#2E7D32"); badgeBg = Color.parseColor("#E8F5E9") }
                        else -> { badgeText = "⚠️ Update"; badgeColor = Color.parseColor("#E65100"); badgeBg = Color.parseColor("#FFF3E0") }
                    }
                    row.addView(infoBlock)
                    row.addView(tv(badgeText, 10f, badgeColor, bold = true).also {
                        it.background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*(resources.displayMetrics.density); setColor(badgeBg)
                        }
                        it.setPadding(px(8,dp), px(3,dp), px(8,dp), px(3,dp))
                    })
                    listContainer.addView(row)
                    listContainer.addView(View(this).apply {
                        setBackgroundColor(Color.parseColor("#EEEEEE"))
                        layoutParams = llp().also { it.height = 1 }
                    })
                }

                tvUpToDate.text = "$upToDate"
                tvOutdated.text = "$outdated"
                tvNeverOpened.text = "$never"
            }
    }

    private fun showSetVersionDialog() {
        val dp = resources.displayMetrics.density
        val etVersion = EditText(this).apply {
            hint = "e.g. 1.0.6"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(latestVersion)
            setPadding(px(12,dp), px(10,dp), px(12,dp), px(10,dp))
        }
        AlertDialog.Builder(this)
            .setTitle("Set Latest Version")
            .setMessage("Enter the latest version number released on GitHub")
            .setView(etVersion)
            .setPositiveButton("Save") { _, _ ->
                val ver = etVersion.text.toString().trim()
                if (ver.isEmpty()) return@setPositiveButton
                db.getReference("appConfig").child("latestVersion").setValue(ver)
                    .addOnSuccessListener {
                        latestVersion = ver
                        tvLatestVersion.text = ver
                        loadEmployeeVersions()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun miniStatBox(v: TextView, label: String, bgColor: String, borderColor: String, dp: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp; setColor(Color.parseColor(bgColor)); setStroke(px(1,dp), Color.parseColor(borderColor)) }
        setPadding(px(8,dp), px(12,dp), px(8,dp), px(12,dp))
        addView(v)
        addView(tv(label, 9f, Color.parseColor("#555555")).also { it.gravity = Gravity.CENTER; it.layoutParams = llp().also { m -> m.topMargin = px(2,dp) } })
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
}