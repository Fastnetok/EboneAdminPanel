package com.example.eboneadminpanel

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PendingSummaryAdapter(

    private val list:
    MutableList<String>

) : RecyclerView.Adapter<
        PendingSummaryAdapter.ViewHolder>() {

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
                R.layout.item_pending_summary,
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
            item

        holder.itemView.setOnClickListener {

            val employeeName =
                item.substringBefore(
                    " ("
                )

            val intent =
                Intent(
                    holder.itemView.context,
                    PendingEmployeeComplaintsActivity::class.java
                )

            intent.putExtra(
                "employeeName",
                employeeName
            )

            holder.itemView.context
                .startActivity(intent)

        }

    }

    override fun getItemCount(): Int {

        return list.size

    }

}