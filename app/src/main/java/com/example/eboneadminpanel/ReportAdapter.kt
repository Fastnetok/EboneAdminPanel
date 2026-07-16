package com.example.eboneadminpanel

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ReportHeaderData(
    val topEmployeesText: String = "🏆 TOP EMPLOYEES",
    val topEmployeesVisible: Boolean = false,
    val summaryText: String = "📅 TODAY REPORT",
    val summaryVisible: Boolean = true,
    val repeatText: String = "⚠️ REPEAT COMPLAINTS",
    val repeatVisible: Boolean = false,
    val areaReportText: String = "📍 AREA-WISE COMPLAINTS",
    val areaReportVisible: Boolean = false
)

data class AreaReportItem(
    val rank: Int,
    val areaName: String,
    val totalComplaints: Int,
    val resolvedCount: Int,
    val pendingCount: Int,
    val successRate: Int
)

private const val TYPE_HEADER = 0
private const val TYPE_ITEM = 1
private const val TYPE_AREA_ITEM = 2

class ReportAdapter(
    private val reportList: MutableList<ReportItem>,
    private val onRepeatClick: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var headerData = ReportHeaderData()
    private var areaList: List<AreaReportItem> = emptyList()

    // ---------- Area list updates (called from ReportsActivity) ----------

    fun updateAreaList(list: List<AreaReportItem>) {
        areaList = list
        notifyDataSetChanged()
    }

    // ---------- Header updates (called from ReportsActivity) ----------

    fun updateHeader(data: ReportHeaderData) {
        headerData = data
        notifyItemChanged(0)
    }

    // ---------- View types ----------

    override fun getItemViewType(position: Int): Int {
        if (position == 0) return TYPE_HEADER
        return if (areaList.isNotEmpty()) TYPE_AREA_ITEM else TYPE_ITEM
    }

    override fun getItemCount(): Int {
        val bodyCount = if (areaList.isNotEmpty()) areaList.size else reportList.size
        return bodyCount + 1 // +1 for header
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_report_header, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_AREA_ITEM -> {
                val view = inflater.inflate(R.layout.item_area_card, parent, false)
                AreaViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_report, parent, false)
                ItemViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind(headerData, onRepeatClick)
            is AreaViewHolder -> holder.bind(areaList[position - 1])
            is ItemViewHolder -> holder.bind(reportList[position - 1])
        }
    }

    // ---------- Header ViewHolder ----------

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val topEmployeeText: TextView = itemView.findViewById(R.id.topEmployeeText)
        private val overallSummaryText: TextView = itemView.findViewById(R.id.overallSummaryText)
        private val repeatReportText: TextView = itemView.findViewById(R.id.repeatReportText)
        private val areaReportText: TextView = itemView.findViewById(R.id.areaReportText)

        fun bind(data: ReportHeaderData, onRepeatClick: () -> Unit) {
            topEmployeeText.text = data.topEmployeesText
            topEmployeeText.visibility = if (data.topEmployeesVisible) View.VISIBLE else View.GONE

            overallSummaryText.text = data.summaryText
            overallSummaryText.visibility = if (data.summaryVisible) View.VISIBLE else View.GONE

            repeatReportText.text = data.repeatText
            repeatReportText.visibility = if (data.repeatVisible) View.VISIBLE else View.GONE
            repeatReportText.setOnClickListener { onRepeatClick() }

            areaReportText.text = data.areaReportText
            areaReportText.visibility = if (data.areaReportVisible) View.VISIBLE else View.GONE
        }
    }

    // ---------- Employee card ViewHolder ----------

    class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val employeeNameText: TextView = itemView.findViewById(R.id.employeeNameText)
        val assignedText: TextView = itemView.findViewById(R.id.assignedText)
        val pendingText: TextView = itemView.findViewById(R.id.pendingText)
        val progressText: TextView = itemView.findViewById(R.id.progressText)
        val resolvedText: TextView = itemView.findViewById(R.id.resolvedText)
        val successRateText: TextView = itemView.findViewById(R.id.successRateText)
        val repeatText: TextView = itemView.findViewById(R.id.repeatText)
        val averageTimeText: TextView = itemView.findViewById(R.id.averageTimeText)
        val weekCountText: TextView = itemView.findViewById(R.id.weekCountText)
        val monthCountText: TextView = itemView.findViewById(R.id.monthCountText)
        val starsText: TextView = itemView.findViewById(R.id.starsText)
        val todayCountText: TextView = itemView.findViewById(R.id.todayCountText)

        fun bind(report: ReportItem) {
            employeeNameText.text = "👤 ${report.employeeName}"

            assignedText.text = "Total Assigned      : ${report.assigned}"
            resolvedText.text = "Resolved            : ${report.resolved}"
            pendingText.text = "Pending             : ${report.pending}"
            progressText.text = "In Progress         : ${report.progress}"
            successRateText.text = "Success Rate        : ${report.successRate}%"
            repeatText.text = "Repeat Complaints   : ${report.repeatComplaints}"
            averageTimeText.text = "Avg Resolve Time    : ${report.averageTime}"

            weekCountText.text =
                "Assigned : ${report.weekAssigned}\n" +
                        "Resolved : ${report.weekResolved}\n" +
                        "Pending  : ${report.weekPending}"

            monthCountText.text =
                "Assigned     : ${report.monthAssigned}\n" +
                        "Resolved     : ${report.monthResolved}\n" +
                        "Pending      : ${report.monthPending}\n" +
                        "Success Rate : ${report.monthSuccessRate}%"

            val stars = when {
                report.successRate >= 95 -> "⭐⭐⭐⭐⭐ ${report.successRate}"
                report.successRate >= 85 -> "⭐⭐⭐⭐ ${report.successRate}"
                report.successRate >= 70 -> "⭐⭐⭐ ${report.successRate}"
                report.successRate >= 50 -> "⭐⭐ ${report.successRate}"
                else -> "⭐ ${report.successRate}"
            }
            starsText.text = stars

            repeatText.setOnClickListener {
                val intent = Intent(itemView.context, EmployeeReportDetailsActivity::class.java)
                intent.putExtra("employeeName", report.employeeName)
                intent.putExtra("showRepeat", true)
                itemView.context.startActivity(intent)
            }

            employeeNameText.setOnClickListener {
                val intent = Intent(itemView.context, EmployeeReportDetailsActivity::class.java)
                intent.putExtra("employeeName", report.employeeName)
                intent.putExtra("showRepeat", false)
                itemView.context.startActivity(intent)
            }
        }
    }

    // ---------- Area card ViewHolder ----------

    class AreaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rankBadge: TextView = itemView.findViewById(R.id.rankBadge)
        private val areaNameText: TextView = itemView.findViewById(R.id.areaNameText)
        private val totalChip: TextView = itemView.findViewById(R.id.totalChip)
        private val resolvedChip: TextView = itemView.findViewById(R.id.resolvedChip)
        private val pendingChip: TextView = itemView.findViewById(R.id.pendingChip)
        private val areaProgressBar: android.widget.ProgressBar =
            itemView.findViewById(R.id.areaProgressBar)
        private val areaPercentText: TextView = itemView.findViewById(R.id.areaPercentText)

        fun bind(area: AreaReportItem) {
            rankBadge.text = area.rank.toString()

            // Top 3 areas get gold/silver/bronze badge colors, rest stay blue
            val badgeColor = when (area.rank) {
                1 -> "#FFB300" // gold
                2 -> "#9E9E9E" // silver
                3 -> "#D2691E" // bronze
                else -> "#1976D2"
            }
            rankBadge.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor(badgeColor))

            areaNameText.text = area.areaName

            totalChip.text = "Total: ${area.totalComplaints}"
            totalChip.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#E3F2FD"))

            resolvedChip.text = "Resolved: ${area.resolvedCount}"
            resolvedChip.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#E8F5E9"))

            pendingChip.text = "Pending: ${area.pendingCount}"
            pendingChip.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#FFF3E0"))

            areaProgressBar.progress = area.successRate
            areaPercentText.text = "${area.successRate}% Resolved"
        }
    }
}