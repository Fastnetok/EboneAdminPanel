package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase

class NewConnectionAdapter(
    private val connectionList: List<Complaint>,
    private val onAssignClicked: (Complaint) -> Unit
) : RecyclerView.Adapter<NewConnectionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.userIdText)
        val tvAddress: TextView = view.findViewById(R.id.addressText)
        val tvPhone: TextView = view.findViewById(R.id.phoneText)
        val tvNotes: TextView = view.findViewById(R.id.resolutionTimeText)
        val btnAssign: Button = view.findViewById(R.id.resolveButton)
        
        val btnEdit: Button = view.findViewById(R.id.editButton)
        val btnDelete: Button = view.findViewById(R.id.deleteButton)
        val btnMove: Button = view.findViewById(R.id.moveButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_employee_complaint, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val connection = connectionList[position]
        val context = holder.itemView.context
        
        holder.tvName.text = connection.userId
        holder.tvAddress.text = "Address: ${connection.address}"
        holder.tvPhone.text = "Phone: ${connection.phoneNumber}"
        holder.tvNotes.text = "Notes: ${connection.details}"
        
        // Buttons: EDIT, DELETE, MOVE, ASSIGN
        holder.btnEdit.setOnClickListener {
            val layout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)
            }
            val userIdInput = android.widget.EditText(context).apply { hint = "User ID"; setText(connection.userId) }
            val addressInput = android.widget.EditText(context).apply { hint = "Address"; setText(connection.address) }
            val phoneInput = android.widget.EditText(context).apply { hint = "Phone"; setText(connection.phoneNumber) }
            layout.addView(userIdInput); layout.addView(addressInput); layout.addView(phoneInput)

            AlertDialog.Builder(context)
                .setTitle("Edit Connection")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val updates = mapOf(
                        "userId" to userIdInput.text.toString().trim(),
                        "address" to addressInput.text.toString().trim(),
                        "phoneNumber" to phoneInput.text.toString().trim()
                    )
                    FirebaseDatabase.getInstance().getReference("new_connections_pending")
                        .child(connection.complaintId).updateChildren(updates)
                }
                .setNegativeButton("Cancel", null).show()
        }

        holder.btnDelete.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Delete Connection")
                .setMessage("Are you sure?")
                .setPositiveButton("Delete") { _, _ ->
                    FirebaseDatabase.getInstance().getReference("new_connections_pending")
                        .child(connection.complaintId).removeValue()
                }
                .setNegativeButton("Cancel", null).show()
        }

        holder.btnAssign.text = "ASSIGN"
        holder.btnAssign.setOnClickListener { onAssignClicked(connection) }
    }

    override fun getItemCount() = connectionList.size
}
