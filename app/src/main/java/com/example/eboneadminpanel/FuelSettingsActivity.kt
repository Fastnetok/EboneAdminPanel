package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class FuelSettingsActivity : AppCompatActivity() {

    private lateinit var fuelPriceEditText: EditText
    private lateinit var saveFuelPriceButton: Button

    private lateinit var fuelEmployeeSpinner: Spinner
    private lateinit var bikeAverageEditText: EditText
    private lateinit var saveBikeAverageButton: Button

    // employeeId -> employeeName, in the same order as the spinner
    private val employeeIds = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fuel_settings)

        fuelPriceEditText = findViewById(R.id.fuelPriceEditText)
        saveFuelPriceButton = findViewById(R.id.saveFuelPriceButton)
        fuelEmployeeSpinner = findViewById(R.id.fuelEmployeeSpinner)
        bikeAverageEditText = findViewById(R.id.bikeAverageEditText)
        saveBikeAverageButton = findViewById(R.id.saveBikeAverageButton)

        loadCurrentFuelPrice()
        loadEmployeeList()

        saveFuelPriceButton.setOnClickListener {
            saveFuelPrice()
        }

        saveBikeAverageButton.setOnClickListener {
            saveBikeAverage()
        }

        fuelEmployeeSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    loadBikeAverageForSelectedEmployee()

                    // Keep both screens in sync — if admin picks a different
                    // employee here, Movement Tracking should default to the
                    // same one next time it's opened, too.
                    if (position in employeeIds.indices) {
                        getSharedPreferences("MovementTrackingPrefs", MODE_PRIVATE)
                            .edit()
                            .putString("selectedEmployeeId", employeeIds[position])
                            .apply()
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    // ---------- Petrol Price ----------

    private fun loadCurrentFuelPrice() {
        FirebaseDatabase.getInstance()
            .getReference("fuelSettings")
            .child("fuelPricePerLiter")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val price = snapshot.getValue(Double::class.java)
                    if (price != null) {
                        fuelPriceEditText.setText(price.toString())
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun saveFuelPrice() {
        val priceText = fuelPriceEditText.text.toString().trim()
        val price = priceText.toDoubleOrNull()

        if (price == null || price <= 0) {
            Toast.makeText(this, "Sahi price likhein (jaise 280)", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseDatabase.getInstance()
            .getReference("fuelSettings")
            .child("fuelPricePerLiter")
            .setValue(price)
            .addOnSuccessListener {
                Toast.makeText(this, "Fuel price save ho gaya: Rs $price", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Save nahi hua, dobara koshish karein", Toast.LENGTH_SHORT).show()
            }
    }

    // ---------- Bike Average (per employee) ----------

    private fun loadEmployeeList() {
        FirebaseDatabase.getInstance()
            .getReference("employees")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    employeeIds.clear()
                    val names = mutableListOf<String>()

                    for (emp in snapshot.children) {
                        val id = emp.key ?: continue
                        val name = emp.child("employeeName").getValue(String::class.java) ?: "Employee"
                        employeeIds.add(id)
                        names.add(name)
                    }

                    fuelEmployeeSpinner.adapter = ArrayAdapter(
                        this@FuelSettingsActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        names
                    )

                    // Default to whichever employee is currently selected in
                    // Movement Tracking (shared across both screens), instead
                    // of always defaulting to the first employee in the list —
                    // so admin doesn't have to re-select the same person again.
                    val sharedEmployeeId = getSharedPreferences("MovementTrackingPrefs", MODE_PRIVATE)
                        .getString("selectedEmployeeId", null)
                    val sharedIndex = employeeIds.indexOf(sharedEmployeeId)
                    if (sharedIndex >= 0) {
                        fuelEmployeeSpinner.setSelection(sharedIndex, false)
                    }

                    if (names.isNotEmpty()) {
                        loadBikeAverageForSelectedEmployee()
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun loadBikeAverageForSelectedEmployee() {
        val index = fuelEmployeeSpinner.selectedItemPosition
        if (index !in employeeIds.indices) return
        val employeeId = employeeIds[index]

        FirebaseDatabase.getInstance()
            .getReference("employees")
            .child(employeeId)
            .child("bikeAverage")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val average = snapshot.getValue(Double::class.java)
                    bikeAverageEditText.setText(if (average != null) average.toString() else "")
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun saveBikeAverage() {
        val index = fuelEmployeeSpinner.selectedItemPosition
        if (index !in employeeIds.indices) {
            Toast.makeText(this, "Employee select karein", Toast.LENGTH_SHORT).show()
            return
        }
        val employeeId = employeeIds[index]

        val averageText = bikeAverageEditText.text.toString().trim()
        val average = averageText.toDoubleOrNull()

        if (average == null || average <= 0) {
            Toast.makeText(this, "Sahi average likhein (jaise 42)", Toast.LENGTH_SHORT).show()
            return
        }

        val averageRef = FirebaseDatabase.getInstance()
            .getReference("employees")
            .child(employeeId)
            .child("bikeAverage")

        averageRef
            .setValue(average)
            .addOnSuccessListener {
                // Read back immediately to confirm it actually landed on the
                // server (catches silent rejections from Security Rules,
                // which addOnSuccessListener alone won't always reveal)
                averageRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val confirmed = snapshot.getValue(Double::class.java)
                        if (confirmed != null && confirmed == average) {
                            Toast.makeText(
                                this@FuelSettingsActivity,
                                "✅ Bike average save ho gaya: $average km/L",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@FuelSettingsActivity,
                                "⚠️ Save confirm nahi hua (Firebase Rules check karein) — dobara koshish karein",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Toast.makeText(
                            this@FuelSettingsActivity,
                            "⚠️ Confirm nahi ho saka: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }
            .addOnFailureListener {
                Toast.makeText(this, "Save nahi hua, dobara koshish karein", Toast.LENGTH_SHORT).show()
            }
    }
}