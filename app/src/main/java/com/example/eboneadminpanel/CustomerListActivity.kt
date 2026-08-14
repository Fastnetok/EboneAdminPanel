package com.example.eboneadminpanel

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale

class CustomerListActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private var listener: ListenerRegistration? = null

    private val ispOrder = listOf("EBONE", "WATEEN", "ZONG")
    private val companies = listOf("EBONE", "WATEEN", "ZONG")

    private var filter = "ALL"
    private var ispFilter = "ALL"

    private lateinit var rv: RecyclerView
    private lateinit var spinnerIsp: Spinner
    private lateinit var tvCustomerListTitle: TextView
    private lateinit var tvCustomerCount: TextView
    private lateinit var tvEmptyCustomers: TextView

    private var currentDocs = emptyList<DocumentSnapshot>()
    private var searchText = ""

    // ============================================================
    // FIX #1 (wrong suggestions): AutoCompleteTextView normally
    // re-filters whatever adapter you give it using ArrayAdapter's
    // default Filter, which only matches items that START WITH the
    // typed text. We already do our own "contains" filtering in
    // updateSuggestions(), so that second pass was silently dropping
    // valid contains-matches. The no-op filter below disables that
    // second filtering pass so exactly the list we computed is shown.
    //
    // FIX #2 (crash): previously a brand-new adapter OBJECT was
    // created and swapped in via searchBox.setAdapter(...) on every
    // keystroke. AutoCompleteTextView processes a dropdown item tap
    // via a Handler-posted Runnable, so there's a window between the
    // tap and it actually being handled. If setText() inside the tap
    // handler fires the TextWatcher -> updateSuggestions() -> a new
    // (smaller) adapter object gets swapped in during that window,
    // the pending click ends up resolving a position that no longer
    // exists in the new adapter -> IndexOutOfBoundsException.
    //
    // Fix: keep ONE stable adapter instance for the whole screen and
    // just update its backing list in place (clear + addAll +
    // notifyDataSetChanged) instead of ever replacing the adapter.
    // ============================================================
    private class SuggestionsAdapter(
        context: android.content.Context
    ) : ArrayAdapter<String>(
        context,
        android.R.layout.simple_dropdown_item_1line,
        mutableListOf()
    ) {

        private var currentItems: List<String> = emptyList()

        fun updateItems(newItems: List<String>) {
            currentItems = newItems
            clear()
            addAll(newItems)
            notifyDataSetChanged()
        }

        private val noOpFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                results.values = currentItems
                results.count = currentItems.size
                return results
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                // No-op: the list is already filtered manually in
                // updateSuggestions() and applied via updateItems().
            }
        }

        override fun getFilter(): Filter = noOpFilter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        filter = intent.getStringExtra("filter") ?: "ALL"
        ispFilter = "ALL"

        setContentView(R.layout.activity_customer_list)

        rv = findViewById(R.id.rvCustomers)
        spinnerIsp = findViewById(R.id.spinnerIsp)
        tvCustomerListTitle = findViewById(R.id.tvCustomerListTitle)
        tvCustomerCount = findViewById(R.id.tvCustomerCount)
        tvEmptyCustomers = findViewById(R.id.tvEmptyCustomers)

        tvCustomerListTitle.text = when (filter) {
            "ACTIVE" -> "Active Customers"
            "DISABLED" -> "Disabled Customers"
            else -> "All Customers"
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        spinnerIsp.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("All ISPs", "Ebone", "Wateen", "Zong")
        )

        rv.layoutManager = LinearLayoutManager(this)
        rv.setHasFixedSize(false)

        // ============================================================
        // ADVANCED CUSTOMER SEARCH
        // Suggestions come ONLY from customers already loaded from
        // Firestore. They are also limited to the currently selected ISP.
        // ============================================================
        val searchBox = AutoCompleteTextView(this).apply {
            hint = "Search customer ID..."
            setSingleLine(true)
            threshold = 1
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
            setTextColor(Color.parseColor("#172033"))
            setHintTextColor(Color.parseColor("#98A2B3"))
            setPadding(16, 0, 16, 0)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(Color.WHITE)
                setStroke(
                    (1 * resources.displayMetrics.density).toInt(),
                    Color.parseColor("#D0D5DD")
                )
            }

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (46 * resources.displayMetrics.density).toInt()
            ).also {
                it.setMargins(
                    (12 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt()
                )
            }
        }

        val rootContainer = rv.parent as? ViewGroup
        rootContainer?.let { parent ->
            val rvIndex = parent.indexOfChild(rv)
            parent.addView(searchBox, rvIndex)
        }

        // Single stable adapter instance for the lifetime of this screen.
        // See SuggestionsAdapter comments above for why this must not be
        // recreated on every keystroke.
        val suggestionsAdapter = SuggestionsAdapter(this)
        searchBox.setAdapter(suggestionsAdapter)

        fun updateSuggestions() {
            val query = searchBox.text.toString().trim()

            val statusFiltered = when (filter) {
                "ACTIVE" -> currentDocs.filter { isActive(it) }
                "DISABLED" -> currentDocs.filter { !isActive(it) }
                else -> currentDocs
            }

            val ispFiltered = if (ispFilter == "ALL") {
                statusFiltered
            } else {
                statusFiltered.filter {
                    (it.getString("ispProvider") ?: "EBONE")
                        .uppercase(Locale.getDefault()) == ispFilter
                }
            }

            val suggestions = ispFiltered
                .map { it.id }
                .filter { it.isNotBlank() }
                .distinct()
                .filter {
                    query.isEmpty() ||
                            it.contains(query, ignoreCase = true)
                }
                .sortedWith(
                    compareBy(
                        { !it.startsWith(query, ignoreCase = true) },
                        { it.lowercase(Locale.getDefault()) }
                    )
                )
                .take(8)

            // FIX: update the ONE stable adapter's contents in place,
            // rather than creating+setting a brand-new adapter object
            // every keystroke (that adapter-swap was both dropping
            // valid contains-matches and causing the tap crash).
            suggestionsAdapter.updateItems(suggestions)

            if (query.isNotEmpty() && suggestions.isNotEmpty() &&
                !searchBox.isPopupShowing
            ) {
                searchBox.showDropDown()
            }
        }

        fun renderCustomerList(docs: List<DocumentSnapshot>) {
            val statusFiltered: List<DocumentSnapshot> = when (filter) {
                "ACTIVE" -> docs.filter { isActive(it) }
                "DISABLED" -> docs.filter { !isActive(it) }
                else -> docs
            }

            val ispFiltered = if (ispFilter == "ALL") {
                statusFiltered
            } else {
                statusFiltered.filter {
                    (it.getString("ispProvider") ?: "EBONE")
                        .uppercase(Locale.getDefault()) == ispFilter
                }
            }

            val query = searchText.trim()

            val filtered = if (query.isEmpty()) {
                ispFiltered
            } else {
                ispFiltered.filter {
                    val customerId = it.id
                    customerId.contains(query, ignoreCase = true)
                }
            }

            tvCustomerCount.text =
                "${filtered.size} customer" +
                        if (filtered.size == 1) "" else "s"

            tvEmptyCustomers.visibility =
                if (filtered.isEmpty()) View.VISIBLE else View.GONE

            rv.visibility =
                if (filtered.isEmpty()) View.GONE else View.VISIBLE

            val items = mutableListOf<Any>()

            if (ispFilter == "ALL") {
                ispOrder.forEach { isp ->
                    val group = filtered.filter { doc ->
                        (doc.getString("ispProvider") ?: "EBONE")
                            .uppercase(Locale.getDefault()) == isp
                    }

                    if (group.isNotEmpty()) {
                        items.add("$isp|${group.size}")
                        items.addAll(group)
                    }
                }
            } else if (filtered.isNotEmpty()) {
                items.add("$ispFilter|${filtered.size}")
                items.addAll(filtered)
            }

            rv.adapter = CustomerAdapter(items)
            updateSuggestions()
        }

        spinnerIsp.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    ispFilter = when (position) {
                        1 -> "EBONE"
                        2 -> "WATEEN"
                        3 -> "ZONG"
                        else -> "ALL"
                    }

                    // Keep the current search text while changing ISP.
                    // Suggestions automatically change to the selected ISP.
                    renderCustomerList(currentDocs)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    ispFilter = "ALL"
                    renderCustomerList(currentDocs)
                }
            }

        searchBox.addTextChangedListener(object : TextWatcher {
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
                searchText = s?.toString()?.trim().orEmpty()
                renderCustomerList(currentDocs)
            }

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        searchBox.setOnItemClickListener { parent, _, position, _ ->
            // Defensive bounds check: guards against any adapter-timing
            // edge case (e.g. list shrinking between the tap and this
            // callback running) instead of crashing with IndexOutOfBounds.
            if (position < 0 || position >= parent.adapter.count) {
                return@setOnItemClickListener
            }

            val selected = parent.getItemAtPosition(position)?.toString().orEmpty()
            searchBox.setText(selected)
            searchBox.setSelection(searchBox.text.length)
            searchText = selected
            renderCustomerList(currentDocs)
        }

        listener = db.collection("customers")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener

                currentDocs = snapshot.documents
                renderCustomerList(currentDocs)
            }
    }

    private fun isActive(doc: DocumentSnapshot): Boolean {
        if (doc.getString("activationStatus") == "PENDING_APPROVAL") return false

        val lastPayment = doc.getLong("lastPaymentDate") ?: return false
        val days = (doc.getLong("billingCycleDays") ?: 30L).toInt()

        return System.currentTimeMillis() <
                lastPayment + (days * 86400000L)
    }

    inner class CustomerAdapter(
        private val items: List<Any>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_ROW = 1

        override fun getItemViewType(pos: Int) =
            if (items[pos] is String) TYPE_HEADER else TYPE_ROW

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): RecyclerView.ViewHolder =
            if (viewType == TYPE_HEADER) {
                HeaderVH(makeHeader(parent))
            } else {
                RowVH(makeRow(parent))
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            pos: Int
        ) {
            if (holder is HeaderVH) {
                val raw = items[pos] as String
                val parts = raw.split("|")
                holder.tv.text = "${parts[0]}  (${parts[1]})"
            } else if (holder is RowVH) {
                val doc = items[pos] as DocumentSnapshot
                holder.bind(doc)
            }
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v as TextView
        }

        inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {

            val tvName: TextView =
                v.findViewById(R.id.tvCustomerId)

            val tvPanel: TextView =
                v.findViewById(R.id.tvPanel)

            val tvMeta: TextView =
                v.findViewById(R.id.tvPackage)

            val tvStatus: TextView =
                v.findViewById(R.id.tvStatus)

            val btnEdit: ImageButton =
                v.findViewById(R.id.btnEdit)

            val btnDelete: ImageButton =
                v.findViewById(R.id.btnDelete)

            fun bind(doc: DocumentSnapshot) {
                val id = doc.id
                val pkg = doc.getString("packageId") ?: "—"
                val price = doc.getDouble("packagePrice") ?: 0.0
                val active = isActive(doc)

                tvName.text = id

                tvPanel.text =
                    (doc.getString("ispProvider") ?: "EBONE")
                        .uppercase(Locale.getDefault())

                tvMeta.text =
                    "$pkg · Rs ${"%.0f".format(price)}"

                tvStatus.text =
                    if (active) "Active" else "Disabled"

                val dp =
                    itemView.context.resources.displayMetrics.density

                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * dp
                    setColor(
                        if (active)
                            Color.parseColor("#E7F6EC")
                        else
                            Color.parseColor("#FDECEC")
                    )
                }

                tvStatus.background = bg

                tvStatus.setTextColor(
                    if (active)
                        Color.parseColor("#18794E")
                    else
                        Color.parseColor("#C62828")
                )

                itemView.alpha = 1f

                btnEdit.setOnClickListener {
                    showEditDialog(doc)
                }

                btnDelete.setOnClickListener {
                    AlertDialog.Builder(
                        this@CustomerListActivity
                    )
                        .setTitle("Delete Customer?")
                        .setMessage(
                            "Are you sure you want to delete \"$id\"?\n\n" +
                                    "This will permanently delete the customer " +
                                    "and all their transactions."
                        )
                        .setPositiveButton("Yes") { _, _ ->
                            deleteCustomer(id)
                        }
                        .setNegativeButton("No", null)
                        .show()
                }
            }
        }

        private fun makeHeader(parent: ViewGroup): View {
            return layoutInflater.inflate(
                R.layout.item_isp_header,
                parent,
                false
            )
        }

        private fun makeRow(parent: ViewGroup): View {
            return layoutInflater.inflate(
                R.layout.item_customer,
                parent,
                false
            )
        }
    }

    private fun showEditDialog(doc: DocumentSnapshot) {
        val oldId = doc.id

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val etCustomerId = EditText(this).apply {
            hint = "Customer ID"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(oldId)
        }

        val ispSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomerListActivity,
                android.R.layout.simple_spinner_dropdown_item,
                companies
            )

            setSelection(
                companies.indexOf(
                    doc.getString("ispProvider") ?: "EBONE"
                ).coerceAtLeast(0)
            )
        }

        val etPackage = EditText(this).apply {
            hint = "Package (e.g. 8 Mbps)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(doc.getString("packageId") ?: "")
        }

        val etPrice = EditText(this).apply {
            hint = "Package Price"
            inputType =
                InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL

            setText(
                "%.0f".format(
                    doc.getDouble("packagePrice") ?: 0.0
                )
            )
        }

        layout.addView(etCustomerId)
        layout.addView(ispSpinner)
        layout.addView(etPackage)
        layout.addView(etPrice)

        AlertDialog.Builder(this)
            .setTitle("Edit Customer")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->

                val newId =
                    etCustomerId.text.toString().trim()

                val price =
                    etPrice.text.toString().toDoubleOrNull()
                        ?: return@setPositiveButton

                if (newId.isEmpty()) {
                    Toast.makeText(
                        this,
                        "Customer ID cannot be empty",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setPositiveButton
                }

                val updatedData = hashMapOf(
                    "customerId" to newId,
                    "ispProvider" to
                            ispSpinner.selectedItem.toString(),
                    "packageId" to
                            etPackage.text.toString().trim(),
                    "packagePrice" to price,
                    "activationStatus" to
                            (doc.getString("activationStatus")
                                ?: "ACTIVE"),
                    "billingCycleDays" to
                            (doc.getLong("billingCycleDays")
                                ?: 30L),
                    "registrationPin" to
                            doc.getString("registrationPin"),
                    "linkedDeviceId" to
                            doc.getString("linkedDeviceId"),
                    "lastPaymentDate" to
                            doc.getLong("lastPaymentDate"),
                    "currentBalance" to
                            (doc.getDouble("currentBalance")
                                ?: 0.0)
                )

                if (newId == oldId) {

                    db.collection("customers")
                        .document(oldId)
                        .update(
                            mapOf(
                                "ispProvider" to
                                        ispSpinner.selectedItem
                                            .toString(),

                                "packageId" to
                                        etPackage.text.toString()
                                            .trim(),

                                "packagePrice" to price
                            )
                        )
                        .addOnSuccessListener {
                            Toast.makeText(
                                this,
                                "Updated ✅",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(
                                this,
                                "Update failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                } else {

                    db.collection("customers")
                        .document(newId)
                        .set(updatedData)
                        .addOnSuccessListener {

                            db.collection("transactions")
                                .whereEqualTo(
                                    "customerId",
                                    oldId
                                )
                                .get()
                                .addOnSuccessListener { txns ->

                                    val batch = db.batch()

                                    txns.documents.forEach { txn ->
                                        batch.update(
                                            txn.reference,
                                            "customerId",
                                            newId
                                        )
                                    }

                                    batch.delete(
                                        db.collection("customers")
                                            .document(oldId)
                                    )

                                    batch.commit()
                                        .addOnSuccessListener {
                                            Toast.makeText(
                                                this,
                                                "ID changed: " +
                                                        "$oldId → $newId ✅",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        .addOnFailureListener { e ->
                                            Toast.makeText(
                                                this,
                                                "Transaction update failed: " +
                                                        "${e.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(
                                        this,
                                        "Transaction lookup failed: " +
                                                "${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(
                                this,
                                "Failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCustomer(customerId: String) {
        db.collection("transactions")
            .whereEqualTo("customerId", customerId)
            .get()
            .addOnSuccessListener { txns ->

                val batch = db.batch()

                txns.documents.forEach {
                    batch.delete(it.reference)
                }

                batch.delete(
                    db.collection("customers")
                        .document(customerId)
                )

                batch.commit()
                    .addOnSuccessListener {
                        Toast.makeText(
                            this,
                            "$customerId deleted",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            this,
                            "Delete failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Could not load transactions: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
    }
}