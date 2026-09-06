package com.example.eboneadminpanel

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Relief / Update Package screen - Phase 1.
 *
 * This phase intentionally handles:
 *  - Company selection
 *  - Customer ID search
 *  - customers + ispMasterUsers search
 *  - exact customer selection
 *  - showing the selected customer
 *  - adding an unregistered master user to customers
 *  - live Active Relief count/list
 *
 * It does NOT start WebView automation yet.
 * Existing WebViewLoginActivity is intentionally untouched.
 */
class ReliefPackageActivity : AppCompatActivity() {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var etCompany: AutoCompleteTextView
    private lateinit var etCustomerId: AutoCompleteTextView

    private lateinit var tvSearchStatus: TextView
    private lateinit var customerCard: LinearLayout
    private lateinit var tvCustomerId: TextView
    private lateinit var tvCompany: TextView
    private lateinit var tvPackage: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvReliefStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var btnAddPermanentUser: Button
    private lateinit var btnActivateRelief: Button
    private lateinit var btnReactivate: Button
    private lateinit var tvActionResult: TextView
    private lateinit var tvActiveReliefCount: TextView
    private lateinit var activeReliefListContainer: LinearLayout
    private lateinit var reliefDaysContainer: LinearLayout
    private lateinit var reliefDaysContainer2: LinearLayout
    private lateinit var tvReliefDaysLabel: TextView

    private val companies = listOf("EBONE", "WATEEN", "ZONG")

    private var selectedCompany = ""
    private var selectedCustomerId = ""
    private var selectedCustomerData: Map<String, Any?> = emptyMap()
    private var selectedCustomerFromMasterOnly = false

    private var searchGeneration = 0
    private var customersListener: ListenerRegistration? = null

    private var selectedReliefDays = 1

