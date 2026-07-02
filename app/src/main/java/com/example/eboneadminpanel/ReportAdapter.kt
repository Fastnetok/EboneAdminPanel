package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.content.Intent
import androidx.recyclerview.widget.RecyclerView

class ReportAdapter(
    private val reportList: MutableList<ReportItem>
) : RecyclerView.Adapter<ReportAdapter.ViewHolder>() {

    class ViewHolder(itemView: View)
        : RecyclerView.ViewHolder(itemView) {

        val employeeNameText: TextView =
            itemView.findViewById(R.id.employeeNameText)

        val assignedText: TextView =
            itemView.findViewById(R.id.assignedText)

        val pendingText: TextView =
            itemView.findViewById(R.id.pendingText)

        val progressText: TextView =
            itemView.findViewById(R.id.progressText)

        val resolvedText: TextView =
            itemView.findViewById(R.id.resolvedText)

        val successRateText: TextView =
            itemView.findViewById(R.id.successRateText)

        val repeatText: TextView =
            itemView.findViewById(R.id.repeatText)

        val todayCountText: TextView =
            itemView.findViewById(R.id.todayCountText)

        val weekCountText: TextView =
            itemView.findViewById(R.id.weekCountText)

        val monthCountText: TextView =
            itemView.findViewById(R.id.monthCountText)

        val averageTimeText: TextView =
            itemView.findViewById(R.id.averageTimeText)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {

        val view =
            LayoutInflater.from(parent.context)
                .inflate(
                    R.layout.item_report,
                    parent,
                    false
                )

        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {

        val report =
            reportList[position]

        holder.employeeNameText.text =
            report.employeeName

        holder.assignedText.text =
            "Assigned : ${report.assigned}"

        holder.pendingText.text =
            "Pending : ${report.pending}"

        holder.progressText.text =
            "Progress : ${report.progress}"

        holder.resolvedText.text =
            "Resolved : ${report.resolved}"

        holder.successRateText.text =
            "Success Rate : ${report.successRate}%"

        holder.repeatText.text =
            "⚠️ Repeat Complaints : ${report.repeatComplaints}"

        holder.todayCountText.text =
            "Today : ${report.todayCount}"

        holder.weekCountText.text =
            "Week : ${report.weekCount}"

        holder.monthCountText.text =
            "Month : ${report.monthCount}"
        holder.averageTimeText.text =
            "Average Time : ${report.averageTime}"
        holder.employeeNameText.setOnClickListener {

            val intent =
                Intent(
                    holder.itemView.context,
                    EmployeeReportDetailsActivity::class.java
                )

            intent.putExtra(
                "employeeName",
                report.employeeName
            )

            holder.itemView.context
                .startActivity(intent)
        }
        holder.employeeNameText.setOnClickListener {

            val intent =
                Intent(
                    holder.itemView.context,
                    EmployeeReportDetailsActivity::class.java
                )

            intent.putExtra(
                "employeeName",
                report.employeeName
            )

            holder.itemView.context
                .startActivity(intent)
        }
    }

    override fun getItemCount(): Int {

        return reportList.size

    }
}