package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ApprovedDeviceAdapter(

    private val deviceList:
    MutableList<ApprovedDevice>

) : RecyclerView.Adapter<
        ApprovedDeviceAdapter.ViewHolder>() {

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
            device.mobile

        holder.statusText.text =
            device.status
    }

    override fun getItemCount():
            Int {

        return deviceList.size
    }
}