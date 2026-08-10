package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MovementPointAdapter(
    private val points: MutableList<MovementPoint>,
    private val onItemClick: (Int) -> Unit = {}
) : RecyclerView.Adapter<MovementPointAdapter.ViewHolder>() {

    private var highlightedPosition: Int = -1

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rootView: View = view
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

        // Highlight the point currently being animated during Play Movement
        holder.rootView.setBackgroundColor(
            if (position == highlightedPosition)
                android.graphics.Color.parseColor("#332979FF")
            else
                android.graphics.Color.TRANSPARENT
        )

        holder.rootView.setOnClickListener {
            onItemClick(position)
        }
    }

    override fun getItemCount() = points.size

    fun replaceAll(newPoints: List<MovementPoint>) {
        points.clear()
        points.addAll(newPoints)
        highlightedPosition = -1
        notifyDataSetChanged()
    }

    fun updateAddress(position: Int, address: String) {
        if (position in points.indices) {
            points[position].address = address
            notifyItemChanged(position)
        }
    }

    // Highlights the point currently reached during Play Movement animation
    fun setHighlighted(position: Int) {
        val old = highlightedPosition
        highlightedPosition = position
        if (old in points.indices) notifyItemChanged(old)
        if (position in points.indices) notifyItemChanged(position)
    }

    fun clearHighlight() {
        setHighlighted(-1)
    }
}