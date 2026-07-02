package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase

class PendingDeviceAdapter(

    private val deviceList:
    MutableList<PendingDevice>

) : RecyclerView.Adapter<
        PendingDeviceAdapter.ViewHolder>() {

    class ViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

        val nameText: TextView =
            itemView.findViewById(
                R.id.nameText
            )

        val mobileText: TextView =
            itemView.findViewById(
                R.id.mobileText
            )

        val statusText: TextView =
            itemView.findViewById(
                R.id.statusText
            )

        val approveButton: Button =
            itemView.findViewById(
                R.id.approveButton
            )

        val deleteButton: Button =
            itemView.findViewById(
                R.id.deleteButton
            )

        val blockButton: Button =
            itemView.findViewById(
                R.id.blockButton
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

        val device =
            deviceList[position]

        holder.nameText.text =
            device.employeeName

        holder.mobileText.text =
            device.mobileNumber

        holder.statusText.text =
            device.status

        holder.approveButton.setOnClickListener {

            val approvedData = hashMapOf(

                "androidId" to device.androidId,

                "employeeName" to device.employeeName,

                "mobileNumber" to device.mobileNumber,

                "status" to "Approved"

            )

            FirebaseDatabase
                .getInstance()
                .getReference("ApprovedDevices")
                .child(device.androidId)
                .setValue(approvedData)

            FirebaseDatabase
                .getInstance()
                .getReference("PendingDevices")
                .child(device.androidId)
                .removeValue()

            holder.statusText.text =
                "APPROVED"
        }

        holder.deleteButton.setOnClickListener {

            FirebaseDatabase
                .getInstance()
                .getReference("PendingDevices")
                .child(device.androidId)
                .removeValue()
        }

        holder.blockButton.setOnClickListener {

            FirebaseDatabase
                .getInstance()
                .getReference("PendingDevices")
                .child(device.androidId)
                .child("status")
                .setValue("Blocked")
        }
    }

    override fun getItemCount():
            Int {

        return deviceList.size
    }
}