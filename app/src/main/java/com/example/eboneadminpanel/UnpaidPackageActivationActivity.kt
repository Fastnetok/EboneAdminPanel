package com.example.eboneadminpanel



import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class UnpaidPackageActivationActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val companies = listOf("EBONE", "WATEEN", "ZONG")

    private lateinit var spinnerCompany: Spinner
    private lateinit var search: AutoCompleteTextView
    private lateinit var customerUrl: EditText
    private lateinit var tvCustomer: TextView
    private lateinit var tvCustomerId: TextView
    private lateinit var tvPackage: TextView
    private lateinit var tvPackagePrice: TextView
    private lateinit var tvStartDate: TextView
    private lateinit var tvSuspendDate: TextView
    private lateinit var quickDaysContainer: LinearLayout
    private lateinit var btnCalendar: ImageButton
    private lateinit var btnQuickTest: ImageButton
    private lateinit var btnTestDisableNow: Button
    private lateinit var btnTestEnableNow: Button
    private lateinit var btnActivate: Button
    private lateinit var btnReactivate: Button
    private lateinit var btnSaveNewUser: Button
    private lateinit var btnReliefActiveList: LinearLayout
    private lateinit var tvReliefActiveCount: TextView
    private lateinit var btnDisabledUsersList: LinearLayout
    private lateinit var tvDisabledUsersCount: TextView
    private var disabledUsersListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private var reliefListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    private val customers = mutableListOf<DocumentSnapshot>()
    private val masterUsers = mutableListOf<DocumentSnapshot>()
    private var selectedCustomer: DocumentSnapshot? = null
    private var selectedCustomerIsMasterOnly = false
    private var selectedDealerName: String? = null

    private val dateFormat =
        SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())

    private val timeFormat =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var selectedReliefDays = 2
    private var selectedSuspendAt = 0L

    // NEW: true whenever the currently-selected suspend deadline was set
    // via the minutes-based Quick Test button rather than a normal
    // day/date pick. Read (and reset) inside activateOnRelief() so the
    // saved Firestore record is clearly marked reliefTestMode = true —
    // exactly like the existing btnTestDisableNow/testDisableNow() TEST
    // marking already used for the Disabled Users list.
    private var isQuickTestPending = false

    // FIX: whenever showCustomer(doc) is called for a customer we ALREADY
    // KNOW exists (from a search-suggestion click, or from tapping a row
    // in the Relief Active / Disabled Users list via jumpToCustomer), it
    // calls search.setText(id, false) to display that ID in the search
    // box. AutoCompleteTextView's TextWatcher fires SYNCHRONOUSLY inside
    // that setText() call — which re-runs updateSaveNewUserButtonVisibility()
    // as if the admin had just typed that ID by hand. If the in-memory
    // "customers" list for the currently-selected company had not yet
    // been (re)loaded from Firestore at that exact moment (e.g. right
    // after jumpToCustomer switches the ISP spinner, which kicks off an
    // async reload), that check would wrongly conclude the ID is
    // "genuinely new", overwrite selectedCustomerIsMasterOnly to true,
    // and pop the "Save New User" button back up for a customer that was
    // already saved. This flag suppresses that specific re-check only
    // for setText() calls we make ourselves — real keystrokes from the
    // admin are never suppressed.
    private var suppressSearchWatcher = false

    // Name of the dealer account (managed via WebViewLoginActivity's
    // existing multi-account switcher, saved under the "ebill_accounts"
    // store) that the URL field should always auto-login as. Only one
    // account is used here — Akmal — regardless of which customer is
    // being searched.
    // NEW: single shared dealer name used for EVERY EBONE action in this
    // screen — the URL button, Activate on Relief, and Reactivate all
    // force this exact dealer, never the Franchise account.
    private val RELIEF_DEALER_NAME = "Akmal"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unpaid_package_activation)

        spinnerCompany = findViewById(R.id.spinnerCompany)
        search = findViewById(R.id.etCustomerSearch)
        customerUrl = findViewById(R.id.etCustomerUrl)
        tvCustomer = findViewById(R.id.tvCustomer)
        tvCustomerId = findViewById(R.id.tvCustomerId)
        tvPackage = findViewById(R.id.tvPackage)
        tvPackagePrice = findViewById(R.id.tvPackagePrice)
        tvStartDate = findViewById(R.id.tvStartDate)
        tvSuspendDate = findViewById(R.id.tvSuspendDate)
        quickDaysContainer = findViewById(R.id.quickDaysContainer)
        btnCalendar = findViewById(R.id.btnCalendar)
        btnTestDisableNow = findViewById(R.id.btnTestDisableNow)
        btnTestEnableNow = findViewById(R.id.btnTestEnableNow)

        btnTestDisableNow.setOnClickListener { testDisableNow() }
        btnTestEnableNow.setOnClickListener { testEnableNow() }
        btnActivate = findViewById(R.id.btnActivateRelief)
        btnReactivate = findViewById(R.id.btnReactivate)
        btnSaveNewUser = findViewById(R.id.btnSaveNewUser)
        btnReliefActiveList = findViewById(R.id.btnReliefActiveList)
        tvReliefActiveCount = findViewById(R.id.tvReliefActiveCount)
        btnDisabledUsersList = findViewById(R.id.btnDisabledUsersList)
        tvDisabledUsersCount = findViewById(R.id.tvDisabledUsersCount)

        // NEW: small round clock-icon button placed right next to the
        // existing calendar button. Added purely in code — the XML
        // layout file is not touched at all. Lets the admin set the
        // relief suspend deadline to "now + a few minutes" instead of
        // days, so the real automatic pipeline (EboneAdminApp's
        // foreground auto-disable checker + WebViewLoginActivity's
        // SUSPEND/ENABLE flow) can be watched end-to-end in minutes
        // instead of waiting 1-7 days for a real cycle to complete.
        btnQuickTest = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_recent_history)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFF3E0"))
            }
            setColorFilter(Color.parseColor("#E65100"))
            contentDescription = "Quick test relief (minutes)"
            val sizePx = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).also {
                it.marginStart = (8 * resources.displayMetrics.density).toInt()
            }
            setPadding(10, 10, 10, 10)
        }

        (btnCalendar.parent as? ViewGroup)?.let { parent ->
            val idx = parent.indexOfChild(btnCalendar)
            parent.addView(btnQuickTest, idx + 1)
        }

        btnQuickTest.setOnClickListener {
            showQuickTestMinutesDialog()
        }

        btnSaveNewUser.setOnClickListener {
            saveSelectedNewUserToUnpaidRelief()
        }

        // Live count + tap-to-open list of everyone currently on
        // Relief. A customer drops off this list the instant their
        // reliefStatus field is cleared — whether via early payment
        // (Re-activate) or the automatic suspend flow (WebViewLoginActivity
        // now sets reliefStatus="SUSPENDED", which this query excludes).
        btnReliefActiveList.setOnClickListener { showReliefActiveListDialog() }
        startReliefActiveListener()

        // Separate live count + list of everyone CURRENTLY
        // DISABLED (reliefStatus == "SUSPENDED") — this is what was
        // missing: a SUSPEND completing (from DISABLE NOW, the real
        // relief cycle, or the foreground auto-disable checker) moves a
        // customer OUT of Relief Active and into this list instead. They
        // drop out of this one automatically the instant they're
        // re-enabled.
        btnDisabledUsersList.setOnClickListener { showDisabledUsersListDialog() }
        startDisabledUsersListener()

        // Pressing the URL field opens WebViewLoginActivity, logs in
        // using the "Akmal" dealer account specifically (never any other
        // saved account), lands on the all-customers list, and stops
        // there — no further automatic navigation.
        customerUrl.setOnClickListener {
            launchAkmalDealerAllUsersList()
        }

        findViewById<ImageButton>(R.id.btnBack).apply {
            // NEW: matches the U-turn back icon used in SMS Match
            // Settings — set here in code so the XML layout file (which
            // still declares @drawable/ic_arrow_back as a fallback) is
            // never touched.
            setImageResource(android.R.drawable.ic_menu_revert)
            setColorFilter(Color.parseColor("#0D2E5C"))
            setOnClickListener {
                finish()
            }
        }

        // NEW: menu button, added purely in code (no XML file touched)
        // — placed in the same header row as the back button, on its
        // right side. Opens the new standalone Relief Log screen.
        run {
            val backButton = findViewById<ImageButton>(R.id.btnBack)
            val headerRow = backButton.parent as? LinearLayout
            if (headerRow != null) {
                val menuButton = ImageButton(this).apply {
                    setImageResource(android.R.drawable.ic_menu_sort_by_size)
                    background = ContextCompat.getDrawable(
                        this@UnpaidPackageActivationActivity,
                        android.R.color.transparent
                    )
                    setColorFilter(Color.parseColor("#0D2E5C"))
                    contentDescription = "Relief Log"
                    layoutParams = LinearLayout.LayoutParams(
                        (42 * resources.displayMetrics.density).toInt(),
                        (42 * resources.displayMetrics.density).toInt()
                    )
                    setPadding(20, 20, 20, 20)
                    setOnClickListener {
                        startActivity(
                            Intent(
                                this@UnpaidPackageActivationActivity,
                                ReliefLogActivity::class.java
                            )
                        )
                    }
                }
                // Replaces the existing invisible "Space" spacer (kept
                // for symmetry) — added right after it so the header
                // stays: [Back] [Title] [Menu].
                headerRow.addView(menuButton)
            }
        }

        spinnerCompany.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            companies
        )

        spinnerCompany.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    selectedCustomer = null
                    clearCustomerDetails()
                    updateCompanyUrl(companies[position])
                    loadCustomersForCompany(companies[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        /*
         * IMPORTANT:
         * Do NOT use customers[position] here.
         *
         * AutoCompleteTextView filters its ArrayAdapter, so the visible
         * dropdown position is NOT guaranteed to be the same position in
         * the original customers list. That was the reason OLT could select
         * Abbas.
         *
         * We now read the actual clicked label and resolve the exact
         * customer ID from that label.
         */
        search.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position)?.toString().orEmpty()
            selectCustomerFromSuggestion(label)
        }

        search.setOnClickListener {
            if (search.adapter != null) {
                search.showDropDown()
            }
        }

        search.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && search.adapter != null) {
                search.showDropDown()
            }
        }

        // FIX: this is what makes "Save New User" appear for a brand-new
        // ID that has NO dropdown suggestion to click at all (nothing
        // was watching text changes before, so the button only ever
        // appeared when an existing master-list suggestion was clicked).
        search.doAfterTextChanged { editable ->
            if (suppressSearchWatcher) return@doAfterTextChanged
            val typed = editable?.toString()?.trim().orEmpty()
            updateSaveNewUserButtonVisibility(typed)
        }

        btnCalendar.setOnClickListener {
            showCustomDatePicker()
        }

        btnActivate.setOnClickListener {
            activateOnRelief()
        }

        btnReactivate.setOnClickListener {
            reactivateAfterPayment()
        }

        buildQuickDayButtons()
        // FIX: previously this called setReliefDays(2), which
        // auto-checked the "2" day checkbox by default — even though the
        // admin had not tapped anything. Any screen reset (loading the
        // screen, switching ISP company, clearing the selected customer)
        // should leave every 1-7 day checkbox unchecked; a checkbox
        // should only ever show as checked because the admin tapped it.
        clearReliefDaySelection()
        loadCustomersForCompany(companies.first())
    }

    private fun buildQuickDayButtons() {
        quickDaysContainer.removeAllViews()

        for (day in 1..7) {
            lateinit var checkBoxRef: CheckBox

            val checkBox = CheckBox(this).apply {
                text = day.toString()
                textSize = 14f
                setTextColor(Color.parseColor("#0D2E5C"))
                gravity = Gravity.CENTER
                minWidth = 44
                minHeight = 44
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#0D2E5C")
                )

                setOnClickListener {
                    if (checkBoxRef.isChecked) {
                        setReliefDays(day)
                    } else {
                        clearReliefDaySelection()
                    }
                }
            }

            checkBoxRef = checkBox
            quickDaysContainer.addView(checkBox)
        }
    }

    private fun clearReliefDaySelection() {
        selectedReliefDays = 0
        selectedSuspendAt = 0L
        isQuickTestPending = false

        tvStartDate.text = "Start Date: —"
        tvSuspendDate.text = "Suspend Date: —"

        for (i in 0 until quickDaysContainer.childCount) {
            (quickDaysContainer.getChildAt(i) as? CheckBox)?.isChecked = false
        }
    }

    private fun setReliefDays(days: Int) {
        selectedReliefDays = days
        isQuickTestPending = false

        val start = Calendar.getInstance()
        val suspend = start.clone() as Calendar
        suspend.add(Calendar.DAY_OF_YEAR, days)

        selectedSuspendAt = suspend.timeInMillis

        tvStartDate.text =
            "Start Date: ${dateFormat.format(start.time)}"
        tvSuspendDate.text =
            "Suspend Date: ${dateFormat.format(suspend.time)}"

        for (i in 0 until quickDaysContainer.childCount) {
            val checkBox =
                quickDaysContainer.getChildAt(i) as? CheckBox ?: continue
            checkBox.isChecked = (i + 1 == days)
        }
    }

    private fun showQuickTestMinutesDialog() {
        val doc = selectedCustomer
        if (doc == null || selectedCustomerIsMasterOnly) {
            Toast.makeText(
                this,
                "Please select an existing customer first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val minuteOptions = listOf(1, 2, 5)
        val labels = minuteOptions.map { "$it minute(s) — quick test" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Quick Test Deadline")
            .setItems(labels) { _, which ->
                setQuickTestDeadline(minuteOptions[which])
            }
            .show()
    }

    private fun setQuickTestDeadline(minutes: Int) {
        selectedReliefDays = 0

        val suspend = Calendar.getInstance().apply {
            add(Calendar.MINUTE, minutes)
        }
        selectedSuspendAt = suspend.timeInMillis
        isQuickTestPending = true

        for (i in 0 until quickDaysContainer.childCount) {
            (quickDaysContainer.getChildAt(i) as? CheckBox)?.isChecked = false
        }

        tvStartDate.text = "Start: ${timeFormat.format(Calendar.getInstance().time)} (TEST)"
        tvSuspendDate.text = "Suspend: ${timeFormat.format(suspend.time)} (in $minutes min)"

        Toast.makeText(
            this,
            "Test deadline: $minutes minute(s). Press \"Activate on Relief\" now. " +
                    "Keep the app OPEN (foreground) to see the auto-disable fire.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun testDisableNow() {
        val doc = selectedCustomer
        if (doc == null || selectedCustomerIsMasterOnly) {
            Toast.makeText(
                this,
                "Please select an existing customer first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val customerId = doc.getString("customerId") ?: doc.id
        val company =
            spinnerCompany.selectedItem?.toString() ?: "EBONE"

        db.collection("customers").document(customerId)
            .update(
                mapOf(
                    "reliefCompany" to company.uppercase(Locale.getDefault()),
                    "reliefTestMode" to true
                )
            )

        val intent = Intent(this, WebViewLoginActivity::class.java).apply {
            putExtra("selected_isp", company)
            putExtra("auto_activate_customer_id", customerId)
            putExtra("customer_url", customerUrl.text.toString().trim().ifBlank { companyUrlFor(company) })
            putExtra("use_dealer_account", company.equals("EBONE", true))
            if (company.equals("EBONE", true)) {
                putExtra("dealer_account_name", RELIEF_DEALER_NAME)
            }
            putExtra("manual_action", "SUSPEND")
            putExtra("target_zone", "Okara")
        }

        Toast.makeText(
            this,
            "Disabling $customerId now…",
            Toast.LENGTH_SHORT
        ).show()

        startActivity(intent)
    }

    private fun testEnableNow() {
        val doc = selectedCustomer
        if (doc == null || selectedCustomerIsMasterOnly) {
            Toast.makeText(
                this,
                "Please select an existing customer first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val customerId = doc.getString("customerId") ?: doc.id
        val company =
            spinnerCompany.selectedItem?.toString() ?: "EBONE"

        val intent = Intent(this, WebViewLoginActivity::class.java).apply {
            putExtra("selected_isp", company)
            putExtra("auto_activate_customer_id", customerId)
            putExtra("customer_url", customerUrl.text.toString().trim().ifBlank { companyUrlFor(company) })
            putExtra("use_dealer_account", company.equals("EBONE", true))
            if (company.equals("EBONE", true)) {
                putExtra("dealer_account_name", RELIEF_DEALER_NAME)
            }
            putExtra("manual_action", "ENABLE")
            putExtra("target_zone", "Okara")
        }

        Toast.makeText(
            this,
            "Enabling $customerId now…",
            Toast.LENGTH_SHORT
        ).show()

        startActivity(intent)
    }

    private fun showCustomDatePicker() {
        val now = Calendar.getInstance()

        val picker = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }

                val start = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                if (selected.timeInMillis <= start.timeInMillis) {
                    Toast.makeText(
                        this,
                        "Suspend date must be after today.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@DatePickerDialog
                }

                isQuickTestPending = false
                selectedSuspendAt = selected.timeInMillis

                val diffDays =
                    ((selected.timeInMillis - start.timeInMillis) /
                            (24L * 60L * 60L * 1000L)).toInt()

                selectedReliefDays = diffDays.coerceAtLeast(1)

                tvStartDate.text =
                    "Start Date: ${dateFormat.format(start.time)}"
                tvSuspendDate.text =
                    "Suspend Date: ${dateFormat.format(selected.time)}"

                for (i in 0 until quickDaysContainer.childCount) {
                    val checkBox =
                        quickDaysContainer.getChildAt(i) as? CheckBox ?: continue
                    checkBox.isChecked = (i + 1 == selectedReliefDays)
                }
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        )

        picker.datePicker.minDate = now.timeInMillis + 24L * 60L * 60L * 1000L
        picker.show()
    }

    private fun loadCustomersForCompany(company: String) {
        val wantedCompany = company.trim().uppercase(Locale.getDefault())

        val customersTask = db.collection("customers").get()
        val masterTask = db.collection("ispMasterUsers").get()

        com.google.android.gms.tasks.Tasks.whenAllSuccess<Any>(
            customersTask, masterTask
        ).addOnSuccessListener { results ->
            customers.clear()
            masterUsers.clear()

            val customerSnapshot =
                results.getOrNull(0) as? com.google.firebase.firestore.QuerySnapshot
            val masterSnapshot =
                results.getOrNull(1) as? com.google.firebase.firestore.QuerySnapshot

            customerSnapshot?.documents?.forEach { doc ->
                val provider = (doc.getString("ispProvider") ?: "")
                    .trim().uppercase(Locale.getDefault())
                if (provider == wantedCompany || provider.isBlank() && wantedCompany == "EBONE") {
                    customers.add(doc)
                }
            }

            masterSnapshot?.documents?.forEach { doc ->
                val provider = getMasterCompany(doc)
                if (provider == wantedCompany || provider.isBlank() && wantedCompany == "EBONE") {
                    masterUsers.add(doc)
                }
            }

            rebuildSearchAdapter()
        }.addOnFailureListener { error ->
            Toast.makeText(
                this,
                "User list load failed: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun rebuildSearchAdapter() {
        val labels = mutableListOf<String>()
        val seen = HashSet<String>()

        customers.forEach { doc ->
            val id = doc.getString("customerId") ?: doc.id
            val key = id.trim().uppercase(Locale.getDefault())

            if (key.isNotEmpty() && seen.add(key)) {
                val packageId = doc.getString("packageId") ?: ""
                labels.add(
                    if (packageId.isBlank()) id
                    else "$id · $packageId"
                )
            }
        }

        masterUsers.forEach { doc ->
            val id = getMasterCustomerId(doc)
            val key = id.trim().uppercase(Locale.getDefault())

            if (key.isNotEmpty() && seen.add(key)) {
                val packageId =
                    doc.getString("packageId")
                        ?: doc.getString("package")
                        ?: ""

                labels.add(
                    if (packageId.isBlank()) id
                    else "$id · $packageId"
                )
            }
        }

        labels.sortWith(
            compareBy(
                { it.substringBefore(" · ").lowercase(Locale.getDefault()) },
                { it.lowercase(Locale.getDefault()) }
            )
        )

        search.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                labels
            )
        )

        search.threshold = 1
    }

    private fun selectCustomerFromSuggestion(label: String) {
        val clickedId = label
            .substringBefore(" · ")
            .trim()

        val doc = customers.firstOrNull {
            val id = it.getString("customerId") ?: it.id
            id.equals(clickedId, ignoreCase = true)
        }

        if (doc != null) {
            selectedCustomer = doc
            selectedCustomerIsMasterOnly = false
            selectedDealerName = doc.getString("dealerName")?.trim().takeIf { !it.isNullOrEmpty() }
            btnSaveNewUser.visibility = android.view.View.GONE
            showCustomer(doc)
            return
        }

        val masterDoc = masterUsers.firstOrNull {
            getMasterCustomerId(it).equals(
                clickedId,
                ignoreCase = true
            )
        }

        if (masterDoc != null) {
            selectedCustomer = masterDoc
            selectedCustomerIsMasterOnly = true
            selectedDealerName = masterDoc.getString("dealerName")?.trim().takeIf { !it.isNullOrEmpty() }
            btnSaveNewUser.visibility = android.view.View.VISIBLE
            showCustomer(masterDoc)

            Toast.makeText(
                this,
                "New user found. Press SAVE NEW USER first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        Toast.makeText(
            this,
            "Selected customer could not be resolved.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateSaveNewUserButtonVisibility(typedText: String) {
        if (typedText.isEmpty()) {
            btnSaveNewUser.visibility = android.view.View.GONE
            return
        }

        val matchesExistingCustomer = customers.any {
            val id = it.getString("customerId") ?: it.id
            id.equals(typedText, ignoreCase = true)
        }

        if (matchesExistingCustomer) {
            btnSaveNewUser.visibility = android.view.View.GONE
            return
        }

        val matchingMaster = masterUsers.firstOrNull {
            getMasterCustomerId(it).equals(typedText, ignoreCase = true)
        }

        if (matchingMaster != null) {
            selectedCustomer = matchingMaster
            selectedCustomerIsMasterOnly = true
            selectedDealerName =
                matchingMaster.getString("dealerName")?.trim()
                    .takeIf { !it.isNullOrEmpty() }
            btnSaveNewUser.visibility = android.view.View.VISIBLE
            return
        }

        selectedCustomer = null
        selectedCustomerIsMasterOnly = true
        btnSaveNewUser.visibility = android.view.View.VISIBLE
    }

    private fun companyUrlFor(company: String): String = when (
        company.trim().uppercase(Locale.getDefault())
    ) {
        "WATEEN" -> "https://panel.wateen.com"
        "ZONG" -> "https://turbonet.zong.com.pk"
        else -> "https://partner.ebill.pk"
    }

    private fun updateCompanyUrl(company: String) {
        customerUrl.setText(companyUrlFor(company))
    }

    private fun launchAkmalDealerAllUsersList() {
        val company =
            spinnerCompany.selectedItem?.toString()
                ?.trim()
                ?.uppercase(Locale.getDefault())
                ?: "EBONE"

        val intent = Intent(this, WebViewLoginActivity::class.java).apply {
            putExtra("selected_isp", company)
            putExtra("use_dealer_account", true)
            putExtra("dealer_account_name", RELIEF_DEALER_NAME)
            putExtra("target_zone", "Okara")
        }

        startActivity(intent)
    }

    private fun showCustomer(doc: DocumentSnapshot) {
        val id = doc.getString("customerId") ?: doc.id
        val packageId = doc.getString("packageId") ?: "—"
        val price =
            (doc.get("packagePrice") as? Number)?.toDouble() ?: 0.0

        tvCustomer.text = "Customer: $id"
        tvCustomerId.text = "Customer ID: $id"
        tvPackage.text = "Package: $packageId"
        tvPackagePrice.text =
            "Package Price: Rs ${"%.0f".format(price)}"

        val explicitUrl = doc.getString("customerUrl")
            ?: doc.getString("url")
            ?: ""
        customerUrl.setText(
            explicitUrl.ifBlank {
                companyUrlFor(
                    spinnerCompany.selectedItem?.toString().orEmpty()
                )
            }
        )

        suppressSearchWatcher = true
        search.setText(id, false)
        suppressSearchWatcher = false

        val reliefStatus =
            doc.getString("reliefStatus")
                ?.uppercase(Locale.getDefault())
                .orEmpty()

        val isReliefCustomer =
            reliefStatus == "ACTIVE" ||
                    reliefStatus == "EXPIRED" ||
                    reliefStatus == "SUSPENDED"

        btnReactivate.visibility =
            if (!selectedCustomerIsMasterOnly && isReliefCustomer)
                android.view.View.VISIBLE
            else
                android.view.View.GONE

        btnSaveNewUser.visibility =
            if (selectedCustomerIsMasterOnly)
                android.view.View.VISIBLE
            else
                android.view.View.GONE
    }

    private fun clearCustomerDetails() {
        search.setText("", false)
        updateCompanyUrl(
            spinnerCompany.selectedItem?.toString()?.trim()?.uppercase(Locale.getDefault()) ?: "EBONE"
        )
        selectedCustomerIsMasterOnly = false
        selectedDealerName = null
        isQuickTestPending = false
        btnSaveNewUser.visibility = android.view.View.GONE
        tvCustomer.text = "Customer: —"
        tvCustomerId.text = "Customer ID: —"
        tvPackage.text = "Package: —"
        tvPackagePrice.text = "Package Price: Rs 0"
        btnReactivate.visibility = android.view.View.GONE
        clearReliefDaySelection()
    }

    private fun activateOnRelief() {
        if (selectedCustomerIsMasterOnly) {
            Toast.makeText(
                this,
                "Please save this new user first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val doc = selectedCustomer
        if (doc == null) {
            Toast.makeText(
                this,
                "Please select a customer first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val customerId = doc.getString("customerId") ?: doc.id
        val company =
            spinnerCompany.selectedItem?.toString() ?: "EBONE"
        val packageId =
            doc.getString("packageId") ?: ""
        val packagePrice =
            (doc.get("packagePrice") as? Number)?.toDouble() ?: 0.0

        if (selectedSuspendAt <= System.currentTimeMillis()) {
            Toast.makeText(
                this,
                "Please select a valid future suspend date.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val startAt = System.currentTimeMillis()

        val isTestRun = isQuickTestPending
        isQuickTestPending = false

        val updates = mutableMapOf<String, Any>(
            "reliefStatus" to "ACTIVE_PENDING",
            "reliefCompany" to company,
            "reliefDays" to selectedReliefDays,
            "reliefStartAt" to startAt,
            "graceDeadline" to selectedSuspendAt,
            "reliefPackageId" to packageId,
            "reliefPackagePrice" to packagePrice,
            "reliefCreatedAt" to startAt
        )

        if (isTestRun) {
            updates["reliefTestMode"] = true
        } else {
            updates["reliefTestMode"] = FieldValue.delete()
        }

        btnActivate.isEnabled = false

        db.collection("customers")
            .document(customerId)
            .update(updates)
            .addOnSuccessListener {
                val intent = Intent(
                    this,
                    WebViewLoginActivity::class.java
                ).apply {
                    putExtra("selected_isp", company)
                    putExtra("auto_activate_customer_id", customerId)
                    putExtra("customer_url", customerUrl.text.toString().trim().ifBlank { companyUrlFor(company) })
                    putExtra("use_dealer_account", company.equals("EBONE", true))
                    if (company.equals("EBONE", true)) {
                        putExtra("dealer_account_name", RELIEF_DEALER_NAME)
                    }
                    putExtra("manual_action", "RELIEF_VIEW")
                    putExtra("target_zone", "Okara")
                }

                startActivityForResult(
                    intent,
                    REQUEST_RELIEF_ACTIVATION
                )
            }
            .addOnFailureListener { e ->
                btnActivate.isEnabled = true
                Toast.makeText(
                    this,
                    "Relief save failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun reactivateAfterPayment() {
        if (selectedCustomerIsMasterOnly) {
            Toast.makeText(
                this,
                "Please save this new user first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val doc = selectedCustomer
        if (doc == null) {
            Toast.makeText(
                this,
                "Please select the customer first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val customerId = doc.getString("customerId") ?: doc.id
        val company =
            spinnerCompany.selectedItem?.toString() ?: "EBONE"

        val currentReliefStatus =
            doc.getString("reliefStatus")?.uppercase(Locale.getDefault()).orEmpty()

        if (currentReliefStatus != "SUSPENDED") {
            btnReactivate.isEnabled = false

            db.collection("customers")
                .document(customerId)
                .update(
                    mapOf(
                        "reliefStatus" to FieldValue.delete(),
                        "graceDeadline" to FieldValue.delete(),
                        "reliefClearedAt" to System.currentTimeMillis(),
                        "reliefTestMode" to FieldValue.delete(),
                        "reliefCompany" to FieldValue.delete()
                    )
                )
                .addOnSuccessListener {
                    btnReactivate.isEnabled = true
                    btnReactivate.visibility = android.view.View.GONE

                    Toast.makeText(
                        this,
                        "Payment received early — customer continues normally ✅",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .addOnFailureListener { e ->
                    btnReactivate.isEnabled = true
                    Toast.makeText(
                        this,
                        "Could not clear relief status: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            return
        }

        btnReactivate.isEnabled = false

        val intent = Intent(
            this,
            WebViewLoginActivity::class.java
        ).apply {
            putExtra("selected_isp", company)
            putExtra("auto_activate_customer_id", customerId)
            putExtra("customer_url", customerUrl.text.toString().trim().ifBlank { companyUrlFor(company) })
            putExtra("use_dealer_account", company.equals("EBONE", true))
            if (company.equals("EBONE", true)) {
                putExtra("dealer_account_name", RELIEF_DEALER_NAME)
            }
            putExtra("manual_action", "ENABLE")
            putExtra("target_zone", "Okara")
        }

        startActivityForResult(
            intent,
            REQUEST_RELIEF_REACTIVATION
        )
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        val doc = selectedCustomer
        val customerId =
            doc?.getString("customerId") ?: doc?.id ?: return

        if (requestCode == REQUEST_RELIEF_ACTIVATION) {
            val success =
                data?.getBooleanExtra(
                    "activation_success",
                    false
                ) == true

            if (resultCode == Activity.RESULT_OK && success) {
                db.collection("customers")
                    .document(customerId)
                    .update(
                        mapOf(
                            "reliefStatus" to "ACTIVE",
                            "activationStatus" to "ACTIVE"
                        )
                    )
                    .addOnSuccessListener {
                        btnActivate.isEnabled = true
                        btnReactivate.visibility =
                            android.view.View.VISIBLE

                        val loggedCompany =
                            doc?.getString("ispProvider")
                                ?: spinnerCompany.selectedItem?.toString()
                                ?: "EBONE"
                        ReliefLogRepository.logActivation(
                            customerId = customerId,
                            company = loggedCompany,
                            reliefDays = selectedReliefDays,
                            expectedExpiryAt = selectedSuspendAt,
                            isTest = isQuickTestPending
                        )

                        Toast.makeText(
                            this,
                            "Customer activated on relief ✅",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .addOnFailureListener {
                        btnActivate.isEnabled = true
                    }
            } else {
                db.collection("customers")
                    .document(customerId)
                    .update(
                        "reliefStatus",
                        "FAILED"
                    )

                btnActivate.isEnabled = true

                Toast.makeText(
                    this,
                    "Activation was not confirmed.",
                    Toast.LENGTH_LONG
                ).show()
            }

            return
        }

        if (requestCode == REQUEST_RELIEF_REACTIVATION) {
            val success =
                data?.getBooleanExtra(
                    "manual_action_success",
                    false
                ) == true

            if (resultCode == Activity.RESULT_OK && success) {
                db.collection("customers")
                    .document(customerId)
                    .update(
                        mapOf(
                            "reliefStatus" to FieldValue.delete(),
                            "reliefClearedAt" to System.currentTimeMillis(),
                            "graceDeadline" to FieldValue.delete(),
                            "reliefTestMode" to FieldValue.delete(),
                            "reliefCompany" to FieldValue.delete()
                        )
                    )
                    .addOnSuccessListener {
                        btnReactivate.isEnabled = true
                        btnReactivate.visibility =
                            android.view.View.GONE

                        Toast.makeText(
                            this,
                            "Payment cleared — customer re-activated ✅",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .addOnFailureListener {
                        btnReactivate.isEnabled = true
                    }
            } else {
                btnReactivate.isEnabled = true

                Toast.makeText(
                    this,
                    "Re-activation was not confirmed.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveSelectedNewUserToUnpaidRelief() {
        val typedId = search.text.toString().trim()

        if (typedId.isBlank()) {
            Toast.makeText(this, "Please type a Customer ID first.", Toast.LENGTH_SHORT).show()
            return
        }

        val company = spinnerCompany.selectedItem?.toString()?.trim()?.uppercase(Locale.getDefault()) ?: return

        val alreadyExists = customers.any {
            val id = it.getString("customerId") ?: it.id
            id.equals(typedId, ignoreCase = true)
        }

        if (alreadyExists) {
            Toast.makeText(this, "\"$typedId\" already exists — nothing new to save.", Toast.LENGTH_LONG).show()
            btnSaveNewUser.visibility = android.view.View.GONE
            return
        }

        val masterDoc = selectedCustomer

        val data = HashMap<String, Any?>()
        masterDoc?.data?.forEach { (key, value) -> if (value != null) data[key] = value }

        data["customerId"] = typedId
        data["ispProvider"] = company
        val existingActivation = masterDoc?.getString("activationStatus")?.trim().orEmpty()
        data["activationStatus"] = if (existingActivation.isNotBlank()) existingActivation else "ACTIVE"
        val url = customerUrl.text.toString().trim().ifBlank { companyUrlFor(company) }
        data["customerUrl"] = url
        selectedDealerName?.takeIf { it.isNotBlank() }?.let { data["dealerName"] = it }
        if (!data.containsKey("createdAt")) data["createdAt"] = System.currentTimeMillis()

        btnSaveNewUser.isEnabled = false

        db.collection("customers").document(typedId).set(data)
            .addOnSuccessListener {
                selectedCustomerIsMasterOnly = false
                btnSaveNewUser.visibility = android.view.View.GONE
                btnSaveNewUser.isEnabled = true
                db.collection("customers").document(typedId).get()
                    .addOnSuccessListener { savedDoc ->
                        selectedCustomer = savedDoc
                        showCustomer(savedDoc)
                        Toast.makeText(this, "New user saved in Firebase customers.", Toast.LENGTH_LONG).show()
                        loadCustomersForCompany(company)
                    }
            }
            .addOnFailureListener { error ->
                btnSaveNewUser.isEnabled = true
                Toast.makeText(this, "Could not save new user: ${error.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun getMasterCustomerId(
        document: DocumentSnapshot
    ): String {
        val customerId =
            document.getString("customerId")
                ?.trim()
                .orEmpty()

        if (customerId.isNotEmpty()) {
            return customerId
        }

        val userId =
            document.getString("userId")
                ?.trim()
                .orEmpty()

        if (userId.isNotEmpty()) {
            return userId
        }

        return document.id.trim()
    }

    private fun getMasterCompany(document: DocumentSnapshot): String {
        val provider = document.getString("ispProvider")?.trim().orEmpty()
        if (provider.isNotEmpty()) return provider.uppercase(Locale.getDefault())

        return document.getString("company")
            ?.trim()
            ?.uppercase(Locale.getDefault())
            .orEmpty()
    }

    private fun startReliefActiveListener() {
        reliefListenerRegistration?.remove()
        reliefListenerRegistration = db.collection("customers")
            .whereEqualTo("reliefStatus", "ACTIVE")
            .addSnapshotListener { snapshot, _ ->
                val count = snapshot?.documents?.size ?: 0
                tvReliefActiveCount.text = "Relief Active Users ($count)"
            }
    }

    private fun jumpToCustomer(customerId: String, company: String) {
        val idx = companies.indexOfFirst {
            it.equals(company, ignoreCase = true)
        }
        if (idx >= 0 && spinnerCompany.selectedItemPosition != idx) {
            spinnerCompany.setSelection(idx)
        }

        db.collection("customers").document(customerId).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    selectedCustomer = doc
                    selectedCustomerIsMasterOnly = false
                    showCustomer(doc)
                } else {
                    Toast.makeText(
                        this,
                        "Could not find \"$customerId\" — it may have just changed.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Could not load \"$customerId\": ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun showReliefActiveListDialog() {
        db.collection("customers")
            .whereEqualTo("reliefStatus", "ACTIVE")
            .get()
            .addOnSuccessListener { snapshot ->

                if (snapshot.isEmpty) {
                    AlertDialog.Builder(this)
                        .setTitle("Relief Active Users")
                        .setMessage("No one is currently on relief.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@addOnSuccessListener
                }

                val scrollView = android.widget.ScrollView(this)
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 8, 24, 8)
                }
                scrollView.addView(container)

                val sorted = snapshot.documents.sortedBy {
                    (it.getString("customerId") ?: it.id)
                        .lowercase(Locale.getDefault())
                }

                val dialog = AlertDialog.Builder(this)
                    .setTitle("Relief Active Users (${sorted.size})")
                    .setView(scrollView)
                    .setPositiveButton("Close", null)
                    .create()

                sorted.forEach { doc ->
                    val id = doc.getString("customerId") ?: doc.id
                    val company =
                        (doc.getString("ispProvider") ?: "EBONE")
                            .uppercase(Locale.getDefault())
                    val reliefDays =
                        (doc.get("reliefDays") as? Number)?.toInt() ?: 0
                    val deadline =
                        (doc.get("graceDeadline") as? Number)?.toLong() ?: 0L
                    val deadlineText = if (deadline > 0) {
                        dateFormat.format(java.util.Date(deadline))
                    } else {
                        "—"
                    }
                    val daysText = if (reliefDays > 0) {
                        "$reliefDays day${if (reliefDays == 1) "" else "s"}"
                    } else {
                        "—"
                    }

                    val row = TextView(this).apply {
                        text = "$id  ·  $company  ·  $daysText  ·  Suspend: $deadlineText"
                        textSize = 14f
                        setTextColor(Color.parseColor("#0D2E5C"))
                        setPadding(0, 16, 0, 16)
                        isClickable = true
                        isFocusable = true
                        setBackgroundResource(android.R.drawable.list_selector_background)
                        setOnClickListener {
                            jumpToCustomer(id, company)
                            dialog.dismiss()
                        }
                    }

                    container.addView(row)
                }

                dialog.show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Could not load relief list: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun startDisabledUsersListener() {
        disabledUsersListenerRegistration?.remove()
        disabledUsersListenerRegistration = db.collection("customers")
            .whereEqualTo("reliefStatus", "SUSPENDED")
            .addSnapshotListener { snapshot, _ ->
                val count = snapshot?.documents?.size ?: 0
                tvDisabledUsersCount.text = "Disabled Users ($count)"
            }
    }

    private fun showDisabledUsersListDialog() {
        db.collection("customers")
            .whereEqualTo("reliefStatus", "SUSPENDED")
            .get()
            .addOnSuccessListener { snapshot ->

                if (snapshot.isEmpty) {
                    AlertDialog.Builder(this)
                        .setTitle("Disabled Users")
                        .setMessage("No one is currently disabled.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@addOnSuccessListener
                }

                val scrollView = android.widget.ScrollView(this)
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 8, 24, 8)
                }
                scrollView.addView(container)

                val sorted = snapshot.documents.sortedBy {
                    (it.getString("customerId") ?: it.id)
                        .lowercase(Locale.getDefault())
                }

                val dialog = AlertDialog.Builder(this)
                    .setTitle("Disabled Users (${sorted.size})")
                    .setView(scrollView)
                    .setPositiveButton("Close", null)
                    .create()

                sorted.forEach { doc ->
                    val id = doc.getString("customerId") ?: doc.id
                    val company =
                        (doc.getString("ispProvider") ?: "EBONE")
                            .uppercase(Locale.getDefault())
                    val isTest = doc.getBoolean("reliefTestMode") == true
                    val reliefDays =
                        (doc.get("reliefDays") as? Number)?.toInt() ?: 0
                    val deadline =
                        (doc.get("graceDeadline") as? Number)?.toLong() ?: 0L
                    val deadlineText = if (deadline > 0) {
                        dateFormat.format(java.util.Date(deadline))
                    } else {
                        "—"
                    }
                    val daysText = when {
                        isTest -> "TEST"
                        reliefDays > 0 -> "$reliefDays day${if (reliefDays == 1) "" else "s"}"
                        else -> "—"
                    }

                    val row = TextView(this).apply {
                        text = "$id  ·  $company  ·  $daysText  ·  Expired: $deadlineText"
                        textSize = 14f
                        setTextColor(Color.parseColor("#C62828"))
                        setPadding(0, 16, 0, 16)
                        isClickable = true
                        isFocusable = true
                        setBackgroundResource(android.R.drawable.list_selector_background)
                        setOnClickListener {
                            jumpToCustomer(id, company)
                            dialog.dismiss()
                        }
                    }

                    container.addView(row)
                }

                dialog.show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Could not load disabled users list: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        reliefListenerRegistration?.remove()
        disabledUsersListenerRegistration?.remove()
    }

    companion object {
        private const val REQUEST_RELIEF_ACTIVATION = 7401
        private const val REQUEST_RELIEF_REACTIVATION = 7402
    }
}