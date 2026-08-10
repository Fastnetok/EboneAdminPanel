package com.example.eboneadminpanel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.eboneadminpanel.databinding.ActivityAddCustomerBinding
import com.google.firebase.firestore.FirebaseFirestore

class AddCustomerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddCustomerBinding
    private val db = FirebaseFirestore.getInstance()
    private var currentPin: String = ""
    private var lastSavedCustomerId: String = ""
    private var lastSavedPin: String = ""

    private val companies = listOf("EBONE", "WATEEN", "ZONG")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddCustomerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spinnerCompany.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, companies
        )

        generateNewPin()

        binding.switchExistingCustomer.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutDaysRemaining.visibility =
                if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRegeneratePin.setOnClickListener { generateNewPin() }
        binding.btnSaveCustomer.setOnClickListener { saveCustomer() }

        // Copy PIN on tap
        binding.tvGeneratedPin.setOnClickListener {
            copyToClipboard(currentPin)
            Toast.makeText(this, "PIN copied!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateNewPin() {
        currentPin = (100000..999999).random().toString()
        binding.tvGeneratedPin.text = currentPin
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PIN", text))
    }

    private fun saveCustomer() {
        val customerId = binding.etCustomerId.text.toString().trim()
        val packageName = binding.etPackage.text.toString().trim()
        val priceText = binding.etPrice.text.toString().trim()
        val company = companies[binding.spinnerCompany.selectedItemPosition]

        if (customerId.isEmpty() || packageName.isEmpty() || priceText.isEmpty()) {
            showResult("Please fill in Customer ID, Package, and Price.", isError = true)
            return
        }
        val price = priceText.toDoubleOrNull()
        if (price == null) {
            showResult("Package Price must be a number.", isError = true)
            return
        }

        binding.btnSaveCustomer.isEnabled = false

        db.collection("customers").document(customerId).get()
            .addOnSuccessListener { existing ->
                if (existing.exists()) {
                    showResult(
                        "A customer with ID \"$customerId\" already exists. Choose a different ID.",
                        isError = true
                    )
                    binding.btnSaveCustomer.isEnabled = true
                    return@addOnSuccessListener
                }

                val billingCycleDays = 30
                var lastPaymentDate: Long? = null
                if (binding.switchExistingCustomer.isChecked) {
                    val daysRemaining =
                        binding.etDaysRemaining.text.toString().trim().toIntOrNull()
                    if (daysRemaining == null) {
                        showResult(
                            "Please enter Days Remaining Until Expiry (a whole number).",
                            isError = true
                        )
                        binding.btnSaveCustomer.isEnabled = true
                        return@addOnSuccessListener
                    }
                    val msPerDay = 24L * 60L * 60L * 1000L
                    lastPaymentDate =
                        System.currentTimeMillis() + (daysRemaining - billingCycleDays) * msPerDay
                }

                val data = hashMapOf(
                    "customerId" to customerId,
                    "packageId" to packageName,
                    "packagePrice" to price,
                    "currentBalance" to 0.0,
                    "activationStatus" to "ACTIVE",
                    "billingCycleDays" to billingCycleDays,
                    "registrationPin" to currentPin,
                    "linkedDeviceId" to null,
                    "lastPaymentDate" to lastPaymentDate,
                    "ispProvider" to company
                )

                db.collection("customers").document(customerId).set(data)
                    .addOnSuccessListener {
                        lastSavedCustomerId = customerId
                        lastSavedPin = currentPin

                        showResult(
                            "✅ Customer \"$customerId\" created!\n\n" +
                                    "Company: $company\nPackage: $packageName — Rs. ${"%.0f".format(price)}\n\n" +
                                    "Share login details with the customer below.",
                            isError = false
                        )

                        // Show share buttons
                        showShareDialog(customerId, currentPin)

                        binding.etCustomerId.text.clear()
                        binding.etPackage.text.clear()
                        binding.etPrice.text.clear()
                        binding.etDaysRemaining.text.clear()
                        binding.switchExistingCustomer.isChecked = false
                        generateNewPin()
                        binding.btnSaveCustomer.isEnabled = true
                    }
                    .addOnFailureListener { e ->
                        showResult("Failed to save: ${e.message}", isError = true)
                        binding.btnSaveCustomer.isEnabled = true
                    }
            }
            .addOnFailureListener { e ->
                showResult("Could not check existing ID: ${e.message}", isError = true)
                binding.btnSaveCustomer.isEnabled = true
            }
    }

    private fun showShareDialog(customerId: String, pin: String) {
        val message = buildShareMessage(customerId, pin)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Share Login Details")
            .setMessage("Send account details to customer:")
            .setPositiveButton("📱 WhatsApp") { _, _ ->
                shareViaWhatsApp(message)
            }
            .setNeutralButton("💬 SMS") { _, _ ->
                shareViaSms(message)
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun buildShareMessage(customerId: String, pin: String): String {
        return "🌐 *Your Internet Account is Ready!*\n\n" +
                "User ID: *$customerId*\n" +
                "PIN: *$pin*\n\n" +
                "Download the app and activate your account using these details.\n\n" +
                "_Keep this PIN safe — it's used to activate your account._"
    }

    private fun shareViaWhatsApp(message: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, message)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // WhatsApp not installed — fall back to general share
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            }
            startActivity(Intent.createChooser(intent, "Share via WhatsApp"))
        }
    }

    private fun shareViaSms(message: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:")
            putExtra("sms_body", message)
        }
        startActivity(intent)
    }

    private fun showResult(message: String, isError: Boolean) {
        binding.tvResult.visibility = android.view.View.VISIBLE
        binding.tvResult.text = message
        binding.tvResult.setBackgroundResource(
            if (isError) R.drawable.bg_stat_danger else R.drawable.bg_stat_success
        )
        binding.tvResult.setTextColor(
            android.graphics.Color.parseColor(if (isError) "#C62828" else "#1B5E20")
        )
    }
}