package com.example.eboneadminpanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class AutoMonitorAdapter(
    private var employeeList: List<EmployeeItem>
) : RecyclerView.Adapter<AutoMonitorAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvEmployeeName)
        val tvStatus: TextView = view.findViewById(R.id.tvEmployeeStatus)
        val switchMonitor: Switch = view.findViewById(R.id.switchMonitor)
        val tvEboneCount: TextView = view.findViewById(R.id.tvEboneCount)
        val tvZongCount: TextView = view.findViewById(R.id.tvZongCount)
        val tvWateenCount: TextView = view.findViewById(R.id.tvWateenCount)
        val viewStatusLed: View = view.findViewById(R.id.viewStatusLed)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_auto_monitor_employee, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val employee = employeeList[position]
        holder.tvName.text = employee.name
        holder.tvStatus.text = "Status: ${employee.status}"
        holder.tvStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32")) // Green for online

        // Use the reactive state passed from the Activity
        holder.switchMonitor.setOnCheckedChangeListener(null)
        holder.switchMonitor.isChecked = employee.isMonitored
        
        holder.switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            val ref = FirebaseDatabase.getInstance().getReference("officeSettings")
                .child("auto_monitor")
            
            ref.child("monitored_employees")
                .child(employee.employeeId)
                .setValue(isChecked)

            if (!isChecked) {
                // Reset LED if employee monitoring is disabled
                ref.child("live_status").child(employee.employeeId).removeValue()
            }
        }

        // Fetch Complaint Counts for this employee
        fetchComplaintCounts(employee.name, holder)

        // Load Live Status LED
        loadLiveStatusLed(employee.employeeId, holder)
    }

    private fun loadLiveStatusLed(employeeId: String, holder: ViewHolder) {
        FirebaseDatabase.getInstance().getReference("officeSettings")
            .child("auto_monitor")
            .child("live_status")
            .child(employeeId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val status = snapshot.child("result").value?.toString() ?: "idle"
                    val isChecking = snapshot.child("checking").getValue(Boolean::class.java) ?: false
                    
                    if (isChecking) {
                        holder.viewStatusLed.setBackgroundResource(R.drawable.bg_circle_amber)
                        startBlinkingAnimation(holder.viewStatusLed)
                    } else {
                        holder.viewStatusLed.clearAnimation()
                        when (status) {
                            "success" -> holder.viewStatusLed.setBackgroundResource(R.drawable.bg_circle_green)
                            "failed" -> holder.viewStatusLed.setBackgroundResource(R.drawable.bg_circle_red)
                            else -> holder.viewStatusLed.setBackgroundResource(R.drawable.bg_circle_grey)
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun startBlinkingAnimation(view: View) {
        val anim = android.view.animation.AlphaAnimation(1.0f, 0.2f)
        anim.duration = 500
        anim.repeatMode = android.view.animation.Animation.REVERSE
        anim.repeatCount = android.view.animation.Animation.INFINITE
        view.startAnimation(anim)
    }

    private fun fetchComplaintCounts(employeeName: String, holder: ViewHolder) {
        FirebaseDatabase.getInstance().getReference("complaints")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var ebone = 0
                    var zong = 0
                    var wateen = 0

                    for (child in snapshot.children) {
                        val assignedTo = child.child("assignedTo").getValue(String::class.java) ?: ""
                        val status = child.child("status").getValue(String::class.java) ?: ""
                        val company = child.child("company").getValue(String::class.java) ?: ""

                        if (assignedTo == employeeName && status != "Resolved") {
                            when (company) {
                                "EBONE" -> ebone++
                                "ZONG" -> zong++
                                "WATEEN" -> wateen++
                            }
                        }
                    }

                    holder.tvEboneCount.text = ebone.toString()
                    holder.tvZongCount.text = zong.toString()
                    holder.tvWateenCount.text = wateen.toString()
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    override fun getItemCount(): Int = employeeList.size

    fun updateList(newList: List<EmployeeItem>) {
        this.employeeList = newList
        notifyDataSetChanged()
    }
}
