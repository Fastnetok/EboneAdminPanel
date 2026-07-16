package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MovementPointAdapter(
    private val points: MutableList<MovementPoint>
) : RecyclerView.Adapter<MovementPointAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rankBadge: TextView = view.findViewById(R.id.pointRankBadge)
        val timeText: TextView = view.findViewById(R.id.pointTimeText)
        val addressText: TextView = view.findViewById(R.id.pointAddressText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_movement_point, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val point = points[position]

        val badgeColor = when (position) {
            0 -> "#639922" // first point = green
            points.size - 1 -> "#E24B4A" // last point = red
            else -> "#EF9F27" // middle points = orange
        }
        holder.rankBadge.backgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(badgeColor))

        holder.timeText.text = TrackingStatistics.formatTime(point.timestamp)
        holder.addressText.text = point.address.ifEmpty { "Loading address..." }
    }

    override fun getItemCount() = points.size

    fun replaceAll(newPoints: List<MovementPoint>) {
        points.clear()
        points.addAll(newPoints)
        notifyDataSetChanged()
    }

    fun updateAddress(position: Int, address: String) {
        if (position in points.indices) {
            points[position].address = address
            notifyItemChanged(position)
        }
    }
}