package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EmployeeAdapter(

    private val employeeList: List<EmployeeItem>,

    private val listener: (EmployeeItem) -> Unit

) : RecyclerView.Adapter<EmployeeAdapter.ViewHolder>() {

    class ViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

        val nameText: TextView =
            itemView.findViewById(
                R.id.nameText
            )

        val statusText: TextView =
            itemView.findViewById(
                R.id.statusText
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
                R.layout.item_employee,
                parent,
                false
            )

        return ViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {

        val employee =
            employeeList[position]

        holder.nameText.text =
            employee.name

        holder.statusText.text =
            employee.status

        holder.itemView.setOnClickListener {

            listener(employee)

        }
    }

    override fun getItemCount(): Int {

        return employeeList.size

    }
}