    private val customerIds = mutableListOf<String>()
    private lateinit var customerAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_relief_package)

        bindViews()
        setupCompanyDropdown()
        setupCustomerSearch()
        setupReliefButtons()

        /*
         * Active relief list is read live from customers.
         * This is deliberately independent from transactions.
         */
        startActiveReliefListener()
    }

    private fun bindViews() {
        etCompany = findViewById(R.id.etCompany)
        etCustomerId = findViewById(R.id.etCustomerId)

        tvSearchStatus = findViewById(R.id.tvSearchStatus)
        customerCard = findViewById(R.id.customerCard)

        tvCustomerId = findViewById(R.id.tvCustomerId)
        tvCompany = findViewById(R.id.tvCompany)
        tvPackage = findViewById(R.id.tvPackage)
        tvStatus = findViewById(R.id.tvStatus)
        tvReliefStatus = findViewById(R.id.tvReliefStatus)
        tvCountdown = findViewById(R.id.tvCountdown)

        btnAddPermanentUser = findViewById(R.id.btnAddPermanentUser)
        btnActivateRelief = findViewById(R.id.btnActivateRelief)
        btnReactivate = findViewById(R.id.btnReactivate)

        tvActionResult = findViewById(R.id.tvActionResult)
        tvActiveReliefCount = findViewById(R.id.tvActiveReliefCount)
        activeReliefListContainer = findViewById(R.id.activeReliefListContainer)

        reliefDaysContainer = findViewById(R.id.reliefDaysContainer)
        reliefDaysContainer2 = findViewById(R.id.reliefDaysContainer2)
        tvReliefDaysLabel = findViewById(R.id.tvReliefDaysLabel)

        customerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            customerIds
        )

        etCustomerId.setAdapter(customerAdapter)
    }

    private fun setupCompanyDropdown() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            companies
        )

        etCompany.setAdapter(adapter)
        etCompany.threshold = 0

        etCompany.setOnClickListener {
            etCompany.showDropDown()
        }

        etCompany.setOnItemClickListener { _, _, position, _ ->
            val company = adapter.getItem(position).orEmpty().uppercase()
            selectCompany(company)
        }

        etCompany.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                etCompany.showDropDown()
            }
        }
    }

    private fun selectCompany(company: String) {
        selectedCompany = company.trim().uppercase()

        selectedCustomerId = ""
        selectedCustomerData = emptyMap()
        selectedCustomerFromMasterOnly = false

        customerIds.clear()
        customerAdapter.notifyDataSetChanged()

        etCustomerId.setText("")
        clearCustomerCard()

        tvSearchStatus.text = "Company selected: $selectedCompany"
    }

    private fun setupCustomerSearch() {
        etCustomerId.threshold = 2

        etCustomerId.setOnItemClickListener { _, _, position, _ ->
            val id = customerAdapter.getItem(position).orEmpty().trim()
            if (id.isNotEmpty()) {
                loadExactCustomer(id)
            }
        }

        etCustomerId.setOnEditorActionListener { _, _, _ ->
            val id = etCustomerId.text.toString().trim()
            if (id.isNotEmpty()) {
                loadExactCustomer(id)
            }
            false
        }

        etCustomerId.addTextChangedListener(object :
            android.text.TextWatcher {

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
                val query = s?.toString()?.trim().orEmpty()

                if (query.length < 2) {
                    customerIds.clear()
                    customerAdapter.notifyDataSetChanged()
                    return
                }

                if (selectedCompany.isBlank()) {
                    tvSearchStatus.text = "Please select Company first."
                    return
                }

                searchCustomers(query)
            }

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun searchCustomers(query: String) {
        val generation = ++searchGeneration

        tvSearchStatus.text = "Searching $selectedCompany..."

        /*
         * Registered customers.
         */
        db.collection("customers")
            .get()
            .addOnSuccessListener { customerSnapshot ->

                if (generation != searchGeneration) return@addOnSuccessListener

                val registered = customerSnapshot.documents
                    .filter { document ->
                        companyMatches(document, selectedCompany)
                    }
                    .mapNotNull { document ->
                        getCustomerId(document)
                    }
                    .filter { it.contains(query, ignoreCase = true) }

                /*
                 * Master users.
                 */
                db.collection("ispMasterUsers")
                    .get()
                    .addOnSuccessListener { masterSnapshot ->

                        if (generation != searchGeneration) return@addOnSuccessListener

                        val master = masterSnapshot.documents
                            .filter { document ->
                                companyMatches(document, selectedCompany)
                            }
                            .mapNotNull { document ->
                                getCustomerId(document)
                            }
                            .filter { it.contains(query, ignoreCase = true) }

                        val merged = (registered + master)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinctBy { it.uppercase() }
                            .sortedWith(
                                compareBy<String>(
                                    { !it.startsWith(query, ignoreCase = true) },
                                    { it.lowercase() }
                                )
                            )
                            .take(20)

                        customerIds.clear()
                        customerIds.addAll(merged)
                        customerAdapter.notifyDataSetChanged()

                        if (merged.isNotEmpty()) {
                            etCustomerId.showDropDown()
                            tvSearchStatus.text =
                                "${merged.size} customer(s) found."
                        } else {
                            tvSearchStatus.text =
                                "No $selectedCompany customer found."
                        }
                    }
                    .addOnFailureListener { error ->
                        if (generation != searchGeneration) return@addOnFailureListener

                        /*
                         * Registered customer results remain usable even if
                         * the master collection cannot be read.
                         */
                        val fallback = registered
                            .distinctBy { it.uppercase() }
                            .take(20)

                        customerIds.clear()
                        customerIds.addAll(fallback)
                        customerAdapter.notifyDataSetChanged()

                        tvSearchStatus.text =
                            if (fallback.isNotEmpty()) {
                                "${fallback.size} registered customer(s) found. Master list unavailable."
                            } else {
                                "Master list error: ${error.message ?: "unknown error"}"
                            }
                    }
            }
            .addOnFailureListener { error ->
                if (generation != searchGeneration) return@addOnFailureListener

                tvSearchStatus.text =
                    "Customer search failed: ${error.message ?: "unknown error"}"
            }
    }

    private fun loadExactCustomer(customerId: String) {
        val cleanId = customerId.trim()

        if (selectedCompany.isBlank()) {
            tvSearchStatus.text = "Please select Company first."
            return
        }

        if (cleanId.isEmpty()) return

        tvSearchStatus.text = "Loading $cleanId..."
        clearCustomerCard()

        /*
         * First check registered customers.
         */
        db.collection("customers")
            .get()
            .addOnSuccessListener { snapshot ->

                val registeredDocument = snapshot.documents.firstOrNull { document ->
                    companyMatches(document, selectedCompany) &&
                            getCustomerId(document).equals(
                                cleanId,
                                ignoreCase = true
                            )
                }

                if (registeredDocument != null) {
                    showSelectedCustomer(
                        registeredDocument,
                        fromMasterOnly = false
                    )
                    return@addOnSuccessListener
                }

                /*
                 * Not registered: check master list.
                 */
                db.collection("ispMasterUsers")
                    .get()
                    .addOnSuccessListener { masterSnapshot ->

                        val masterDocument = masterSnapshot.documents.firstOrNull { document ->
                            companyMatches(document, selectedCompany) &&
                                    getCustomerId(document).equals(
                                        cleanId,
                                        ignoreCase = true
                                    )
                        }

                        if (masterDocument != null) {
                            showSelectedCustomer(
                                masterDocument,
                                fromMasterOnly = true
                            )
                        } else {
                            tvSearchStatus.text =
                                "$cleanId not found in $selectedCompany."
                        }
                    }
                    .addOnFailureListener { error ->
                        tvSearchStatus.text =
                            "Master search failed: ${error.message ?: "unknown error"}"
                    }
            }
            .addOnFailureListener { error ->
                tvSearchStatus.text =
                    "Customer search failed: ${error.message ?: "unknown error"}"
            }
    }

    private fun showSelectedCustomer(
        document: DocumentSnapshot,
        fromMasterOnly: Boolean
    ) {
        selectedCustomerId = getCustomerId(document)
        selectedCustomerFromMasterOnly = fromMasterOnly
        selectedCustomerData = document.data ?: emptyMap()

        val company = getCompany(document)
            .ifBlank { selectedCompany }

        tvCustomerId.text = "Customer ID: $selectedCustomerId"
        tvCompany.text = "Company: $company"

        val packageId =
            valueAsString(document, "packageId")
                .ifBlank {
                    valueAsString(document, "package")
                }

        val packagePrice =
            valueAsString(document, "packagePrice")
                .ifBlank {
                    valueAsString(document, "price")
                }

        tvPackage.text =
            if (packagePrice.isNotBlank()) {
                "Package: ${packageId.ifBlank { "-" }}  |  Price: $packagePrice"
            } else {
                "Package: ${packageId.ifBlank { "-" }}"
            }

        tvStatus.text =
            "Status: ${valueAsString(document, "activationStatus").ifBlank { "UNKNOWN" }}"

        val reliefStatus =
            valueAsString(document, "reliefStatus")
                .ifBlank { "NONE" }

        tvReliefStatus.text = "Relief: $reliefStatus"

        val deadline = document.getTimestamp("graceDeadline")

        if (reliefStatus.equals("ACTIVE", ignoreCase = true) && deadline != null) {
            tvCountdown.text = "Deadline: ${deadline.toDate()}"
        } else {
            tvCountdown.text = "Remaining: --"
        }

        customerCard.visibility = View.VISIBLE

        if (fromMasterOnly) {
            btnAddPermanentUser.visibility = View.VISIBLE
            tvSearchStatus.text =
                "$selectedCustomerId found in master list. Not registered permanently."
        } else {
            btnAddPermanentUser.visibility = View.GONE
            tvSearchStatus.text =
                "$selectedCustomerId loaded from registered customers."
        }

        tvReliefDaysLabel.visibility = View.VISIBLE
        reliefDaysContainer.visibility = View.VISIBLE
        reliefDaysContainer2.visibility = View.VISIBLE

        val isReliefActive =
            reliefStatus.equals("ACTIVE", ignoreCase = true) ||
                    reliefStatus.equals("ACTIVE_PENDING", ignoreCase = true)

        btnActivateRelief.visibility =
            if (isReliefActive) View.GONE else View.VISIBLE

        btnReactivate.visibility =
            if (isReliefActive) View.VISIBLE else View.GONE

        btnAddPermanentUser.setOnClickListener {
            addSelectedMasterUserToCustomers()
        }

        /*
         * Phase 1 deliberately does not start WebView automation.
         * The button is prepared for the next phase.
         */
        btnActivateRelief.setOnClickListener {
            tvActionResult.text =
                "Relief selection is ready. ISP automation will be connected in the next phase."
            Toast.makeText(
                this,
                "Search/selection verified. Automation not started yet.",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnReactivate.setOnClickListener {
            tvActionResult.text =
                "Reactivation is reserved for the next phase."
            Toast.makeText(
                this,
                "Reactivation automation will be connected after the EBONE test.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupReliefButtons() {
        findViewById<Button>(R.id.btnRelief1Day).setOnClickListener {
            selectReliefDays(1)
        }

        findViewById<Button>(R.id.btnRelief2Day).setOnClickListener {
            selectReliefDays(2)
        }

        findViewById<Button>(R.id.btnRelief3Day).setOnClickListener {
            selectReliefDays(3)
        }

        findViewById<Button>(R.id.btnRelief4Day).setOnClickListener {
            selectReliefDays(4)
        }

        findViewById<Button>(R.id.btnRelief5Day).setOnClickListener {
            selectReliefDays(5)
        }

        findViewById<Button>(R.id.btnRelief6Day).setOnClickListener {
            selectReliefDays(6)
        }

        findViewById<Button>(R.id.btnRelief7Day).setOnClickListener {
            selectReliefDays(7)
        }

        findViewById<Button>(R.id.btnCustomRelief).setOnClickListener {
            Toast.makeText(
                this,
                "Custom date will be connected in the next phase.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun selectReliefDays(days: Int) {
        selectedReliefDays = days
        tvActionResult.text = "Relief duration selected: $days day(s)."
    }

    private fun addSelectedMasterUserToCustomers() {
        if (!selectedCustomerFromMasterOnly) {
            tvActionResult.text =
                "This customer is already registered."
            return
        }

        if (selectedCustomerId.isBlank()) {
            tvActionResult.text =
                "No customer selected."
            return
        }

        val data = HashMap<String, Any?>()

        data["customerId"] = selectedCustomerId
        data["ispProvider"] = selectedCompany
        data["activationStatus"] = "ACTIVE"

        val packageId =
            selectedCustomerData["packageId"]

        if (packageId != null) {
            data["packageId"] = packageId
        }

        val packagePrice =
            selectedCustomerData["packagePrice"]

        if (packagePrice != null) {
            data["packagePrice"] = packagePrice
        }

        /*
         * Copy useful master fields without copying the master document
         * ID as the customer ID when an explicit customerId exists.
         */
        selectedCustomerData.forEach { (key, value) ->
            if (
                key != "customerId" &&
                key != "ispProvider" &&
                key != "activationStatus" &&
                value != null
            ) {
                data[key] = value
            }
        }

        db.collection("customers")
            .document(selectedCustomerId)
            .set(data)
            .addOnSuccessListener {

                selectedCustomerFromMasterOnly = false

                btnAddPermanentUser.visibility = View.GONE

                tvStatus.text = "Status: ACTIVE"
                tvSearchStatus.text =
                    "$selectedCustomerId added to permanent customers."

                tvActionResult.text =
                    "Customer permanently saved successfully."

                Toast.makeText(
                    this,
                    "Customer added successfully.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .addOnFailureListener { error ->

                tvActionResult.text =
                    "Could not add customer: ${error.message ?: "unknown error"}"
            }
    }

    private fun startActiveReliefListener() {
        customersListener?.remove()

        customersListener = db.collection("customers")
            .whereEqualTo(
                "reliefStatus",
                ReliefAutomationContract.CUSTOMER_RELIEF_ACTIVE
            )
            .addSnapshotListener { snapshot, error ->

                if (error != null) {
                    tvActiveReliefCount.text = "0"
                    activeReliefListContainer.removeAllViews()

                    val errorView = TextView(this)
                    errorView.text =
                        "Could not load Active Relief list."
                    errorView.setPadding(8, 8, 8, 8)

                    activeReliefListContainer.addView(errorView)
                    return@addSnapshotListener
                }

                val documents = snapshot?.documents.orEmpty()

                val active = documents.filter { document ->
                    val deadline =
                        document.getTimestamp("graceDeadline")

                    val status =
                        valueAsString(document, "reliefStatus")

                    status.equals("ACTIVE", ignoreCase = true) &&
                            deadline != null &&
                            deadline.toDate().time > System.currentTimeMillis()
                }

                tvActiveReliefCount.text = active.size.toString()

                renderActiveReliefList(active)
            }
    }

    private fun renderActiveReliefList(
        documents: List<DocumentSnapshot>
    ) {
        activeReliefListContainer.removeAllViews()

        if (documents.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No active relief customers."
            empty.setPadding(8, 8, 8, 8)

            activeReliefListContainer.addView(empty)
            return
        }

        documents
            .sortedBy { it.getTimestamp("graceDeadline")?.toDate()?.time ?: Long.MAX_VALUE }
            .forEach { document ->

                val row = TextView(this)

                val id = getCustomerId(document)
                val company = getCompany(document)
                    .ifBlank { "-" }

                val deadline =
                    document.getTimestamp("graceDeadline")

                row.text =
                    if (deadline != null) {
                        "$id  |  $company\nDeadline: ${deadline.toDate()}"
                    } else {
                        "$id  |  $company"
                    }

                row.setPadding(12, 12, 12, 12)

                row.setOnClickListener {
                    selectedCompany = company.uppercase()
                    etCompany.setText(selectedCompany, false)
                    loadExactCustomer(id)
                }

                activeReliefListContainer.addView(row)
            }
    }

    private fun clearCustomerCard() {
        customerCard.visibility = View.GONE

        btnAddPermanentUser.visibility = View.GONE
        btnActivateRelief.visibility = View.GONE
        btnReactivate.visibility = View.GONE

        tvReliefDaysLabel.visibility = View.GONE
        reliefDaysContainer.visibility = View.GONE
        reliefDaysContainer2.visibility = View.GONE

        tvActionResult.text = ""
    }

    private fun companyMatches(
        document: DocumentSnapshot,
        company: String
    ): Boolean {
        val provider =
            valueAsString(document, "ispProvider")
                .ifBlank {
                    valueAsString(document, "company")
                }

        return provider.equals(
            company,
            ignoreCase = true
        )
    }

    private fun getCustomerId(
        document: DocumentSnapshot
    ): String {
        val explicit =
            valueAsString(document, "customerId")

        return if (explicit.isNotBlank()) {
            explicit
        } else {
            document.id
        }
    }

    private fun getCompany(
        document: DocumentSnapshot
    ): String {
        return valueAsString(document, "ispProvider")
            .ifBlank {
                valueAsString(document, "company")
            }
    }

    private fun valueAsString(
        document: DocumentSnapshot,
        field: String
    ): String {
        val value = document.get(field) ?: return ""

        return when (value) {
            is String -> value.trim()
            else -> value.toString().trim()
        }
    }

    override fun onDestroy() {
        customersListener?.remove()
        customersListener = null

        mainHandler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }
}
