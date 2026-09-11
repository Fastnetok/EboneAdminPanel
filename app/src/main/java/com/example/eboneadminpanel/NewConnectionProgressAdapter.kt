package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NewConnectionProgressAdapter(
    private val list: MutableList<NewConnection>
) : RecyclerView.Adapter<NewConnectionProgressAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val employeeNameText: TextView =
            itemView.findViewById(R.id.employeeNameText)

        val customerNameText: TextView =
            itemView.findViewById(R.id.customerNameText)

        val addressText: TextView =
            itemView.findViewById(R.id.addressText)

        val phoneText: TextView =
            itemView.findViewById(R.id.phoneText)

        val assignedText: TextView =
            itemView.findViewById(R.id.assignedText)

        val runningTimeText: TextView =
            itemView.findViewById(R.id.runningTimeText)

        val seenTimeText: TextView =
            itemView.findViewById(R.id.seenTimeText)

        val seenStatusIcon: ImageView =
            itemView.findViewById(R.id.seenStatusIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_new_connection_progress,
            parent,
            false
        )
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

        val connection = list[position]

        holder.employeeNameText.text = connection.assignedTo
        holder.customerNameText.text = connection.customerName
        holder.addressText.text = connection.address
        holder.phoneText.text = connection.phoneNumber

        val formatter = SimpleDateFormat(
            "dd MMM yyyy / hh:mm a",
            Locale.getDefault()
        )

        holder.assignedText.text =
            "Assigned: " + formatter.format(Date(connection.assignedTime))

        val currentTime = System.currentTimeMillis()
        val diff = currentTime - connection.assignedTime
        val minutes = diff / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24

        holder.runningTimeText.text = when {
            days > 0 -> "Running: " + days + " Day"
            hours > 0 -> "Running: " + hours + " Hour"
            else -> "Running: " + minutes + " Minute"
        }

        // ---- Seen Status (Blue Double Tick) — same pattern as complaints ----
        if (connection.seenByEmployee) {

            holder.seenStatusIcon.setImageResource(R.drawable.ic_double_tick)

            val seenFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

            holder.seenTimeText.text = seenFormatter.format(Date(connection.seenTime))
            holder.seenTimeText.visibility = View.VISIBLE

        } else {

            holder.seenStatusIcon.setImageResource(R.drawable.ic_single_tick)
            holder.seenTimeText.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int {
        return list.size
    }
}