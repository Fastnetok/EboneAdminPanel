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

    // FIX: Guards against the automatic onItemSelected(position=0) callback
    // that Android fires the instant `spinner.adapter = ...` is set — BEFORE
    // we get a chance to restore the employee that was actually selected
    // last time. Without this flag, that spurious event overwrites
    // SharedPreferences with employee #0, so the spinner (and therefore the
    // Bike Average field) silently resets to the first employee every time
    // this screen is opened, making it look like the average was never saved.
    private var isRestoringSelection = false

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
                    // FIX: Skip this entirely while we are programmatically
                    // restoring the previously-selected employee (either the
                    // automatic position-0 event fired by setting the
                    // adapter, or our own setSelection(sharedIndex) call
                    // below). Only a REAL user tap on the spinner should
                    // update the bike average field and the shared prefs.
                    if (isRestoringSelection) return

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

                    // FIX: Setting the adapter fires an automatic
                    // onItemSelected(0) event synchronously on some Android
                    // versions. Guard it so that spurious event doesn't
                    // clobber SharedPreferences or trigger a redundant load.
                    isRestoringSelection = true

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

                    // Done restoring — real user taps on the spinner from
                    // here on will behave normally again.
                    isRestoringSelection = false

                    // FIX: Don't ask the spinner "who's selected right now?"
                    // here — right after setSelection(), the Spinner hasn't
                    // necessarily finished its layout pass yet, so
                    // selectedItemPosition can still report the OLD/stale
                    // position (usually 0). That caused the Bike Average
                    // field to always load the wrong employee's value on
                    // screen open. Instead, load using the employeeId we
                    // already know we just selected.
                    if (employeeIds.isNotEmpty()) {
                        val targetId = if (sharedIndex >= 0) employeeIds[sharedIndex] else employeeIds[0]
                        loadBikeAverageForEmployee(targetId)
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun loadBikeAverageForSelectedEmployee() {
        val index = fuelEmployeeSpinner.selectedItemPosition
        if (index !in employeeIds.indices) return
        loadBikeAverageForEmployee(employeeIds[index])
    }

    private fun loadBikeAverageForEmployee(employeeId: String) {
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