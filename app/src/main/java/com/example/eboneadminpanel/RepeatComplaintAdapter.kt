package com.example.eboneadminpanel

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

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
            holder.userIdText.text =
                "👤 ${item["userId"]} — ${item["count"]} baar"
        } else if (holder is ItemViewHolder) {
            holder.addressText.text = "📍 ${item["address"]}"
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
        val addressText: TextView = view.findViewById(R.id.itemAddressText)
        val statusText: TextView = view.findViewById(R.id.itemStatusText)
    }
}