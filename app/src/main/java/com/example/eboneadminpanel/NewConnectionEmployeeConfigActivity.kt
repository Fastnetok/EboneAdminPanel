package com.example.eboneadminpanel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class NewConnectionEmployeeConfigActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private val employeeList = mutableListOf<EmployeeStatus>()
    private val activeRefs = FirebaseDatabase.getInstance().getReference("officeSettings/new_connections/active_employees")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_connection_employee_config)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        recycler = findViewById(R.id.recyclerEmployeeConfig)
        recycler.layoutManager = LinearLayoutManager(this)

        loadEmployees()
    }

    private fun loadEmployees() {
        FirebaseDatabase.getInstance().getReference("employees").get().addOnSuccessListener { empSnapshot ->
            activeRefs.get().addOnSuccessListener { activeSnapshot ->
                employeeList.clear()
                for (child in empSnapshot.children) {
                    val name = child.child("employeeName").getValue(String::class.java) ?: ""
                    if (name.isNotEmpty()) {
                        val isActive = activeSnapshot.child(name).getValue(Boolean::class.java) ?: true
                        employeeList.add(EmployeeStatus(name, isActive))
                    }
                }
                recycler.adapter = ConfigAdapter(employeeList) { name, status ->
                    activeRefs.child(name).setValue(status)
                }
            }
        }
    }

    data class EmployeeStatus(val name: String, var isActive: Boolean)

    class ConfigAdapter(private val list: List<EmployeeStatus>, val onToggle: (String, Boolean) -> Unit) : RecyclerView.Adapter<ConfigAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvEmployeeName)
            val switch: SwitchCompat = v.findViewById(R.id.switchActive)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_employee_config, parent, false))
        override fun getItemCount(): Int = list.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.name.text = item.name
            holder.switch.isChecked = item.isActive
            holder.switch.setOnCheckedChangeListener { _, isChecked -> onToggle(item.name, isChecked) }
        }
    }
}
