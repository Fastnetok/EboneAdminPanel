package com.example.eboneadminpanel

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
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

class CustomerListActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private var listener: ListenerRegistration? = null
    private val ispOrder = listOf("EBONE", "WATEEN", "ZONG")
    private val companies = listOf("EBONE", "WATEEN", "ZONG")
    private var filter = "ALL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filter = intent.getStringExtra("filter") ?: "ALL"

        val title = when (filter) {
            "ACTIVE" -> "Active Customers"
            "DISABLED" -> "Disabled Customers"
            else -> "All Customers"
        }

        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6FA"))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0D2E5C"))
            setPadding((16*dp).toInt(), (48*dp).toInt(), (16*dp).toInt(), (16*dp).toInt())
        }
        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            background = null
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((28*dp).toInt(), (28*dp).toInt())
            setOnClickListener { finish() }
        }
        val titleTv = TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginStart = (12*dp).toInt() }
        }
        header.addView(backBtn)
        header.addView(titleTv)
        root.addView(header)

        // RecyclerView
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CustomerListActivity)
            setPadding((12*dp).toInt(), (12*dp).toInt(), (12*dp).toInt(), (12*dp).toInt())
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(rv)
        setContentView(root)

        listener = db.collection("customers").addSnapshotListener { snapshot, _ ->
            if (snapshot == null) return@addSnapshotListener
            val docs: List<DocumentSnapshot> = snapshot.documents
            val filtered: List<DocumentSnapshot> = when (filter) {
                "ACTIVE" -> docs.filter { isActive(it) }
                "DISABLED" -> docs.filter { !isActive(it) }
                else -> docs
            }
            val items = mutableListOf<Any>()
            ispOrder.forEach { isp ->
                val group = filtered.filter { doc ->
                    (doc.getString("ispProvider") ?: "EBONE") == isp
                }
                if (group.isNotEmpty()) {
                    items.add("$isp|${group.size}")
                    items.addAll(group)
                }
            }
            rv.adapter = CustomerAdapter(items)
        }
    }

    private fun isActive(doc: DocumentSnapshot): Boolean {
        if (doc.getString("activationStatus") == "PENDING_APPROVAL") return false
        val lastPayment = doc.getLong("lastPaymentDate") ?: return false
        val days = (doc.getLong("billingCycleDays") ?: 30L).toInt()
        return System.currentTimeMillis() < lastPayment + (days * 86400000L)
    }

    inner class CustomerAdapter(private val items: List<Any>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_ROW = 1

        override fun getItemViewType(pos: Int) =
            if (items[pos] is String) TYPE_HEADER else TYPE_ROW

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_HEADER) HeaderVH(makeHeader()) else RowVH(makeRow())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
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
            val tvName: TextView = v.findViewWithTag("name")
            val tvMeta: TextView = v.findViewWithTag("meta")
            val tvStatus: TextView = v.findViewWithTag("status")
            val btnEdit: ImageButton = v.findViewWithTag("edit")
            val btnDelete: ImageButton = v.findViewWithTag("delete")

            fun bind(doc: DocumentSnapshot) {
                val id = doc.id
                val pkg = doc.getString("packageId") ?: "—"
                val price = doc.getDouble("packagePrice") ?: 0.0
                val active = isActive(doc)

                tvName.text = id
                tvMeta.text = "$pkg · Rs ${"%.0f".format(price)}"
                tvStatus.text = if (active) "Active" else "Disabled"

                val dp = itemView.context.resources.displayMetrics.density
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 20f * dp
                    // Vibrant colors
                    setColor(if (active) Color.parseColor("#43A047") else Color.parseColor("#E53935"))
                }
                tvStatus.background = bg
                // White text on vibrant background
                tvStatus.setTextColor(Color.WHITE)
                itemView.alpha = if (active) 1f else 0.65f

                btnEdit.setOnClickListener { showEditDialog(doc) }
                btnDelete.setOnClickListener {
                    AlertDialog.Builder(this@CustomerListActivity)
                        .setTitle("Delete Customer?")
                        .setMessage("Are you sure you want to delete \"$id\"?\n\nThis will permanently delete the customer and all their transactions.")
                        .setPositiveButton("Yes") { _, _ -> deleteCustomer(id) }
                        .setNegativeButton("No", null)
                        .show()
                }
            }
        }

        private fun makeHeader(): View {
            val dp = resources.displayMetrics.density
            return TextView(this@CustomerListActivity).apply {
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#9E9E9E"))
                setAllCaps(true)
                setPadding((16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt(), (6*dp).toInt())
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }

        private fun makeRow(): View {
            val dp = resources.displayMetrics.density
            val ctx = this@CustomerListActivity

            // Card 70% size — reduced padding
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding((11*dp).toInt(), (8*dp).toInt(), (11*dp).toInt(), (8*dp).toInt())
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (1*dp).toInt() }
            }

            val textBlock = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(ctx).apply {
                tag = "name"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#111111"))
            }
            val tvMeta = TextView(ctx).apply {
                tag = "meta"
                textSize = 10f
                setTextColor(Color.parseColor("#9E9E9E"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (1*dp).toInt() }
            }
            textBlock.addView(tvName)
            textBlock.addView(tvMeta)

            // Vibrant status badge
            val tvStatus = TextView(ctx).apply {
                tag = "status"
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding((10*dp).toInt(), (4*dp).toInt(), (10*dp).toInt(), (4*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = (6*dp).toInt(); it.marginEnd = (6*dp).toInt() }
            }

            // Both buttons same block size (34dp)
            val btnSize = (34*dp).toInt()

            val editBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 7*dp
                setColor(Color.parseColor("#F0F0F0"))
                setStroke((1*dp).toInt(), Color.parseColor("#D0D0D0"))
            }
            val deleteBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 7*dp
                setColor(Color.parseColor("#FFCDD2"))
                setStroke((1*dp).toInt(), Color.parseColor("#FF5252"))
            }

            val btnEdit = ImageButton(ctx).apply {
                tag = "edit"
                setImageResource(android.R.drawable.ic_menu_edit)
                background = editBg
                setColorFilter(Color.parseColor("#424242"))
                setPadding((4*dp).toInt(), (4*dp).toInt(), (4*dp).toInt(), (4*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                    .also { it.marginEnd = (5*dp).toInt() }
            }
            val btnDelete = ImageButton(ctx).apply {
                tag = "delete"
                setImageResource(android.R.drawable.ic_menu_delete)
                background = deleteBg
                setColorFilter(Color.parseColor("#D32F2F"))
                // Extra padding makes icon appear 80% — block stays same size
                setPadding((6*dp).toInt(), (6*dp).toInt(), (6*dp).toInt(), (6*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            }

            card.addView(textBlock)
            card.addView(tvStatus)
            card.addView(btnEdit)
            card.addView(btnDelete)
            return card
        }
    }

    private fun showEditDialog(doc: DocumentSnapshot) {
        val oldId = doc.id
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        // Customer ID field
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
            setSelection(companies.indexOf(doc.getString("ispProvider") ?: "EBONE").coerceAtLeast(0))
        }
        val etPackage = EditText(this).apply {
            hint = "Package (e.g. 8 Mbps)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(doc.getString("packageId") ?: "")
        }
        val etPrice = EditText(this).apply {
            hint = "Package Price"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.0f".format(doc.getDouble("packagePrice") ?: 0.0))
        }

        layout.addView(etCustomerId)
        layout.addView(ispSpinner)
        layout.addView(etPackage)
        layout.addView(etPrice)

        AlertDialog.Builder(this)
            .setTitle("Edit Customer")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newId = etCustomerId.text.toString().trim()
                val price = etPrice.text.toString().toDoubleOrNull() ?: return@setPositiveButton

                if (newId.isEmpty()) {
                    return@setPositiveButton
                    return@setPositiveButton
                }

                val updatedData = hashMapOf(
                    "customerId" to newId,
                    "ispProvider" to ispSpinner.selectedItem.toString(),
                    "packageId" to etPackage.text.toString().trim(),
                    "packagePrice" to price,
                    "activationStatus" to (doc.getString("activationStatus") ?: "ACTIVE"),
                    "billingCycleDays" to (doc.getLong("billingCycleDays") ?: 30L),
                    "registrationPin" to doc.getString("registrationPin"),
                    "linkedDeviceId" to doc.getString("linkedDeviceId"),
                    "lastPaymentDate" to doc.getLong("lastPaymentDate"),
                    "currentBalance" to (doc.getDouble("currentBalance") ?: 0.0)
                )

                if (newId == oldId) {
                    // Same ID — just update fields
                    db.collection("customers").document(oldId).update(
                        mapOf(
                            "ispProvider" to ispSpinner.selectedItem.toString(),
                            "packageId" to etPackage.text.toString().trim(),
                            "packagePrice" to price
                        )
                    ).addOnSuccessListener {
                        Toast.makeText(this, "Updated ✅", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // ID Changed — create new doc, delete old
                    db.collection("customers").document(newId).set(updatedData)
                        .addOnSuccessListener {
                            // Update all transactions to new ID
                            db.collection("transactions")
                                .whereEqualTo("customerId", oldId).get()
                                .addOnSuccessListener { txns ->
                                    val batch = db.batch()
                                    txns.documents.forEach { txn ->
                                        batch.update(txn.reference, "customerId", newId)
                                    }
                                    batch.delete(db.collection("customers").document(oldId))
                                    batch.commit().addOnSuccessListener {
                                        Toast.makeText(this, "ID changed: $oldId → $newId ✅", Toast.LENGTH_LONG).show()
                                    }
                                }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCustomer(customerId: String) {
        db.collection("transactions").whereEqualTo("customerId", customerId).get()
            .addOnSuccessListener { txns ->
                val batch = db.batch()
                txns.documents.forEach { batch.delete(it.reference) }
                batch.delete(db.collection("customers").document(customerId))
                batch.commit().addOnSuccessListener {
                    Toast.makeText(this, "$customerId deleted", Toast.LENGTH_SHORT).show()
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
    }
}