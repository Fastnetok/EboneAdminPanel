package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ComplaintAdapter(
    private val complaintList: MutableList<Complaint>
) : RecyclerView.Adapter<ComplaintAdapter.ViewHolder>() {

    val selectedComplaints =
        mutableListOf<Complaint>()

    class ViewHolder(itemView: View)
        : RecyclerView.ViewHolder(itemView) {

        val checkComplaint: CheckBox =
            itemView.findViewById(
                R.id.checkComplaint
            )

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

        val assignedToText: TextView =
            itemView.findViewById(
                R.id.assignedToText
            )

        val statusText: TextView =
            itemView.findViewById(
                R.id.statusText
            )

        val dateText: TextView =
            itemView.findViewById(
                R.id.dateText
            )
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {

        val view =
            LayoutInflater.from(parent.context)
                .inflate(
                    R.layout.item_complaint,
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

        holder.assignedToText.text =
            "Assigned To : " +
                    if (
                        complaint.assignedTo.isEmpty()
                    )
                        "Not Assigned"
                    else
                        complaint.assignedTo

        holder.statusText.text =
            "Status : " +
                    complaint.status

        val dateFormat =
            SimpleDateFormat(
                "dd MMM yyyy / hh:mm a",
                Locale.getDefault()
            )

        holder.dateText.text =
            dateFormat.format(
                Date(
                    complaint.createdTime
                )
            )

        holder.checkComplaint.setOnCheckedChangeListener(null)

        holder.checkComplaint.isChecked =
            selectedComplaints.contains(
                complaint
            )

        holder.checkComplaint
            .setOnCheckedChangeListener { _, isChecked ->

                if (isChecked) {

                    if (
                        !selectedComplaints.contains(
                            complaint
                        )
                    ) {

                        selectedComplaints.add(
                            complaint
                        )
                    }

                } else {

                    selectedComplaints.remove(
                        complaint
                    )
                }
            }
    }

    override fun getItemCount(): Int {

        return complaintList.size

    }
}