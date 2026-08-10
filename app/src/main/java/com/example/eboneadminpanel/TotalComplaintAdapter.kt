package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.content.Intent
import android.content.Context

class TotalComplaintAdapter(

    private val list:
    MutableList<EmployeeComplaintSummary>

) : RecyclerView.Adapter<
        TotalComplaintAdapter.ViewHolder>() {

    class ViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

        val employeeNameText:
                TextView =
            itemView.findViewById(
                R.id.employeeNameText
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
                R.layout.item_total_complaint,
                parent,
                false
            )

        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {

        val item =
            list[position]

        holder.employeeNameText.text =

            item.employeeName +
                    " (" +
                    item.totalComplaints +
                    ")"
        holder.itemView.setOnClickListener {

            val intent =
                Intent(
                    holder.itemView.context,
                    EmployeeComplaintsActivity::class.java
                )

            intent.putExtra(
                "employeeName",
                item.employeeName
            )

            holder.itemView.context
                .startActivity(intent)

        }
    }

    override fun getItemCount(): Int {

        return list.size
    }

}