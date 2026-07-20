package com.example.eboneadminpanel

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class PendingDeviceAdapter(

    private val deviceList:
    MutableList<PendingDevice>

) : RecyclerView.Adapter<
        PendingDeviceAdapter.ViewHolder>() {

    class ViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

        val nameText: TextView =
            itemView.findViewById(R.id.nameText)

        val mobileText: TextView =
            itemView.findViewById(R.id.mobileText)

        val statusText: TextView =
            itemView.findViewById(R.id.statusText)

        val approveButton: Button =
            itemView.findViewById(R.id.approveButton)

        val deleteButton: Button =
            itemView.findViewById(R.id.deleteButton)

        val blockButton: Button =
            itemView.findViewById(R.id.blockButton)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {

        val view =
            LayoutInflater.from(parent.context).inflate(
                R.layout.item_pending_device,
                parent,
                false
            )

        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {

        val device = deviceList[position]

        holder.nameText.text = device.employeeName
        holder.mobileText.text = device.mobileNumber
        holder.statusText.text = device.status

        holder.approveButton.setOnClickListener {
            checkForDuplicateThenApprove(holder, device)
        }

        holder.deleteButton.setOnClickListener {
            FirebaseDatabase.getInstance()
                .getReference("PendingDevices")
                .child(device.androidId)
                .removeValue()
        }

        holder.blockButton.setOnClickListener {
            FirebaseDatabase.getInstance()
                .getReference("PendingDevices")
                .child(device.androidId)
                .child("status")
                .setValue("Blocked")
        }
    }

    // NEW: before approving, check if any ALREADY APPROVED device has the
    // same employeeName AND mobileNumber. If so, warn the admin and let
    // them decide instead of silently allowing a duplicate.
    private fun checkForDuplicateThenApprove(
        holder: ViewHolder,
        device: PendingDevice
    ) {
        FirebaseDatabase.getInstance()
            .getReference("ApprovedDevices")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {

                    var duplicateAndroidId: String? = null

                    for (child in snapshot.children) {
                        val existingName = child.child("employeeName")
                            .getValue(String::class.java) ?: ""
                        val existingMobile = child.child("mobileNumber")
                            .getValue(String::class.java) ?: ""

                        if (existingName.equals(device.employeeName, ignoreCase = true) &&
                            existingMobile == device.mobileNumber
                        ) {
                            duplicateAndroidId = child.key
                            break
                        }
                    }

                    if (duplicateAndroidId != null) {
                        AlertDialog.Builder(holder.itemView.context)
                            .setTitle("Duplicate Employee")
                            .setMessage(
                                "${device.employeeName} (${device.mobileNumber}) pehle se " +
                                        "ek doosre device (ID: $duplicateAndroidId) mein Approved hai.\n\n" +
                                        "Kya aap phir bhi is naye device ko approve karna chahte hain?"
                            )
                            .setPositiveButton("Haan, Approve karein") { _, _ ->
                                approveDevice(holder, device)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        approveDevice(holder, device)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // If the duplicate check itself fails (e.g. network),
                    // fall back to approving directly rather than blocking
                    // the admin entirely.
                    approveDevice(holder, device)
                }
            })
    }

    private fun approveDevice(
        holder: ViewHolder,
        device: PendingDevice
    ) {
        val approvedData = hashMapOf(
            "androidId" to device.androidId,
            "employeeName" to device.employeeName,
            "mobileNumber" to device.mobileNumber,
            "status" to "Approved",
            "uid" to device.uid
        )

        FirebaseDatabase.getInstance()
            .getReference("ApprovedDevices")
            .child(device.androidId)
            .setValue(approvedData)

        FirebaseDatabase.getInstance()
            .getReference("PendingDevices")
            .child(device.androidId)
            .removeValue()

        holder.statusText.text = "APPROVED"
    }

    override fun getItemCount(): Int {
        return deviceList.size
    }
}