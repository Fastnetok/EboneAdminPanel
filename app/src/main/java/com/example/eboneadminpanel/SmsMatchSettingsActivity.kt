package com.example.eboneadminpanel

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SmsMatchSettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "sms_match_prefs"
        private const val KEY_DAYS = "match_window_days"

        fun getMatchWindowDays(context: Context): Int {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_DAYS, 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        val savedDays = getSharedPreferences("sms_match_prefs", Context.MODE_PRIVATE)
            .getInt("match_window_days", 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#F4F6FA"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#0D2E5C"))
            setPadding((16*dp).toInt(), (48*dp).toInt(), (16*dp).toInt(), (16*dp).toInt())
        }
        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            background = null
            setColorFilter(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((28*dp).toInt(), (28*dp).toInt())
            setOnClickListener { finish() }
        }
        val titleTv = TextView(this).apply {
            text = "SMS Match Settings"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.marginStart = (12*dp).toInt() }
        }
        header.addView(backBtn)
        header.addView(titleTv)
        root.addView(header)

        // Scrollable content
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt())
        }
        scroll.addView(content)

        val desc = TextView(this).apply {
            text = "Select the time window for matching incoming SMS with pending payments"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#757575"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (16*dp).toInt() }
        }
        content.addView(desc)

        // Simple RadioGroup with direct RadioButton children
        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (16*dp).toInt() }
        }

        val rb1 = makeRadioButton("Today only (1 day)", 1, savedDays)
        val rb2 = makeRadioButton("Today + Yesterday (2 days)", 2, savedDays)
        val rb3 = makeRadioButton("Last 3 days", 3, savedDays)
        val rb7 = makeRadioButton("Last 7 days", 7, savedDays)

        radioGroup.addView(rb1)
        radioGroup.addView(rb2)
        radioGroup.addView(rb3)
        radioGroup.addView(rb7)
        content.addView(radioGroup)

        // FIX: if the saved value isn't one of the standard radio options
        // (1, 2, 3, 7), it must have come from the custom field — so none
        // of the radio buttons get checked above, which used to make it
        // look like the setting had "disappeared" even though it was
        // still saved correctly. Un-check all radios in that case, since
        // the custom field below will show/represent the real value.
        val isStandardValue = savedDays in setOf(1, 2, 3, 7)
        if (!isStandardValue) {
            radioGroup.clearCheck()
        }

        // Custom days input
        val customLabel = TextView(this).apply {
            text = "Custom number of days:"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#444444"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6*dp).toInt() }
        }
        content.addView(customLabel)

        val customEt = EditText(this).apply {
            hint = "e.g. 360"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                (120*dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (16*dp).toInt() }
            // FIX: restore the previously saved custom value so it doesn't
            // look "gone" when reopening this screen.
            if (!isStandardValue) {
                setText(savedDays.toString())
            }
        }
        content.addView(customEt)

        // Save Button
        val saveBtn = Button(this).apply {
            text = "Save Settings"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1565C0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                // Custom input takes priority if filled
                val customText = customEt.text.toString().trim()
                val days = if (customText.isNotEmpty()) {
                    // FIX: was coerceIn(1, 30), which silently capped any
                    // custom entry (like 360) down to 30 with no warning.
                    // Now allows up to 3650 days (~10 years).
                    customText.toIntOrNull()?.coerceIn(1, 3650) ?: 1
                } else {
                    when (radioGroup.checkedRadioButtonId) {
                        rb2.id -> 2
                        rb3.id -> 3
                        rb7.id -> 7
                        else -> 1
                    }
                }
                getSharedPreferences("sms_match_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("match_window_days", days).apply()

                Toast.makeText(
                    this@SmsMatchSettingsActivity,
                    "Saved: $days day(s)",
                    Toast.LENGTH_SHORT
                ).show()

                finish()
            }
        }
        content.addView(saveBtn)

        root.addView(scroll)
        return root
    }

    private fun makeRadioButton(label: String, days: Int, savedDays: Int): RadioButton {
        return RadioButton(this).apply {
            text = label
            textSize = 14f
            id = View.generateViewId()
            isChecked = (savedDays == days)
            setPadding(8, 16, 8, 16)
        }
    }
}