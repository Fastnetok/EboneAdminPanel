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

class EmployeeComplaintAdapter(
    private val complaintList: MutableList<Complaint>
) : RecyclerView.Adapter<EmployeeComplaintAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val userIdText: TextView =
            itemView.findViewById(R.id.userIdText)

        val addressText: TextView =
            itemView.findViewById(R.id.addressText)

        val phoneText: TextView =
            itemView.findViewById(R.id.phoneText)

        val assignedDateTimeText: TextView =
            itemView.findViewById(R.id.assignedDateTimeText)

        val editButton: Button =
            itemView.findViewById(R.id.editButton)

        val deleteButton: Button =
            itemView.findViewById(R.id.deleteButton)

        val moveButton: Button =
            itemView.findViewById(R.id.moveButton)

        val resolveButton: Button =
            itemView.findViewById(R.id.resolveButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_employee_complaint, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val complaint = complaintList[position]

        holder.userIdText.text = complaint.userId
        holder.addressText.text = complaint.address
        holder.phoneText.text = complaint.phoneNumber

        val formatter = SimpleDateFormat("dd MMM yyyy / hh:mm a", Locale.getDefault())
        holder.assignedDateTimeText.text =
            "Assigned: " + formatter.format(Date(complaint.assignedTime))

        // RESOLVE
        holder.resolveButton.setOnClickListener {
            AlertDialog.Builder(holder.itemView.context)
                .setTitle("Resolve Complaint")
                .setMessage("Are you sure you want to mark this complaint as resolved?")
                .setPositiveButton("Resolve") { _, _ ->
                    FirebaseDatabase.getInstance()
                        .getReference("complaints")
                        .child(complaint.complaintId)
                        .updateChildren(
                            mapOf(
                                "status" to "Resolved",
                                "resolvedBy" to "Admin",
                                "resolvedTime" to System.currentTimeMillis()
                            )
                        )
                        .addOnSuccessListener {
                            // FIX: don't manually touch complaintList/position here.
                            // The screen that shows this RecyclerView already has a
                            // live Firebase listener on "complaints" that refreshes
                            // the whole list (and calls notifyDataSetChanged) the
                            // moment this write lands on the server. Doing both at
                            // once caused a race condition -> IndexOutOfBounds crash.
                            Toast.makeText(
                                holder.itemView.context,
                                "Complaint Resolved",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .addOnFailureListener {
                            Toast.makeText(
                                holder.itemView.context,
                                "Failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // EDIT
        holder.editButton.setOnClickListener {
            val dialogView = LayoutInflater.from(holder.itemView.context)
                .inflate(R.layout.dialog_edit_complaint, null)

            val editUserId = dialogView.findViewById<EditText>(R.id.editUserId)
            val editPhone = dialogView.findViewById<EditText>(R.id.editPhone)
            val editAddress = dialogView.findViewById<EditText>(R.id.editAddress)

            editUserId.setText(complaint.userId)
            editPhone.setText(complaint.phoneNumber)
            editAddress.setText(complaint.address)

            AlertDialog.Builder(holder.itemView.context)
                .setTitle("Edit Complaint")
                .setView(dialogView)
                .setPositiveButton("SAVE") { _, _ ->
                    FirebaseDatabase.getInstance()
                        .getReference("complaints")
                        .child(complaint.complaintId)
                        .updateChildren(
                            mapOf(
                                "userId" to editUserId.text.toString(),
                                "phoneNumber" to editPhone.text.toString(),
                                "address" to editAddress.text.toString()
                            )
                        )
                    Toast.makeText(
                        holder.itemView.context,
                        "Complaint Updated",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }

        // MOVE
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
                            holder.itemView.context,
                            "Koi employee nahi mila",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@addOnSuccessListener
                    }

                    AlertDialog.Builder(holder.itemView.context)
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
                                        "assignedTime" to System.currentTimeMillis()
                                    )
                                )
                                .addOnSuccessListener {
                                    // FIX: same reason as above — let the live
                                    // listener refresh the list instead of also
                                    // removing here.
                                    Toast.makeText(
                                        holder.itemView.context,
                                        "Moved to $selectedName",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
        }

        // DELETE
        holder.deleteButton.setOnClickListener {
            AlertDialog.Builder(holder.itemView.context)
                .setTitle("Delete Complaint")
                .setMessage("Are you sure you want to delete this complaint?")
                .setPositiveButton("YES") { _, _ ->
                    FirebaseDatabase.getInstance()
                        .getReference("complaints")
                        .child(complaint.complaintId)
                        .removeValue()
                        .addOnSuccessListener {
                            // FIX: same reason as above.
                            Toast.makeText(
                                holder.itemView.context,
                                "Complaint Deleted",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
                .setNegativeButton("NO", null)
                .show()
        }
    }

    override fun getItemCount(): Int = complaintList.size
}