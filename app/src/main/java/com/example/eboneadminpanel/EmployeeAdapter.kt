package com.example.eboneadminpanel

import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase

class EmployeeAdapter(

    private val employeeList: List<EmployeeItem>,

    private val listener: (EmployeeItem) -> Unit

) : RecyclerView.Adapter<EmployeeAdapter.ViewHolder>() {

    class ViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

        val nameText: TextView =
            itemView.findViewById(
                R.id.nameText
            )

        val statusText: TextView =
            itemView.findViewById(
                R.id.statusText
            )

        val pinText: TextView =
            itemView.findViewById(
                R.id.pinText
            )

        val pinSwitch: Switch =
            itemView.findViewById(
                R.id.pinSwitch
            )

        val setPinButton: Button =
            itemView.findViewById(
                R.id.setPinButton
            )
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {

        val view =
            LayoutInflater.from(
                parent.context
            ).inflate(
                R.layout.item_employee,
                parent,
                false
            )

        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
        val employee = employeeList[position]

        holder.nameText.text = employee.name
        holder.statusText.text = employee.status

        // Load each employee's own PIN settings.
        FirebaseDatabase.getInstance()
            .getReference("employees")
            .child(employee.employeeId)
            .get()
            .addOnSuccessListener { snapshot ->
                // ViewHolder may have been reused while Firebase returned.
                if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) {
                    return@addOnSuccessListener
                }

                val currentEmployee =
                    employeeList[holder.bindingAdapterPosition]

                if (currentEmployee.employeeId != employee.employeeId) {
                    return@addOnSuccessListener
                }

                val pin =
                    snapshot.child("attendancePin")
                        .value
                        ?.toString()
                        ?.trim()
                        .orEmpty()

                val enabled =
                    snapshot.child("attendancePinEnabled")
                        .getValue(Boolean::class.java)
                        ?: false

                holder.pinText.text =
                    if (pin.isNotEmpty()) {
                        "PIN: $pin"
                    } else {
                        "PIN: Not Set"
                    }

                holder.pinSwitch.setOnCheckedChangeListener(null)
                holder.pinSwitch.isChecked = enabled
                holder.pinSwitch.setOnCheckedChangeListener { _, isChecked ->
                    FirebaseDatabase.getInstance()
                        .getReference("employees")
                        .child(employee.employeeId)
                        .child("attendancePinEnabled")
                        .setValue(isChecked)
                        .addOnSuccessListener {
                            Toast.makeText(
                                holder.itemView.context,
                                if (isChecked)
                                    "${employee.name}: PIN ON"
                                else
                                    "${employee.name}: PIN OFF",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .addOnFailureListener {
                            holder.pinSwitch.setOnCheckedChangeListener(null)
                            holder.pinSwitch.isChecked = !isChecked
                            holder.pinSwitch.setOnCheckedChangeListener { _, checked ->
                                FirebaseDatabase.getInstance()
                                    .getReference("employees")
                                    .child(employee.employeeId)
                                    .child("attendancePinEnabled")
                                    .setValue(checked)
                            }

                            Toast.makeText(
                                holder.itemView.context,
                                "PIN setting update failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
            }
            .addOnFailureListener {
                holder.pinText.text = "PIN: —"
                holder.pinSwitch.setOnCheckedChangeListener(null)
                holder.pinSwitch.isChecked = false
            }

        holder.setPinButton.setOnClickListener {
            showSetPinDialog(holder.itemView.context, employee)
        }

        // Keep the original employee-row navigation.
        holder.itemView.setOnClickListener {
            listener(employee)
        }
    }

    private fun showSetPinDialog(
        context: android.content.Context,
        employee: EmployeeItem
    ) {
        val input = android.widget.EditText(context).apply {
            hint = "Enter 4–8 digit PIN"
            inputType =
                InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
            filters =
                arrayOf(
                    android.text.InputFilter.LengthFilter(8)
                )
        }

        val dialog =
            AlertDialog.Builder(context)
                .setTitle("Set PIN — ${employee.name}")
                .setMessage(
                    "Enter this employee's attendance PIN."
                )
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val pin = input.text.toString().trim()

                    if (!pin.matches(Regex("\\d{4,8}"))) {
                        input.error =
                            "PIN must be 4–8 digits"
                        input.requestFocus()
                        return@setOnClickListener
                    }

                    val employeeRef =
                        FirebaseDatabase.getInstance()
                            .getReference("employees")
                            .child(employee.employeeId)

                    employeeRef
                        .child("attendancePin")
                        .setValue(pin)
                        .addOnSuccessListener {
                            // Saving a PIN automatically enables it.
                            employeeRef
                                .child("attendancePinEnabled")
                                .setValue(true)

                            dialog.dismiss()
                            Toast.makeText(
                                context,
                                "${employee.name}: PIN saved and enabled",
                                Toast.LENGTH_SHORT
                            ).show()

                            notifyItemChanged(
                                employeeList.indexOf(employee)
                            )
                        }
                        .addOnFailureListener {
                            Toast.makeText(
                                context,
                                "PIN save failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
        }

        dialog.show()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams
                .SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        input.requestFocus()
    }

    override fun getItemCount(): Int {
        return employeeList.size
    }
}