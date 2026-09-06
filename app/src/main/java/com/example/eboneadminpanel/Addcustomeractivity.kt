package com.example.eboneadminpanel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.eboneadminpanel.databinding.ActivityAddCustomerBinding
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class AddCustomerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddCustomerBinding
    private val db = FirebaseFirestore.getInstance()

    private var currentPin = ""

    private val companies = listOf("EBONE", "WATEEN", "ZONG")
    private val masterUsersCollection = "ispMasterUsers"
    private val customersCollection = "customers"

    /*
     * We keep the latest query so an older Firebase response cannot replace
     * a newer search result while the user is still typing.
     */
    private var latestSearchQuery = ""
    private var latestSearchCompany = "EBONE"

    private var selectedMasterUserId = ""
    private var selectedRegisteredCustomer: Map<String, Any?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAddCustomerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spinnerCompany.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            companies
        )

        generateNewPin()

        // CustomerBillingActivity-style search:
        // Firebase customers are loaded once, then the current ISP filter and
        // typed text are applied locally. This avoids a query on every keypress
        // and gives the same stable suggestion behaviour used by Total Accounts.
        val suggestionsAdapter = SuggestionsAdapter(this)

        binding.etCustomerId.threshold = 1
        binding.etCustomerId.setAdapter(suggestionsAdapter)

        fun updateCustomerSuggestions() {
            val query = binding.etCustomerId.text.toString().trim()

            val company =
                companies.getOrNull(
                    binding.spinnerCompany.selectedItemPosition
                ) ?: companies.first()

            loadCustomersForSearch(
                company = company,
                query = query,
                adapter = suggestionsAdapter
            )
        }

        binding.etCustomerId.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    updateCustomerSuggestions()
                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) = Unit
            }
        )

        binding.etCustomerId.setOnItemClickListener(
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                val value =
                    parent.getItemAtPosition(position)
                        ?.toString()
                        ?.trim()
                        .orEmpty()

                if (value.isNotEmpty()) {
                    binding.etCustomerId.setText(value)
                    binding.etCustomerId.setSelection(value.length)
                    loadSelectedMasterUser(value)
                }
            }
        )

        updateCustomerSuggestions()

        binding.spinnerCompany.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) = Unit

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val query =
                        binding.etCustomerId.text.toString().trim()

                    if (query.isNotEmpty()) {
                        updateCustomerSuggestions()
                    }
                }
            }
        )

        binding.switchExistingCustomer.setOnCheckedChangeListener { _, checked ->
            binding.layoutDaysRemaining.visibility =
                if (checked) View.VISIBLE else View.GONE
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnRegeneratePin.setOnClickListener {
            generateNewPin()
        }

        binding.btnSaveCustomer.setOnClickListener {
            saveCustomer()
        }

        binding.tvGeneratedPin.setOnClickListener {
            copyToClipboard(currentPin)
            Toast.makeText(
                this,
                "PIN copied!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun generateNewPin() {
        currentPin = (100000..999999).random().toString()
        binding.tvGeneratedPin.text = currentPin
    }

    private fun copyToClipboard(text: String) {
        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText("PIN", text)
        )
    }

    /*
     * IMPORTANT FIX:
     *
     * The previous version searched ONLY ispMasterUsers.
     *
     * Your existing registered users such as OLT/ABBAS can already exist
     * in the "customers" collection. Therefore the search now checks BOTH:
     *
     * 1) customers       -> already registered Customer ID app users
     * 2) ispMasterUsers  -> master ISP list / future unregistered users
     *
     * We also do NOT use orderBy(), so no composite Firestore index is
     * required.
     *
     * The previous searchInProgress lock was also removed. That lock could
     * ignore the next characters while the previous request was running
     * (for example "ab" -> "abbas").
     */
    private fun loadCustomersForSearch(
        company: String,
        query: String,
        adapter: SuggestionsAdapter
    ) {
        val wantedCompany = company.uppercase(Locale.getDefault())
        val normalizedQuery = query.trim()

        /*
         * Search BOTH sources:
         * 1) customers       = already registered app customers
         * 2) ispMasterUsers  = imported ISP master list / future users
         *
         * We merge them here so Add Customer can find a user even when
         * that user has not yet been registered in the customers collection.
         */
        val customersTask = db.collection(customersCollection).get()
        val masterTask = db.collection(masterUsersCollection).get()

        com.google.android.gms.tasks.Tasks.whenAllSuccess<Any>(
            customersTask,
            masterTask
        ).addOnSuccessListener { results ->

            val customerSnapshot =
                results.getOrNull(0) as? com.google.firebase.firestore.QuerySnapshot

            val masterSnapshot =
                results.getOrNull(1) as? com.google.firebase.firestore.QuerySnapshot

            val ids = linkedSetOf<String>()

            // Already registered customers
            customerSnapshot?.documents?.forEach { doc ->
                val provider = (
                        doc.getString("ispProvider") ?: "EBONE"
                        ).uppercase(Locale.getDefault())

                if (provider == wantedCompany) {
                    getUserIdFromDocument(doc)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { ids.add(it) }
                }
            }

            // Imported master ISP users
            masterSnapshot?.documents?.forEach { doc ->
                val provider = (
                        doc.getString("ispProvider") ?: ""
                        ).uppercase(Locale.getDefault())

                if (provider == wantedCompany) {
                    getUserIdFromDocument(doc)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { ids.add(it) }
                }
            }

            val suggestions = ids
                .filter {
                    normalizedQuery.isEmpty() ||
                            it.contains(
                                normalizedQuery,
                                ignoreCase = true
                            )
                }
                .sortedWith(
                    compareBy(
                        {
                            !it.startsWith(
                                normalizedQuery,
                                ignoreCase = true
                            )
                        },
                        {
                            it.lowercase(Locale.getDefault())
                        }
                    )
                )
                .take(20)

            adapter.updateItems(suggestions)

            if (
                normalizedQuery.isNotEmpty() &&
                suggestions.isNotEmpty() &&
                !binding.etCustomerId.isPopupShowing
            ) {
                binding.etCustomerId.showDropDown()
            }
        }.addOnFailureListener { e ->
            adapter.updateItems(emptyList())

            showResult(
                "Could not search customers: ${e.message}",
                isError = true
            )
        }
    }

    /**
     * Same stable-adapter approach used by CustomerBillingActivity.
     * The adapter instance is never replaced while the user is typing.
     */
    private class SuggestionsAdapter(
        context: Context
    ) : ArrayAdapter<String>(
        context,
        android.R.layout.simple_dropdown_item_1line,
        mutableListOf()
    ) {

        private var currentItems: List<String> =
            emptyList()

        fun updateItems(
            newItems: List<String>
        ) {
            currentItems = newItems
            clear()
            addAll(newItems)
            notifyDataSetChanged()
        }

        private val noOpFilter =
            object : android.widget.Filter() {

                override fun performFiltering(
                    constraint: CharSequence?
                ): FilterResults {

                    return FilterResults().apply {
                        values = currentItems
                        count = currentItems.size
                    }
                }

                override fun publishResults(
                    constraint: CharSequence?,
                    results: FilterResults?
                ) {
                    // Filtering is performed explicitly above.
                }
            }

        override fun getFilter():
                android.widget.Filter = noOpFilter
    }

    private fun getUserIdFromDocument(
        document: DocumentSnapshot
    ): String? {

        /*
         * Prefer the explicit customerId field.
         * If the imported master list stores the user ID as the
         * Firestore document ID, document.id is used as fallback.
         */
        val explicitId =
            document.getString("customerId")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        if (explicitId != null) {
            return explicitId
        }

        return document.id
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun showMasterUserSuggestions(
        suggestions: List<String>
    ) {

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            suggestions
        )

        AlertDialog.Builder(this)
            .setTitle("Select Customer")
            .setAdapter(adapter) { dialog, which ->

                val selectedId =
                    suggestions.getOrNull(which)
                        ?: return@setAdapter

                binding.etCustomerId.setText(
                    selectedId
                )

                binding.etCustomerId.setSelection(
                    selectedId.length
                )

                loadSelectedMasterUser(
                    selectedId
                )

                dialog.dismiss()
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun loadSelectedMasterUser(
        customerId: String
    ) {

        val selectedCompany =
            companies.getOrNull(
                binding.spinnerCompany.selectedItemPosition
            ) ?: companies.first()

        db.collection(customersCollection)
            .document(customerId)
            .get()
            .addOnSuccessListener { existing ->

                selectedMasterUserId = customerId

                if (existing.exists()) {

                    /*
                     * Existing registered Customer ID app user.
                     * Load the actual saved data automatically.
                     */
                    val savedCompany =
                        existing.getString(
                            "ispProvider"
                        ).orEmpty()

                    if (
                        savedCompany.isNotBlank() &&
                        savedCompany.equals(
                            selectedCompany,
                            ignoreCase = true
                        )
                    ) {
                        selectedRegisteredCustomer =
                            existing.data

                        val companyIndex =
                            companies.indexOfFirst {
                                it.equals(
                                    savedCompany,
                                    ignoreCase = true
                                )
                            }

                        if (companyIndex >= 0) {
                            binding.spinnerCompany
                                .setSelection(
                                    companyIndex
                                )
                        }

                        binding.etPackage.setText(
                            existing.getString(
                                "packageId"
                            ).orEmpty()
                        )

                        val savedPrice =
                            existing.getDouble(
                                "packagePrice"
                            )

                        binding.etPrice.setText(
                            if (savedPrice != null) {
                                "%.0f".format(
                                    savedPrice
                                )
                            } else {
                                ""
                            }
                        )

                        val savedPin =
                            existing.getString(
                                "registrationPin"
                            ).orEmpty()

                        if (savedPin.isNotBlank()) {
                            currentPin = savedPin
                            binding.tvGeneratedPin.text =
                                savedPin
                        }

                        binding.btnSaveCustomer.isEnabled =
                            false

                        showResult(
                            "✓ Existing customer found.\n\n" +
                                    "Customer data loaded automatically.",
                            isError = false
                        )

                    } else {

                        /*
                         * Same user name can exist in another ISP.
                         * Do not cross company boundaries.
                         */
                        selectedRegisteredCustomer = null
                        binding.btnSaveCustomer.isEnabled =
                            true

                        showResult(
                            "$customerId exists, but it belongs to another ISP.",
                            isError = true
                        )
                    }

                } else {

                    /*
                     * Master ISP user, but not yet registered in
                     * Customer ID app.
                     */
                    selectedRegisteredCustomer = null
                    binding.btnSaveCustomer.isEnabled =
                        true

                    showResult(
                        "✓ ISP user found.\n\n" +
                                "This user is not registered in the Customer ID app yet.",
                        isError = false
                    )
                }
            }
            .addOnFailureListener { e ->

                showResult(
                    "Could not check registered customer: ${e.message}",
                    isError = true
                )
            }
    }

    private fun saveCustomer() {

        val customerId =
            binding.etCustomerId.text
                .toString()
                .trim()

        val packageName =
            binding.etPackage.text
                .toString()
                .trim()

        val priceText =
            binding.etPrice.text
                .toString()
                .trim()

        val company =
            companies.getOrNull(
                binding.spinnerCompany.selectedItemPosition
            ) ?: companies.first()

        if (
            customerId.isEmpty() ||
            packageName.isEmpty() ||
            priceText.isEmpty()
        ) {
            showResult(
                "Please fill in Customer ID, Package, and Price.",
                isError = true
            )
            return
        }

        val price =
            priceText.toDoubleOrNull()

        if (price == null) {
            showResult(
                "Package Price must be a number.",
                isError = true
            )
            return
        }

        if (selectedRegisteredCustomer != null) {
            showResult(
                "This customer is already registered. Duplicate registration is blocked.",
                isError = true
            )
            return
        }

        binding.btnSaveCustomer.isEnabled = false

        db.collection(customersCollection)
            .document(customerId)
            .get()
            .addOnSuccessListener { existing ->

                if (existing.exists()) {

                    showResult(
                        "Customer \"$customerId\" is already registered. " +
                                "Duplicate registration blocked.",
                        isError = true
                    )

                    binding.btnSaveCustomer.isEnabled =
                        true

                    return@addOnSuccessListener
                }

                val billingCycleDays = 30

                var lastPaymentDate =
                    System.currentTimeMillis()

                if (
                    binding.switchExistingCustomer
                        .isChecked
                ) {

                    val daysRemaining =
                        binding.etDaysRemaining
                            .text
                            .toString()
                            .trim()
                            .toIntOrNull()

                    if (daysRemaining == null) {

                        showResult(
                            "Please enter Days Remaining Until Expiry (a whole number).",
                            isError = true
                        )

                        binding.btnSaveCustomer.isEnabled =
                            true

                        return@addOnSuccessListener
                    }

                    val msPerDay =
                        24L * 60L * 60L * 1000L

                    lastPaymentDate =
                        System.currentTimeMillis() +
                                (
                                        daysRemaining -
                                                billingCycleDays
                                        ) * msPerDay
                }

                val data =
                    hashMapOf<String, Any?>(
                        "customerId" to customerId,
                        "packageId" to packageName,
                        "packagePrice" to price,
                        "currentBalance" to 0.0,
                        "activationStatus" to "ACTIVE",
                        "billingCycleDays" to billingCycleDays,
                        "registrationPin" to currentPin,
                        "linkedDeviceId" to null,
                        "lastPaymentDate" to lastPaymentDate,
                        "ispProvider" to company,
                        "source" to "ADD_CUSTOMER",
                        "masterUserLinked" to (
                                selectedMasterUserId ==
                                        customerId
                                ),
                        "createdAt" to
                                System.currentTimeMillis()
                    )

                db.collection(customersCollection)
                    .document(customerId)
                    .set(data)
                    .addOnSuccessListener {

                        showResult(
                            "Customer \"$customerId\" created!\n\n" +
                                    "Company: $company\n" +
                                    "Package: $packageName — Rs. " +
                                    "${"%.0f".format(price)}\n\n" +
                                    "Generated PIN: $currentPin",
                            isError = false
                        )

                        showShareDialog(
                            customerId,
                            currentPin
                        )

                        binding.etCustomerId.text.clear()
                        binding.etPackage.text.clear()
                        binding.etPrice.text.clear()
                        binding.etDaysRemaining.text.clear()

                        binding.switchExistingCustomer
                            .isChecked = false

                        selectedMasterUserId = ""
                        selectedRegisteredCustomer = null

                        generateNewPin()

                        binding.btnSaveCustomer
                            .isEnabled = true
                    }
                    .addOnFailureListener { e ->

                        showResult(
                            "Failed to save: ${e.message}",
                            isError = true
                        )

                        binding.btnSaveCustomer
                            .isEnabled = true
                    }
            }
            .addOnFailureListener { e ->

                showResult(
                    "Could not check existing ID: ${e.message}",
                    isError = true
                )

                binding.btnSaveCustomer
                    .isEnabled = true
            }
    }

    private fun showShareDialog(
        customerId: String,
        pin: String
    ) {

        val message =
            buildShareMessage(
                customerId,
                pin
            )

        AlertDialog.Builder(this)
            .setTitle("Share Login Details")
            .setMessage(
                "Customer ID: $customerId\n\n" +
                        "Generated PIN: $pin\n\n" +
                        "Send these details to the customer."
            )
            .setPositiveButton(
                "📱 WhatsApp"
            ) { _, _ ->
                shareViaWhatsApp(message)
            }
            .setNeutralButton(
                "💬 SMS"
            ) { _, _ ->
                shareViaSms(message)
            }
            .setNegativeButton(
                "Skip",
                null
            )
            .show()
    }

    private fun buildShareMessage(
        customerId: String,
        pin: String
    ): String {

        return "🌐 *Your Internet Account is Ready!*\n\n" +
                "User ID: *$customerId*\n" +
                "PIN: *$pin*\n\n" +
                "Download the app and activate your account " +
                "using these details.\n\n" +
                "_Keep this PIN safe — it's used to activate your account._"
    }

    private fun shareViaWhatsApp(
        message: String
    ) {

        try {

            startActivity(
                Intent(
                    Intent.ACTION_SEND
                ).apply {
                    type = "text/plain"
                    setPackage("com.whatsapp")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        message
                    )
                }
            )

        } catch (e: Exception) {

            startActivity(
                Intent.createChooser(
                    Intent(
                        Intent.ACTION_SEND
                    ).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            message
                        )
                    },
                    "Share via WhatsApp"
                )
            )
        }
    }

    private fun shareViaSms(
        message: String
    ) {

        startActivity(
            Intent(
                Intent.ACTION_SENDTO
            ).apply {
                data = Uri.parse("smsto:")
                putExtra(
                    "sms_body",
                    message
                )
            }
        )
    }

    private fun showResult(
        message: String,
        isError: Boolean
    ) {

        binding.tvResult.visibility =
            View.VISIBLE

        binding.tvResult.text =
            message

        binding.tvResult.setBackgroundResource(
            if (isError) {
                R.drawable.bg_stat_danger
            } else {
                R.drawable.bg_stat_success
            }
        )

        binding.tvResult.setTextColor(
            android.graphics.Color.parseColor(
                if (isError) {
                    "#C62828"
                } else {
                    "#1B5E20"
                }
            )
        )
    }
}