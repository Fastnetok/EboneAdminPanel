package com.example.eboneadminpanel

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RepeatComplaintAdapter(
    private val list: MutableList<Map<String, String>>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (list[position]["type"] == "header") TYPE_HEADER else TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_repeat_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_repeat_complaint, parent, false)
            ItemViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = list[position]
        if (holder is HeaderViewHolder) {
            val count = item["count"] ?: "0"
            val days = item["days"] ?: "0"
            holder.userIdText.text = "${item["userId"]} — $count bar ($days din mein)"
        } else if (holder is ItemViewHolder) {
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
            holder.dateText.setTypeface(null, Typeface.BOLD)

            holder.userIdText.text = item["userId"] ?: ""
            holder.addressText.text = item["address"] ?: ""
            holder.phoneText.text = item["phoneNumber"] ?: ""
            holder.statusText.text = "Status: ${item["status"]}"
            val color = when (item["status"]) {
                "Resolved" -> Color.parseColor("#2E7D32")
                "Progress" -> Color.parseColor("#1565C0")
                else -> Color.parseColor("#E65100")
            }
            holder.statusText.setTextColor(color)

            // Employee name only present in ALL-employees mode; hidden otherwise
            val employeeName = item["employeeName"]
            if (!employeeName.isNullOrEmpty()) {
                holder.employeeText.text = "🧑‍🔧 Employee: $employeeName"
                holder.employeeText.visibility = View.VISIBLE
            } else {
                holder.employeeText.visibility = View.GONE
            }
        }
    }

    override fun getItemCount() = list.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val userIdText: TextView = view.findViewById(R.id.headerUserIdText)
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