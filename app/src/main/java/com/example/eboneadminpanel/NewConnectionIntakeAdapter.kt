package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase

class NewConnectionIntakeAdapter(
    private val list: List<NewConnection>,
    private val onAssign: (NewConnection) -> Unit,
    private val onMove: (NewConnection) -> Unit
) : RecyclerView.Adapter<NewConnectionIntakeAdapter.ViewHolder>() {

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvCustomerName)
        val address: TextView = v.findViewById(R.id.tvAddress)
        val phone: TextView = v.findViewById(R.id.tvPhone)
        val comments: TextView = v.findViewById(R.id.tvComments)
        val btnEdit: Button = v.findViewById(R.id.btnEdit)
        val btnDelete: Button = v.findViewById(R.id.btnDelete)
        val btnMove: Button = v.findViewById(R.id.btnMove)
        val btnAssign: Button = v.findViewById(R.id.btnAssign)
        val btnInstall: Button = v.findViewById(R.id.btnInstall)

        val seenStatus: android.widget.ImageView = v.findViewById(R.id.ivSeenStatus)
        val seenTime: TextView = v.findViewById(R.id.tvSeenTime)
        val createdTime: TextView = v.findViewById(R.id.tvCreatedTime)
        val assignedToText: TextView = v.findViewById(R.id.tvAssignedTo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_new_connection_intake, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        val context = holder.itemView.context
        val fullFormat = java.text.SimpleDateFormat("dd MMM / h:mm a", java.util.Locale.getDefault())

        holder.name.text = item.customerName
        holder.address.text = item.address
        holder.phone.text = item.phoneNumber
        holder.comments.text = "Note: ${item.comments}"
        holder.createdTime.text = "Added: ${fullFormat.format(java.util.Date(item.createdTime))}"

        holder.btnEdit.setOnClickListener {
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 10)
            }
            val etName = EditText(context).apply { hint = "Name"; setText(item.customerName) }
            val etAddr = EditText(context).apply { hint = "Address"; setText(item.address) }
            val etPhone = EditText(context).apply { hint = "Phone"; setText(item.phoneNumber) }
            val etComm = EditText(context).apply { hint = "Comments"; setText(item.comments) }
            layout.addView(etName); layout.addView(etAddr); layout.addView(etPhone); layout.addView(etComm)

            AlertDialog.Builder(context)
                .setTitle("Edit Connection")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val updates = mapOf(
                        "customerName" to etName.text.toString().trim(),
                        "address" to etAddr.text.toString().trim(),
                        "phoneNumber" to etPhone.text.toString().trim(),
                        "comments" to etComm.text.toString().trim()
                    )

                    // Root path is officeSettings/new_connections
                    val fbRoot = FirebaseDatabase.getInstance().getReference("officeSettings/new_connections")

                    if (item.assignedTo.isEmpty()) {
                        fbRoot.child("pending").child(item.id).updateChildren(updates)
                    } else {
                        fbRoot.child("gift_box").child(item.assignedTo).child(item.id).updateChildren(updates)
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }

        holder.btnDelete.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Delete Connection?")
                .setMessage("Are you sure you want to delete this connection?")
                .setPositiveButton("YES") { _, _ ->
                    val fbRoot = FirebaseDatabase.getInstance().getReference("officeSettings/new_connections")
                    if (item.assignedTo.isEmpty()) {
                        fbRoot.child("pending").child(item.id).removeValue()
                    } else {
                        fbRoot.child("gift_box").child(item.assignedTo).child(item.id).removeValue()
                    }
                }
                .setNegativeButton("NO", null).show()
        }

        holder.btnMove.setOnClickListener { onMove(item) }

        holder.btnAssign.setOnClickListener { onAssign(item) }

        // NOTE: the Seen/Double-tick indicator is intentionally NOT shown
        // on this screen (Pending list / Total employee drill-down).
        // The tick is only meaningful on the Progress screen
        // (NewConnectionProgressAdapter) — showing it here as well was
        // confusing, since this list mixes unassigned, assigned, and
        // completed items for entirely different purposes.
        holder.seenStatus.visibility = View.GONE
        holder.seenTime.visibility = View.GONE

        if (item.status == "Completed") {
            holder.assignedToText.text = "Installed By: ${item.assignedTo}"
            holder.assignedToText.visibility = View.VISIBLE

            holder.btnAssign.visibility = View.GONE
            holder.btnMove.visibility = View.GONE
            holder.btnInstall.visibility = View.GONE
            holder.btnEdit.visibility = View.GONE
        } else if (item.assignedTo.isNotEmpty()) {
            holder.assignedToText.text = "Sent: ${item.assignedTo}"
            holder.assignedToText.visibility = View.VISIBLE

            holder.btnAssign.visibility = View.GONE
            holder.btnMove.visibility = View.VISIBLE
            holder.btnInstall.visibility = View.VISIBLE
            holder.btnEdit.visibility = View.VISIBLE
        } else {
            holder.assignedToText.visibility = View.GONE
            holder.btnAssign.visibility = View.VISIBLE
            holder.btnMove.visibility = View.GONE
            holder.btnInstall.visibility = View.GONE
            holder.btnEdit.visibility = View.VISIBLE
        }

        // Install (Resolve) by Admin
        holder.btnInstall.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Mark as Installed")
                .setMessage("Are you sure you want to mark this as Installed?")
                .setPositiveButton("YES") { _, _ ->
                    val currentTime = System.currentTimeMillis()
                    val updates = mapOf(
                        "status" to "Completed",
                        "completionTime" to currentTime
                    )
                    val root = FirebaseDatabase.getInstance().getReference("officeSettings/new_connections")
                    root.child("completed").child(item.id).setValue(item).addOnSuccessListener {
                        root.child("completed").child(item.id).updateChildren(updates)
                        root.child("gift_box").child(item.assignedTo).child(item.id).removeValue()
                    }
                }
                .setNegativeButton("No", null).show()
        }
    }

    override fun getItemCount() = list.size
}