package com.example.eboneadminpanel

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FuelLogEntry(
    val employeeName: String,
    val amount: Double,
    val type: String,
    val newBalance: Double,
    val timestamp: Long
)

class FuelLogAdapter(
    private val entries: MutableList<FuelLogEntry>
) : RecyclerView.Adapter<FuelLogAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val employeeText: TextView = view.findViewById(R.id.logEmployeeText)
        val dateTimeText: TextView = view.findViewById(R.id.logDateTimeText)
        val typeText: TextView = view.findViewById(R.id.logTypeText)
        val amountText: TextView = view.findViewById(R.id.logAmountText)
        val balanceText: TextView = view.findViewById(R.id.logBalanceText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fuel_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]

        holder.employeeText.text = "👤 ${entry.employeeName}"
        holder.dateTimeText.text = dateFormat.format(Date(entry.timestamp))
        holder.typeText.text = entry.type

        val isPositive = entry.amount >= 0
        holder.amountText.text = if (isPositive)
            "+Rs %.0f".format(entry.amount)
        else
            "-Rs %.0f".format(kotlin.math.abs(entry.amount))
        holder.amountText.setTextColor(
            Color.parseColor(if (isPositive) "#2E7D32" else "#C62828")
        )

        holder.typeText.setTextColor(
            Color.parseColor(
                when (entry.type) {
                    "Add" -> "#1565C0"
                    "Revert" -> "#E65100"
                    "Auto-Deduction" -> "#6A1B9A"
                    else -> "#757575"
                }
            )
        )

        holder.balanceText.text = "Balance: Rs %.0f".format(entry.newBalance)
    }

    override fun getItemCount() = entries.size

    fun replaceAll(newEntries: List<FuelLogEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }
}