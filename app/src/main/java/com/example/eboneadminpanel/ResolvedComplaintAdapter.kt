package com.example.eboneadminpanel

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResolvedComplaintAdapter(
    private val complaintList: MutableList<Complaint>
) : RecyclerView.Adapter<ResolvedComplaintAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val userIdText: TextView = itemView.findViewById(R.id.userIdText)
        val addressText: TextView = itemView.findViewById(R.id.addressText)
        val phoneText: TextView = itemView.findViewById(R.id.phoneText)
        val assignedDateTimeText: TextView = itemView.findViewById(R.id.assignedDateTimeText)
        val resolutionTimeText: TextView = itemView.findViewById(R.id.resolutionTimeText)
        val editButton: Button = itemView.findViewById(R.id.editButton)
        val deleteButton: Button = itemView.findViewById(R.id.deleteButton)
        val moveButton: Button = itemView.findViewById(R.id.moveButton)
        val resolveButton: Button = itemView.findViewById(R.id.resolveButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_employee_complaint, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val complaint = complaintList[position]
        val context = holder.itemView.context

        holder.userIdText.text = complaint.userId
        holder.addressText.text = complaint.address
        holder.phoneText.text = complaint.phoneNumber

        holder.resolveButton.visibility = View.GONE

        val formatter = SimpleDateFormat("dd MMM yyyy / hh:mm a", Locale.getDefault())
        holder.assignedDateTimeText.text =
            "Resolved: " + formatter.format(Date(complaint.resolvedTime))

        val diff = complaint.resolvedTime - complaint.assignedTime
        val minutes = diff / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24

        val result = when {
            days > 0 -> "$days Day(s) ${hours % 24} Hour(s)"
            hours > 0 -> "$hours Hour(s) ${minutes % 60} Minute(s)"
            else -> "$minutes Minute(s)"
        }

        holder.resolutionTimeText.text = "Resolved In: $result"

        holder.editButton.setOnClickListener {
            val layout = android.widget.LinearLayout(context)
            layout.orientation = android.widget.LinearLayout.VERTICAL
            layout.setPadding(40, 20, 40, 20)

            val userIdInput = EditText(context)
            userIdInput.hint = "User ID"
            userIdInput.setText(complaint.userId)
            layout.addView(userIdInput)

            val addressInput = EditText(context)
            addressInput.hint = "Address"
            addressInput.setText(complaint.address)
            layout.addView(addressInput)

            val phoneInput = EditText(context)
            phoneInput.hint = "Phone"
            phoneInput.setText(complaint.phoneNumber)
            layout.addView(phoneInput)

            AlertDialog.Builder(context)
                .setTitle("Edit Complaint")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val newUserId = userIdInput.text.toString().trim()
                    val newAddress = addressInput.text.toString().trim()
                    val newPhone = phoneInput.text.toString().trim()

                    FirebaseDatabase.getInstance()
                        .getReference("complaints")
                        .child(complaint.complaintId)
                        .updateChildren(
                            mapOf(
                                "userId" to newUserId,
                                "address" to newAddress,
                                "phoneNumber" to newPhone
                            )
                        )
                        .addOnSuccessListener {
                            complaint.userId = newUserId
                            complaint.address = newAddress
                            complaint.phoneNumber = newPhone
                            notifyItemChanged(position)
                            Toast.makeText(context, "Updated", Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener {
                            Toast.makeText(context, "Update failed", Toast.LENGTH_SHORT).show()
                        }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        holder.deleteButton.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Delete Complaint")
                .setMessage("Are you sure you want to delete this complaint?")
                .setPositiveButton("Delete") { _, _ ->
                    FirebaseDatabase.getInstance()
                        .getReference("complaints")
                        .child(complaint.complaintId)
                        .removeValue()
                        .addOnSuccessListener {
                            complaintList.removeAt(position)
                            notifyItemRemoved(position)
                            notifyItemRangeChanged(position, complaintList.size)
                            Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener {
                            Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                        }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        holder.moveButton.setOnClickListener {
            FirebaseDatabase.getInstance()
                .getReference("employees")
                .get()
                .addOnSuccessListener { snapshot ->
                    val employeeNames = mutableListOf<String>()

                    for (child in snapshot.children) {
                        val name = child.child("employeeName")
                            .getValue(String::class.java) ?: ""
                        if (name.isNotEmpty()) {
                            employeeNames.add(name)
                        }
                    }

                    if (employeeNames.isEmpty()) {
                        Toast.makeText(
                            context,
                            "Koi employee nahi mila",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@addOnSuccessListener
                    }

                    AlertDialog.Builder(context)
                        .setTitle("Move Complaint To")
                        .setItems(employeeNames.toTypedArray()) { _, which ->
                            val selectedName = employeeNames[which]
                            FirebaseDatabase.getInstance()
                                .getReference("complaints")
                                .child(complaint.complaintId)
                                .updateChildren(
                                    mapOf(
                                        "assignedTo" to selectedName,
                                        "status" to "Progress",
                                        "assignedTime" to System.currentTimeMillis(),
                                        "resolvedBy" to "",
                                        "resolvedTime" to 0
                                    )
                                )
                                .addOnSuccessListener {
                                    complaintList.removeAt(position)
                                    notifyItemRemoved(position)
                                    notifyItemRangeChanged(position, complaintList.size)
                                    Toast.makeText(
                                        context,
                                        "Moved to $selectedName",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
        }
    }

    override fun getItemCount(): Int = complaintList.size
}