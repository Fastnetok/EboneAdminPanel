package com.example.eboneadminpanel

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/*
 * Exact copy of PendingSummaryAdapter.kt (Complaints), adapted only to
 * open NewConnectionEmployeeDetailsActivity (the existing New Connection
 * equivalent of PendingEmployeeComplaintsActivity) instead.
 */
class NewConnectionPendingSummaryAdapter(

    private val list:
    MutableList<String>

) : RecyclerView.Adapter<
        NewConnectionPendingSummaryAdapter.ViewHolder>() {

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
                    NewConnectionEmployeeDetailsActivity::class.java
                )

            intent.putExtra(
                "employeeName",
                employeeName
            )

            // NEW: tells NewConnectionEmployeeDetailsActivity to hide
            // the front-of-queue (Progress) item — only Pending should
            // exclude it. When opened from Total (NewConnectionSummaryAdapter),
            // this extra is absent, so everything shows, including the
            // Progress item.
            intent.putExtra(
                "pendingOnly",
                true
            )

            holder.itemView.context
                .startActivity(intent)

        }

    }

    override fun getItemCount(): Int {

        return list.size

    }

}