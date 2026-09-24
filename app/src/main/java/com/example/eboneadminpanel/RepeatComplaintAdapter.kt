package com.example.eboneadminpanel

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RepeatComplaintAdapter(
    private val list: MutableList<Map<String, String>>,
    private val onSubHeaderClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SUB_HEADER = 1
        const val TYPE_ITEM = 2
    }

    override fun getItemViewType(position: Int): Int {
        val type = list[position]["type"]
        return when (type) {
            "header" -> TYPE_HEADER
            "sub_header" -> TYPE_SUB_HEADER
            else -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_repeat_header, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_SUB_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_repeat_sub_header, parent, false)
                SubHeaderViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_repeat_complaint, parent, false)
                ItemViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = list[position]
        when (holder) {
            is HeaderViewHolder -> {
                val headerText = item["headerText"]
                if (!headerText.isNullOrEmpty()) {
                    holder.userIdText.text = headerText
                } else {
                    val count = item["count"] ?: "0"
                    val days = item["days"] ?: "0"
                    val userId = item["userId"] ?: ""
                    holder.userIdText.text = if (userId.isNotEmpty()) "$userId — $count bar ($days din mein)" else "$count Repeat Complaints"
                }
            }
            is SubHeaderViewHolder -> {
                val title = item["title"] ?: "Customer"
                val repeatCount = item["repeatCount"]?.toIntOrNull() ?: 0
                val ncCount = item["ncCount"]?.toIntOrNull() ?: 0
                val isExpanded = item["isExpanded"] == "true"

                holder.tvSubHeaderTitle.text = title

                if (repeatCount > 0) {
                    holder.tvBadgeRepeat.text = "Repeat $repeatCount"
                    holder.tvBadgeRepeat.visibility = View.VISIBLE
                } else {
                    holder.tvBadgeRepeat.visibility = View.GONE
                }

                if (ncCount > 0) {
                    holder.tvBadgeNc.text = "NC $ncCount"
                    holder.tvBadgeNc.visibility = View.VISIBLE
                } else {
                    holder.tvBadgeNc.visibility = View.GONE
                }

                holder.tvSubExpandIndicator.text = if (isExpanded) "▲" else "▼"

                holder.subHeaderLayout.setOnClickListener {
                    onSubHeaderClick?.invoke(position)
                }
            }
            is ItemViewHolder -> {
                val isNcCard = item["isNcCard"] == "true"
                val createdTime = item["createdTime"]?.toLongOrNull() ?: 0L
                val dateStr = if (createdTime > 0) {
                    val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
                    "📅 " + sdf.format(Date(createdTime))
                } else {
                    "📅 N/A"
                }
                holder.dateText.text = dateStr
                holder.dateText.setTypeface(null, Typeface.BOLD)
                holder.dateText.setTextColor(Color.parseColor("#212121"))

                val userId = item["userId"] ?: ""
                holder.userIdText.text = userId
                holder.addressText.text = item["address"] ?: ""

                val phone = item["phoneNumber"] ?: ""
                holder.phoneText.text = if (phone.isNotEmpty()) "📞 $phone (Tap to Call)" else ""

                val rawStatus = item["status"] ?: ""
                if (isNcCard) {
                    holder.statusText.text = "🔴 NEW CONNECTION  •  Status: $rawStatus"
                    holder.statusText.setTypeface(null, Typeface.BOLD)
                    holder.statusText.setTextColor(Color.parseColor("#D32F2F"))
                } else {
                    holder.statusText.text = "Status: $rawStatus"
                    val color = when (rawStatus) {
                        "Resolved" -> Color.parseColor("#2E7D32")
                        "Progress" -> Color.parseColor("#1565C0")
                        else -> Color.parseColor("#E65100")
                    }
                    holder.statusText.setTextColor(color)
                }

                val employeeName = item["employeeName"]
                if (!employeeName.isNullOrEmpty()) {
                    holder.employeeText.text = "🧑‍🔧 Employee: $employeeName"
                    holder.employeeText.visibility = View.VISIBLE
                } else {
                    holder.employeeText.visibility = View.GONE
                }

                // Clickable phone number opens Phone Dialer
                holder.phoneText.setOnClickListener {
                    if (phone.isNotEmpty()) {
                        try {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                            holder.itemView.context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(holder.itemView.context, "Unable to open dialer", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // Click listener on card item to open details
                holder.itemView.setOnClickListener {
                    val ctx = holder.itemView.context
                    val title = if (isNcCard) "⭐ New Connection Details" else "📋 Complaint Details"
                    val msg = StringBuilder()
                    msg.append("User/Customer: $userId\n")
                    if (!employeeName.isNullOrEmpty()) msg.append("Employee: $employeeName\n")
                    msg.append("Address: ${item["address"]}\n")
                    msg.append("Phone: $phone\n")
                    msg.append("Status: $rawStatus\n")
                    msg.append("Date: $dateStr\n")
                    item["details"]?.let { if (it.isNotEmpty()) msg.append("Details: $it\n") }

                    AlertDialog.Builder(ctx)
                        .setTitle(title)
                        .setMessage(msg.toString())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    override fun getItemCount() = list.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val userIdText: TextView = view.findViewById(R.id.headerUserIdText)
    }

    class SubHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSubHeaderTitle: TextView = view.findViewById(R.id.tvSubHeaderTitle)
        val tvBadgeRepeat: TextView = view.findViewById(R.id.tvBadgeRepeat)
        val tvBadgeNc: TextView = view.findViewById(R.id.tvBadgeNc)
        val tvSubExpandIndicator: TextView = view.findViewById(R.id.tvSubExpandIndicator)
        val subHeaderLayout: View = view.findViewById(R.id.repeatSubHeaderLayout)
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val employeeText: TextView = view.findViewById(R.id.itemEmployeeText)
        val userIdText: TextView = view.findViewById(R.id.itemUserIdText)
        val addressText: TextView = view.findViewById(R.id.itemAddressText)
        val phoneText: TextView = view.findViewById(R.id.itemPhoneText)
        val dateText: TextView = view.findViewById(R.id.itemDateText)
        val statusText: TextView = view.findViewById(R.id.itemStatusText)
    }
}
