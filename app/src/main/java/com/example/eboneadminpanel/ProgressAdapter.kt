package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProgressAdapter(

    private val list:
    MutableList<Complaint>

) : RecyclerView.Adapter<
        ProgressAdapter.ViewHolder>() {

    class ViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

        val employeeNameText:
                TextView =
            itemView.findViewById(
                R.id.employeeNameText
            )

        val userIdText:
                TextView =
            itemView.findViewById(
                R.id.userIdText
            )

        val addressText:
                TextView =
            itemView.findViewById(
                R.id.addressText
            )

        val phoneText:
                TextView =
            itemView.findViewById(
                R.id.phoneText
            )

        val assignedText:
                TextView =
            itemView.findViewById(
                R.id.assignedText
            )

        val runningTimeText:
                TextView =
            itemView.findViewById(
                R.id.runningTimeText
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
                R.layout.item_progress,
                parent,
                false
            )

        return ViewHolder(view)

    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {

        val complaint =
            list[position]

        holder.employeeNameText.text =
            complaint.assignedTo

        holder.userIdText.text =
            complaint.userId

        holder.addressText.text =
            complaint.address

        holder.phoneText.text =
            complaint.phoneNumber

        val formatter =
            SimpleDateFormat(
                "dd MMM yyyy / hh:mm a",
                Locale.getDefault()
            )

        holder.assignedText.text =

            "Assigned: " +

                    formatter.format(

                        Date(
                            complaint.assignedTime
                        )

                    )

        val currentTime =
            System.currentTimeMillis()

        val diff =
            currentTime -
                    complaint.assignedTime

        val minutes =
            diff / (1000 * 60)

        val hours =
            minutes / 60

        val days =
            hours / 24

        holder.runningTimeText.text =

            when {

                days > 0 ->

                    "Running: " +
                            days +
                            " Day"

                hours > 0 ->

                    "Running: " +
                            hours +
                            " Hour"

                else ->

                    "Running: " +
                            minutes +
                            " Minute"

            }

    }

    override fun getItemCount(): Int {

        return list.size

    }

}