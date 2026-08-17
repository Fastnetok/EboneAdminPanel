package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.database.FirebaseDatabase

class DealerMenuActivity : BaseAdminActivity() {

    private lateinit var minimumInput: EditText
    private lateinit var maximumInput: EditText
    private lateinit var statusText: TextView

    private val settingsRef =
        FirebaseDatabase.getInstance().getReference("paymentSettings")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dealer_menu)

        minimumInput = findViewById(R.id.minimumPaymentInput)
        maximumInput = findViewById(R.id.maximumPaymentInput)
        statusText = findViewById(R.id.paymentLimitStatus)

        findViewById<Button>(R.id.savePaymentLimitsButton).setOnClickListener {
            saveLimits()
        }

        findViewById<TextView>(R.id.menuBack).setOnClickListener {
            finish()
        }

        loadLimits()
    }

    private fun loadLimits() {
        settingsRef.get()
            .addOnSuccessListener { snapshot ->
                val minimum = snapshot.child("minimumAmount")
                    .getValue(Long::class.java) ?: 3000L

                val maximum = snapshot.child("maximumAmount")
                    .getValue(Long::class.java) ?: 100000L

                minimumInput.setText(minimum.toString())
                maximumInput.setText(maximum.toString())
                statusText.text = "Saved: Rs. $minimum - Rs. $maximum"
            }
            .addOnFailureListener {
                minimumInput.setText("3000")
                maximumInput.setText("100000")
                statusText.text = "Could not load saved limits."

                Toast.makeText(
                    this,
                    "Payment limits load nahi ho sakin.",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun saveLimits() {
        val minimum = minimumInput.text.toString().trim().toLongOrNull()
        val maximum = maximumInput.text.toString().trim().toLongOrNull()

        if (minimum == null || maximum == null) {
            Toast.makeText(
                this,
                "Dono limits mein valid amount likhein.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (minimum < 1L) {
            Toast.makeText(
                this,
                "Minimum amount Rs. 1 se kam nahi ho sakta.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (maximum < minimum) {
            Toast.makeText(
                this,
                "Maximum amount minimum se kam nahi ho sakta.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val data = mapOf(
            "minimumAmount" to minimum,
            "maximumAmount" to maximum
        )

        settingsRef.setValue(data)
            .addOnSuccessListener {
                statusText.text = "Saved: Rs. $minimum - Rs. $maximum"

                Toast.makeText(
                    this,
                    "Payment limits save ho gayi hain.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .addOnFailureListener { error ->
                Toast.makeText(
                    this,
                    "Save failed: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }
}