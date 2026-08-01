package com.example.eboneadminpanel

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
            binding.layoutDaysRemaining.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRegeneratePin.setOnClickListener { generateNewPin() }
        binding.btnSaveCustomer.setOnClickListener { saveCustomer() }
    }

    private fun generateNewPin() {
        currentPin = (100000..999999).random().toString()
        binding.tvGeneratedPin.text = currentPin
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
                    showResult("A customer with ID \"$customerId\" already exists. Choose a different ID.", isError = true)
                    binding.btnSaveCustomer.isEnabled = true
                    return@addOnSuccessListener
                }

                val billingCycleDays = 30
                var lastPaymentDate: Long? = null
                if (binding.switchExistingCustomer.isChecked) {
                    val daysRemaining = binding.etDaysRemaining.text.toString().trim().toIntOrNull()
                    if (daysRemaining == null) {
                        showResult("Please enter Days Remaining Until Expiry (a whole number).", isError = true)
                        binding.btnSaveCustomer.isEnabled = true
                        return@addOnSuccessListener
                    }
                    val msPerDay = 24L * 60L * 60L * 1000L
                    lastPaymentDate = System.currentTimeMillis() + (daysRemaining - billingCycleDays) * msPerDay
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
                        showResult(
                            "✅ Customer \"$customerId\" created!\n\n" +
                                    "Company: $company\nPackage: $packageName — Rs. ${"%.0f".format(price)}\n\n" +
                                    "Send this to the Employee via WhatsApp:\nID: $customerId\nPIN: $currentPin",
                            isError = false
                        )
                        Toast.makeText(this, "Customer saved successfully", Toast.LENGTH_SHORT).show()
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

    private fun showResult(message: String, isError: Boolean) {
        binding.tvResult.visibility = android.view.View.VISIBLE
        binding.tvResult.text = message
        binding.tvResult.setBackgroundResource(if (isError) R.drawable.bg_stat_danger else R.drawable.bg_stat_success)
        binding.tvResult.setTextColor(
            android.graphics.Color.parseColor(if (isError) "#C62828" else "#1B5E20")
        )
    }
}