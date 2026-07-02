package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AssignComplaintAdapter(

    private val complaintList:
    MutableList<Complaint>

) : RecyclerView.Adapter<
        AssignComplaintAdapter.ViewHolder>() {

    class ViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

        val userIdText: TextView =
            itemView.findViewById(
                R.id.userIdText
            )

        val addressText: TextView =
            itemView.findViewById(
                R.id.addressText
            )

        val phoneText: TextView =
            itemView.findViewById(
                R.id.phoneText
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
                R.layout.item_assign_complaint,
                parent,
                false
            )

        return ViewHolder(view)

    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {

        val complaint =
            complaintList[position]

        holder.userIdText.text =
            complaint.userId

        holder.addressText.text =
            complaint.address

        holder.phoneText.text =
            complaint.phoneNumber

    }

    override fun getItemCount(): Int {

        return complaintList.size

    }

}