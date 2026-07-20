package com.example.eboneadminpanel

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MovementTrackingActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var employeeSpinner: Spinner
    private lateinit var timeRangeSpinner: Spinner
    private lateinit var showMovementButton: TextView
    private lateinit var playMovementButton: TextView
    private lateinit var distanceStatText: TextView
    private lateinit var fuelStatText: TextView
    private lateinit var actualFuelCompareText: TextView
    private lateinit var hoursStatText: TextView
    private lateinit var pointsRecyclerView: RecyclerView
    private lateinit var noMovementDataText: TextView
    private lateinit var movementDetailsContainer: android.view.View
    private lateinit var movementPanelChevron: TextView

    private lateinit var mMap: GoogleMap
    private var mapReady = false
    private var pendingDefaultLatLng: LatLng? = null
    private var customFromMillis: Long = 0L
    private var currentEmployeeId: String? = null
    private var lastCalculatedFuelCost: Double = 0.0
    private var customToMillis: Long = System.currentTimeMillis()

    // employeeId -> Pair(employeeName, bikeAverageKmPerLitre)
    private val employeeMap = LinkedHashMap<String, Pair<String, Double>>()
    private var fuelPricePerLiter: Double = 0.0

    private val pointAdapter = MovementPointAdapter(mutableListOf()) { position ->
        onListItemTapped(position)
    }
    private val waypointMarkers = mutableListOf<Marker>()
    private var filteredPoints: List<MovementPoint> = emptyList() // full set -> polyline + stats
    private var waypoints: List<MovementPoint> = emptyList()      // downsampled -> numbered markers/list

    private var playMarker: Marker? = null
    private var isPlaying = false
    private var currentPlayIndex = 0
    private val playHandler = Handler(Looper.getMainLooper())
    private var isMovementPanelExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movement_tracking)

        // Restore the last-selected employee from PERSISTENT storage — this
        // survives even a full app close (not just backgrounding), so
        // whichever employee was selected stays selected next time too.
        val prefs = getSharedPreferences("MovementTrackingPrefs", MODE_PRIVATE)
        currentEmployeeId = prefs.getString("selectedEmployeeId", null)

        employeeSpinner = findViewById(R.id.employeeSpinner)
        timeRangeSpinner = findViewById(R.id.timeRangeSpinner)
        showMovementButton = findViewById(R.id.showMovementButton)
        playMovementButton = findViewById(R.id.playMovementButton)
        distanceStatText = findViewById(R.id.distanceStatText)
        fuelStatText = findViewById(R.id.fuelStatText)
        actualFuelCompareText = findViewById(R.id.actualFuelCompareText)
        hoursStatText = findViewById(R.id.hoursStatText)
        pointsRecyclerView = findViewById(R.id.movementPointsRecyclerView)
        noMovementDataText = findViewById(R.id.noMovementDataText)
        movementDetailsContainer = findViewById(R.id.movementDetailsContainer)
        movementPanelChevron = findViewById(R.id.movementPanelChevron)

        findViewById<android.view.View>(R.id.movementPointsHeader).setOnClickListener {
            toggleMovementPanel()
        }

        // CHANGED: hamburger now opens a small popup menu containing ONLY
        // "Fuel Settings" (per request), instead of just finish()-ing back.
        // Back navigation still works normally via the system back button.
        findViewById<TextView>(R.id.movementBackButton).setOnClickListener { anchor ->
            showHamburgerMenu(anchor)
        }

        findViewById<android.view.View>(R.id.recenterButton).setOnClickListener {
            recenterMap()
        }

        findViewById<android.view.View>(R.id.toggleMapTypeButton).setOnClickListener {
            toggleMapType()
        }

        // Tapping the Fuel amount itself (top bar) now opens the Add/Revert
        // dialog, instead of a separate floating 💵 button
        findViewById<android.view.View>(R.id.fuelTapArea).setOnClickListener {
            showActualFuelDialog()
        }

        pointsRecyclerView.layoutManager = LinearLayoutManager(this)
        pointsRecyclerView.adapter = pointAdapter

        val timeRanges = arrayOf("Last 2 Hours", "Today", "Yesterday", "Last 7 Days", "Custom Date")
        val timeRangeAdapter = ArrayAdapter(this, R.layout.spinner_item_white, timeRanges)
        timeRangeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        timeRangeSpinner.adapter = timeRangeAdapter

        // Restore last-selected time range from persistent storage (same
        // mechanism as the employee selection) — survives full app close
        val savedRangePosition = getSharedPreferences("MovementTrackingPrefs", MODE_PRIVATE)
            .getInt("selectedTimeRangePosition", -1)
        if (savedRangePosition in timeRanges.indices) {
            timeRangeSpinner.setSelection(savedRangePosition, false)
        }

        timeRangeSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    getSharedPreferences("MovementTrackingPrefs", MODE_PRIVATE)
                        .edit()
                        .putInt("selectedTimeRangePosition", position)
                        .apply()

                    if (position == 4) { // "Custom Date"
                        showCustomDatePicker()
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.movementMap) as SupportMapFragment
        mapFragment.getMapAsync(this)

        loadEmployeeList()
        loadFuelPrice()

        showMovementButton.setOnClickListener {
            onShowMovementClicked()
        }

        playMovementButton.setOnClickListener {
            playMovementAnimation()
        }
    }

    // NEW: hamburger popup — "Fuel Settings" + "Fuel Log" + "Fuel Test Calculator"
    private fun showHamburgerMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Fuel Settings")
        popup.menu.add(0, 2, 1, "Fuel Log")
        popup.menu.add(0, 3, 2, "Fuel Test Calculator")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    startActivity(
                        android.content.Intent(this, FuelSettingsActivity::class.java)
                    )
                    true
                }
                2 -> {
                    startActivity(
                        android.content.Intent(this, FuelLogActivity::class.java)
                    )
                    true
                }
                3 -> {
                    showFuelTestCalculatorDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ---------- Fuel Test Calculator (standalone, NOT saved to Firebase) ----------
    // Lets admin plug in any amount / price / average — for any vehicle type
    // (car, bike, truck, jeep) — just to quickly check "what mileage would
    // this give?". Pure local math, one-time, never touches the real Fuel
    // Wallet, deductedKm tracking, or Firebase at all. The real auto-deduction
    // logic above (onShowMovementClicked / processAutoDeductionForSingleDay)
    // is completely untouched by this.
    private fun showFuelTestCalculatorDialog() {
        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad, pad, 0)

        val amountInput = android.widget.EditText(this)
        amountInput.hint = "Fuel amount (Rs) — e.g. 4000"
        amountInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

        val priceInput = android.widget.EditText(this)
        priceInput.hint = "Price per litre (Rs) — e.g. 318"
        priceInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

        val averageInput = android.widget.EditText(this)
        averageInput.hint = "Average (km per litre) — e.g. 15"
        averageInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

        val distanceInput = android.widget.EditText(this)
        distanceInput.hint = "Distance to travel (km) — e.g. 37"
        distanceInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

        val resultText = TextView(this)
        resultText.setPadding(0, pad, 0, 0)
        resultText.textSize = 15f

        val distanceResultText = TextView(this)
        distanceResultText.setPadding(0, pad / 2, 0, 0)
        distanceResultText.textSize = 15f

        container.addView(amountInput)
        container.addView(priceInput)
        container.addView(averageInput)
        container.addView(resultText)
        container.addView(distanceInput)
        container.addView(distanceResultText)

        fun recalculate() {
            val amount = amountInput.text.toString().trim().toDoubleOrNull()
            val price = priceInput.text.toString().trim().toDoubleOrNull()
            val average = averageInput.text.toString().trim().toDoubleOrNull()

            if (amount == null || price == null || average == null ||
                amount <= 0 || price <= 0 || average <= 0
            ) {
                resultText.text = "Enter all 3 values to see the result"
            } else {
                // Same formula as FuelCalculator, used purely for this local
                // test — nothing here is written back to Firebase.
                val litres = amount / price
                val estimatedKm = litres * average
                resultText.text = "≈ %.2f litres\n≈ %.0f km on Rs %.0f".format(litres, estimatedKm, amount)
            }

            val distance = distanceInput.text.toString().trim().toDoubleOrNull()
            if (price == null || average == null || price <= 0 || average <= 0 ||
                distance == null || distance <= 0
            ) {
                distanceResultText.text = ""
            } else {
                // Reverse direction: how much fuel (Rs) is needed to cover
                // a given distance, at this price and average
                val litresNeeded = distance / average
                val costNeeded = litresNeeded * price
                distanceResultText.text = "For %.0f km: ≈ %.2f litres ≈ Rs %.0f needed".format(distance, litresNeeded, costNeeded)
            }
        }

        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = recalculate()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        amountInput.addTextChangedListener(watcher)
        priceInput.addTextChangedListener(watcher)
        averageInput.addTextChangedListener(watcher)
        distanceInput.addTextChangedListener(watcher)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Fuel Test Calculator")
            .setView(container)
            .setNegativeButton("Reset", null)
            .setPositiveButton("Close", null)
            .create()

        // Override the Reset button's click so it clears the fields
        // WITHOUT dismissing the dialog (default AlertDialog behavior
        // dismisses on any button tap) — lets the admin run another
        // test right away. Using NEGATIVE (not NEUTRAL) so it sits right
        // next to Close instead of in the opposite corner.
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                amountInput.setText("")
                priceInput.setText("")
                averageInput.setText("")
                distanceInput.setText("")
                resultText.text = ""
                distanceResultText.text = ""
                amountInput.requestFocus()
            }
        }

        dialog.show()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.mapType = GoogleMap.MAP_TYPE_SATELLITE
        mapReady = true
        applyPendingDefaultCameraIfReady()

        // Tapping a marker on the map -> scroll + highlight the matching
        // row in the Movement Points list below, so its time is visible
        mMap.setOnMarkerClickListener { marker ->
            val index = waypointMarkers.indexOf(marker)
            if (index >= 0) {
                pointsRecyclerView.smoothScrollToPosition(index)
                pointAdapter.setHighlighted(index)
            }
            false // still show the marker's default info window / re-center
        }
    }

    private fun applyPendingDefaultCameraIfReady() {
        if (mapReady && pendingDefaultLatLng != null) {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingDefaultLatLng!!, 14f))
            pendingDefaultLatLng = null // only need to do this once
        }
    }

    private fun recenterMap() {
        if (!mapReady) return
        if (filteredPoints.isNotEmpty()) {
            val bounds = LatLngBounds.Builder()
            for (point in filteredPoints) {
                bounds.include(LatLng(point.lat, point.lng))
            }
            try {
                mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 80))
            } catch (e: Exception) {
                // ignore
            }
        } else {
            val last = filteredPoints.lastOrNull()
            if (last != null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.lat, last.lng), 14f))
            }
        }
    }

    private fun toggleMapType() {
        if (!mapReady) return
        mMap.mapType = if (mMap.mapType == GoogleMap.MAP_TYPE_SATELLITE) {
            GoogleMap.MAP_TYPE_NORMAL
        } else {
            GoogleMap.MAP_TYPE_SATELLITE
        }
    }

    // Movement Points panel starts collapsed (just the thin header row,
    // sitting right above Show Movement / Play Movement). Tapping the
    // header expands it; it auto-collapses again after a few seconds if
    // left untouched, so it doesn't permanently take up screen space.
    private fun toggleMovementPanel() {
        isMovementPanelExpanded = !isMovementPanelExpanded
        movementDetailsContainer.visibility =
            if (isMovementPanelExpanded) android.view.View.VISIBLE else android.view.View.GONE
        movementPanelChevron.text = if (isMovementPanelExpanded) "▲" else "▼"
    }

    // ---------- Data loading (employees + fuel price) ----------

    private fun loadEmployeeList() {
        FirebaseDatabase.getInstance().getReference("employees")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    employeeMap.clear()
                    val names = mutableListOf<String>()
                    for (emp in snapshot.children) {
                        val id = emp.key ?: continue
                        val name = emp.child("employeeName").getValue(String::class.java) ?: "Employee"
                        val average = emp.child("bikeAverage").getValue(Double::class.java) ?: 0.0
                        employeeMap[id] = Pair(name, average)
                        names.add(name)

                        // Use the first employee with a known live location to
                        // center the map immediately (instead of Google's
                        // default zoomed-out world view)
                        if (pendingDefaultLatLng == null) {
                            val lat = emp.child("latitude").value?.toString()?.toDoubleOrNull()
                            val lng = emp.child("longitude").value?.toString()?.toDoubleOrNull()
                            if (lat != null && lng != null) {
                                pendingDefaultLatLng = LatLng(lat, lng)
                            }
                        }
                    }

                    if (names.isEmpty()) {
                        Toast.makeText(
                            this@MovementTrackingActivity,
                            "Firebase 'employees' node mein koi data nahi mila",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    val empAdapter = ArrayAdapter(
                        this@MovementTrackingActivity,
                        R.layout.spinner_item_white,
                        names
                    )
                    empAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    employeeSpinner.adapter = empAdapter

                    // Restore whichever employee was already selected (e.g.
                    // Kashif) instead of always resetting to position 0 —
                    // this matters because onResume() reloads this list
                    // every time the screen becomes visible again.
                    val idsList = employeeMap.keys.toList()
                    val previousIndex = idsList.indexOf(currentEmployeeId)
                    if (previousIndex >= 0) {
                        employeeSpinner.setSelection(previousIndex, false)
                    }

                    employeeSpinner.onItemSelectedListener =
                        object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(
                                parent: android.widget.AdapterView<*>?,
                                view: android.view.View?,
                                position: Int,
                                id: Long
                            ) {
                                val ids = employeeMap.keys.toList()
                                if (position in ids.indices) {
                                    val empId = ids[position]
                                    currentEmployeeId = empId

                                    // Save persistently so this selection survives
                                    // even a full app close, not just backgrounding
                                    getSharedPreferences("MovementTrackingPrefs", MODE_PRIVATE)
                                        .edit()
                                        .putString("selectedEmployeeId", empId)
                                        .apply()

                                    val avg = employeeMap[empId]?.second ?: 0.0
                                    loadWalletBalance(empId) { balance ->
                                        updateFuelBarDisplay(balance, avg)
                                    }
                                }
                            }

                            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                        }

                    applyPendingDefaultCameraIfReady()
                }

                override fun onCancelled(error: DatabaseError) {
                    // Was silently swallowed before — now visible so we can
                    // actually diagnose permission/connection issues
                    Toast.makeText(
                        this@MovementTrackingActivity,
                        "Employee list load nahi hui: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun loadFuelPrice() {
        FirebaseDatabase.getInstance().getReference("fuelSettings")
            .child("fuelPricePerLiter")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    fuelPricePerLiter = snapshot.getValue(Double::class.java) ?: 0.0
                    if (fuelPricePerLiter <= 0.0) {
                        Toast.makeText(
                            this@MovementTrackingActivity,
                            "⚠️ Fuel Price set nahi hai — Menu → Fuel Settings mein set karein",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        this@MovementTrackingActivity,
                        "Fuel price load nahi hui: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    // ---------- SHOW MOVEMENT ----------

    private fun onShowMovementClicked() {
        val employeeIds = employeeMap.keys.toList()
        val employeeIndex = employeeSpinner.selectedItemPosition
        if (employeeIndex !in employeeIds.indices) {
            Toast.makeText(this, "Employee select karein", Toast.LENGTH_SHORT).show()
            return
        }
        val employeeId = employeeIds[employeeIndex]
        val (employeeName, bikeAverage) = employeeMap[employeeId] ?: return
        currentEmployeeId = employeeId

        val (fromMillis, toMillis) = getTimeRangeMillis(timeRangeSpinner.selectedItemPosition)

        distanceStatText.text = "Loading..."
        fuelStatText.text = "Loading..."
        hoursStatText.text = "Loading..."
        actualFuelCompareText.visibility = android.view.View.GONE

        loadTrackingData(employeeId, fromMillis, toMillis) { rawPoints ->
            val cleaned = MovementFilter.filterValidPoints(rawPoints)
            filteredPoints = cleaned

            if (cleaned.isEmpty()) {
                Toast.makeText(
                    this,
                    "$employeeName ka is waqt mein koi movement data nahi mila",
                    Toast.LENGTH_SHORT
                ).show()
                clearMapAndStats()
                return@loadTrackingData
            }

            waypoints = selectSignificantWaypoints(cleaned)

            val stats = TrackingStatistics.computeStats(cleaned)
            val fuelUsed = FuelCalculator.fuelUsedLitres(stats.totalDistanceKm, bikeAverage)
            val fuelCost = FuelCalculator.fuelCost(fuelUsed, fuelPricePerLiter)
            lastCalculatedFuelCost = fuelCost

            distanceStatText.text = "%.1f km".format(stats.totalDistanceKm)
            hoursStatText.text = TrackingStatistics.formatDuration(stats.workingDurationMillis)

            if (bikeAverage <= 0.0 || fuelPricePerLiter <= 0.0) {
                // Missing config — show WHY instead of a silent "Rs 0"
                stopFuelBlink()
                fuelStatText.text = "⚠️ Set"
                actualFuelCompareText.visibility = android.view.View.GONE
                val reason = if (bikeAverage <= 0.0)
                    "$employeeName ka Bike Average missing hai"
                else
                    "Fuel Price missing hai"
                Toast.makeText(this, "⚠️ $reason — Fuel Settings mein set karein", Toast.LENGTH_LONG).show()
            } else {
                // Wallet balance drives the top bar — deduct fuel cost
                // DYNAMICALLY as the employee covers more distance, for
                // whichever SINGLE day is being viewed (Today, Yesterday, or
                // a single Custom Date) — not just literally "Today"
                val dateKeysForRange = getDateKeysBetween(fromMillis, toMillis)
                loadWalletBalance(employeeId) { balance ->
                    if (dateKeysForRange.size == 1) {
                        processAutoDeductionForSingleDay(
                            employeeId, dateKeysForRange[0], stats.totalDistanceKm, balance, bikeAverage
                        )
                    } else {
                        // Multi-day range (Last 7 Days, multi-day Custom) —
                        // just a historical summary view, no deduction
                        updateFuelBarDisplay(balance, bikeAverage)
                    }
                }
            }

            drawRoute(cleaned, waypoints)
            reverseGeocodeWaypoints(waypoints)
        }
    }

    // Writes one entry to the transaction history log for this employee
    private fun logFuelTransaction(employeeId: String, amount: Double, type: String, newBalance: Double) {
        val entry = hashMapOf(
            "amount" to amount,
            "type" to type,
            "newBalance" to newBalance,
            "timestamp" to System.currentTimeMillis()
        )
        FirebaseDatabase.getInstance()
            .getReference("fuelWallet")
            .child(employeeId)
            .child("transactions")
            .push()
            .setValue(entry)
    }

    // ---------- Fuel Wallet (running balance per employee) ----------

    private fun loadWalletBalance(employeeId: String, callback: (Double) -> Unit) {
        FirebaseDatabase.getInstance()
            .getReference("fuelWallet")
            .child(employeeId)
            .child("balance")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    callback(snapshot.getValue(Double::class.java) ?: 0.0)
                }

                override fun onCancelled(error: DatabaseError) {
                    callback(0.0)
                }
            })
    }

    // Deducts fuel cost DYNAMICALLY as the employee covers more distance
    // today. Instead of a one-time "already deducted today" flag, this
    // tracks the CUMULATIVE km already deducted for that SPECIFIC date —
    // each time that day is checked, only the NEW distance covered since
    // the last check gets deducted. Checking again after more movement =
    // balance drops further. Checking again with no new movement = no
    // double-charge. Works for Today, Yesterday, or a single Custom Date.
    private fun processAutoDeductionForSingleDay(
        employeeId: String,
        dateKey: String,
        totalDistanceKmForDay: Double,
        currentBalance: Double,
        bikeAverage: Double
    ) {
        val deductedKmRef = FirebaseDatabase.getInstance()
            .getReference("fuelWallet")
            .child(employeeId)
            .child("deductedKm")
            .child(dateKey)

        deductedKmRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val alreadyDeductedKm = snapshot.getValue(Double::class.java) ?: 0.0
                val incrementalKm = totalDistanceKmForDay - alreadyDeductedKm

                if (incrementalKm <= 0.01) {
                    // No meaningful new movement since last check — nothing to deduct
                    updateFuelBarDisplay(currentBalance, bikeAverage)
                    return
                }

                val incrementalFuelUsed = FuelCalculator.fuelUsedLitres(incrementalKm, bikeAverage)
                val incrementalCost = FuelCalculator.fuelCost(incrementalFuelUsed, fuelPricePerLiter)
                val newBalance = currentBalance - incrementalCost

                FirebaseDatabase.getInstance()
                    .getReference("fuelWallet")
                    .child(employeeId)
                    .child("balance")
                    .setValue(newBalance)
                deductedKmRef.setValue(totalDistanceKmForDay)
                logFuelTransaction(employeeId, -incrementalCost, "Auto-Deduction ($dateKey)", newBalance)
                updateFuelBarDisplay(newBalance, bikeAverage)
            }

            override fun onCancelled(error: DatabaseError) {
                updateFuelBarDisplay(currentBalance, bikeAverage)
            }
        })
    }

    // Updates the top-bar Rs value + the "remaining km" line under Movement Points
    // Simple continuous fade in/out blink to draw attention to low balance,
    // without changing the text color (stays white as requested)
    private fun startFuelBlink() {
        if (fuelStatText.animation != null) return // already blinking
        val anim = android.view.animation.AlphaAnimation(1.0f, 0.25f)
        anim.duration = 600
        anim.repeatMode = android.view.animation.Animation.REVERSE
        anim.repeatCount = android.view.animation.Animation.INFINITE
        fuelStatText.startAnimation(anim)
    }

    private fun stopFuelBlink() {
        fuelStatText.clearAnimation()
        fuelStatText.alpha = 1.0f
    }

    private fun updateFuelBarDisplay(balance: Double, bikeAverage: Double) {
        fuelStatText.text = "Rs %.0f".format(balance)
        fuelStatText.setTextColor(android.graphics.Color.parseColor("#FFFFFF")) // stays white always

        // Low fuel warning — keeps appearing every time the fuel display
        // refreshes (employee selected, Show Movement clicked, funds
        // added, etc.) as long as balance stays at/below the threshold,
        // so it can't be missed or forgotten. Text blinks instead of
        // changing color, per request.
        val lowBalanceThreshold = 100.0
        if (balance <= lowBalanceThreshold) {
            startFuelBlink()
            Toast.makeText(
                this,
                "⚠️ Low Fuel Balance: Rs %.0f left — please add funds".format(balance),
                Toast.LENGTH_LONG
            ).show()
        } else {
            stopFuelBlink()
        }

        if (bikeAverage > 0.0 && fuelPricePerLiter > 0.0 && balance > 0.0) {
            val remainingLitres = balance / fuelPricePerLiter
            val remainingKm = remainingLitres * bikeAverage
            actualFuelCompareText.text =
                "⛽ Remaining: ~%.0f km (Rs %.0f balance)".format(remainingKm, balance)
            actualFuelCompareText.visibility = android.view.View.VISIBLE
        } else {
            actualFuelCompareText.visibility = android.view.View.GONE
        }
    }

    private fun showActualFuelDialog() {
        val employeeId = currentEmployeeId
        if (employeeId == null) {
            Toast.makeText(this, "Select an employee first", Toast.LENGTH_SHORT).show()
            return
        }

        loadWalletBalance(employeeId) { currentBalance ->
            val input = android.widget.EditText(this)
            input.hint = "e.g. 300 to add, -300 to revert"
            input.inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED

            android.app.AlertDialog.Builder(this)
                .setTitle("Add / Revert Fuel Amount")
                .setMessage("Current balance: Rs %.0f\n\nEnter a positive number to add funds, or a negative number (e.g. -300) to revert a mistaken entry.".format(currentBalance))
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val amount = input.text.toString().trim().toDoubleOrNull()
                    if (amount == null || amount == 0.0) {
                        Toast.makeText(this, "Enter a valid non-zero amount", Toast.LENGTH_SHORT).show()
                    } else {
                        val newBalance = currentBalance + amount
                        FirebaseDatabase.getInstance()
                            .getReference("fuelWallet")
                            .child(employeeId)
                            .child("balance")
                            .setValue(newBalance)
                            .addOnSuccessListener {
                                val actionWord = if (amount > 0) "added" else "reverted"
                                val logType = if (amount > 0) "Add" else "Revert"
                                logFuelTransaction(employeeId, amount, logType, newBalance)
                                Toast.makeText(
                                    this,
                                    "Rs %.0f %s. New balance: Rs %.0f".format(kotlin.math.abs(amount), actionWord, newBalance),
                                    Toast.LENGTH_SHORT
                                ).show()
                                val bikeAverage = employeeMap[employeeId]?.second ?: 0.0
                                updateFuelBarDisplay(newBalance, bikeAverage)
                            }
                            .addOnFailureListener {
                                Toast.makeText(this, "Save failed, please try again", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun clearMapAndStats() {
        if (mapReady) mMap.clear()
        stopFuelBlink()
        distanceStatText.text = "0 km"
        fuelStatText.text = "Rs 0"
        actualFuelCompareText.visibility = android.view.View.GONE
        hoursStatText.text = "0h 0m"
        pointAdapter.replaceAll(emptyList())
        noMovementDataText.visibility = android.view.View.VISIBLE
        waypoints = emptyList()
        filteredPoints = emptyList()
    }

    private fun getTimeRangeMillis(selection: Int): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return when (selection) {
            0 -> Pair(now - 2 * 60 * 60 * 1000L, now)       // Last 2 Hours
            1 -> Pair(getStartOfDayMillis(0), now)            // Today
            2 -> {
                val yesterdayStart = getStartOfDayMillis(-1)
                val yesterdayEnd = getStartOfDayMillis(0) - 1
                Pair(yesterdayStart, yesterdayEnd)
            }
            3 -> Pair(now - 7 * 24 * 60 * 60 * 1000L, now)     // Last 7 Days
            4 -> Pair(customFromMillis, customToMillis)        // Custom Date
            else -> Pair(now - 2 * 60 * 60 * 1000L, now)
        }
    }

    private fun showCustomDatePicker() {
        val cal = Calendar.getInstance()
        android.app.DatePickerDialog(
            this,
            { _, fromYear, fromMonth, fromDay ->
                val fromCal = Calendar.getInstance()
                fromCal.set(fromYear, fromMonth, fromDay, 0, 0, 0)
                fromCal.set(Calendar.MILLISECOND, 0)

                android.app.DatePickerDialog(
                    this,
                    { _, toYear, toMonth, toDay ->
                        val toCal = Calendar.getInstance()
                        toCal.set(toYear, toMonth, toDay, 23, 59, 59)
                        toCal.set(Calendar.MILLISECOND, 999)

                        if (fromCal.timeInMillis > toCal.timeInMillis) {
                            Toast.makeText(
                                this,
                                "From date pehle honi chahiye",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            customFromMillis = fromCal.timeInMillis
                            customToMillis = toCal.timeInMillis
                        }
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun getStartOfDayMillis(dayOffset: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ---------- Firebase tracking/ node reading (handles multi-day ranges) ----------

    private fun loadTrackingData(
        employeeId: String,
        fromMillis: Long,
        toMillis: Long,
        callback: (List<MovementPoint>) -> Unit
    ) {
        val dateKeys = getDateKeysBetween(fromMillis, toMillis)
        val allPoints = mutableListOf<MovementPoint>()
        var pending = dateKeys.size
        if (pending == 0) {
            callback(emptyList())
            return
        }

        for (dateKey in dateKeys) {
            FirebaseDatabase.getInstance()
                .getReference("tracking")
                .child(employeeId)
                .child(dateKey)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        for (child in snapshot.children) {
                            val lat = child.child("lat").getValue(Double::class.java) ?: continue
                            val lng = child.child("lng").getValue(Double::class.java) ?: continue
                            val timestamp = child.child("timestamp").getValue(Long::class.java)
                                ?: (child.key?.toLongOrNull() ?: 0L)
                            if (timestamp < fromMillis || timestamp > toMillis) continue

                            val accuracy =
                                (child.child("accuracy").getValue(Double::class.java) ?: 0.0).toFloat()
                            val speed =
                                (child.child("speed").getValue(Double::class.java) ?: 0.0).toFloat()
                            val bearing =
                                (child.child("bearing").getValue(Double::class.java) ?: 0.0).toFloat()

                            allPoints.add(MovementPoint(lat, lng, accuracy, speed, bearing, timestamp))
                        }
                        pending--
                        if (pending == 0) callback(allPoints.sortedBy { it.timestamp })
                    }

                    override fun onCancelled(error: DatabaseError) {
                        pending--
                        if (pending == 0) callback(allPoints.sortedBy { it.timestamp })
                    }
                })
        }
    }

    private fun getDateKeysBetween(fromMillis: Long, toMillis: Long): List<String> {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val keys = mutableListOf<String>()
        val cal = Calendar.getInstance()
        cal.timeInMillis = fromMillis
        while (cal.timeInMillis <= toMillis) {
            keys.add(sdf.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return keys
    }

    // ---------- Downsampling (full route line stays accurate, but only
    // ~10 numbered waypoints are shown/reverse-geocoded, matching the
    // approved mockup style) ----------

    // Tapping a row in the Movement Points list -> jump the map camera to
    // that point's location and show its marker (reverse of marker-tap sync)
    private fun onListItemTapped(position: Int) {
        if (position !in waypoints.indices || !mapReady) return
        val point = waypoints[position]
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lng), 17f))
        pointAdapter.setHighlighted(position)
        waypointMarkers.getOrNull(position)?.showInfoWindow()
    }

    // ---------- Smart waypoint selection ----------
    // Instead of naively picking 10 evenly-spaced points, this walks through
    // the FULL point list and marks only genuinely meaningful moments:
    //   • Stop  (5+ min stationary)  -> arrival marker + departure marker
    //   • U-turn (sharp bearing change) -> one marker
    //   • Long continuous travel     -> one marker every ~5km
    // Then de-duplicates anything too close together (avoids clusters of
    // near-identical markers piling up at the same spot).

    private fun selectSignificantWaypoints(points: List<MovementPoint>): List<MovementPoint> {
        if (points.size <= 2) return points

        val stationaryRadiusMeters = 30.0

        // ---- PASS 1: figure out vehicle type from MOVING-only average speed
        // (stationary periods excluded, so long stops don't skew this) ----
        var movingDistanceMeters = 0.0
        var movingTimeMs = 0L
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val distance = DistanceCalculator.distanceBetween(prev.lat, prev.lng, curr.lat, curr.lng)
            if (distance >= stationaryRadiusMeters) {
                movingDistanceMeters += distance
                movingTimeMs += (curr.timestamp - prev.timestamp)
            }
        }
        val movingHours = movingTimeMs / 3_600_000.0
        val avgMovingSpeedKmh = if (movingHours > 0) (movingDistanceMeters / 1000.0) / movingHours else 0.0

        // Walking ≤8 km/h, Motorbike 8–80 km/h, Car >80 km/h — spacing scales accordingly
        var sequenceIntervalMeters = when {
            avgMovingSpeedKmh <= 8.0 -> 1000.0   // ~every 1km on foot
            avgMovingSpeedKmh > 80.0 -> 9000.0   // ~every 9km at car/highway speed
            else -> 5000.0                        // ~every 5km at motorbike speed
        }

        // For SHORT trips, the vehicle-based interval above would never
        // trigger even once (e.g. a 0.3km trip vs a 1km walking interval),
        // leaving no in-between markers at all. Scale it down so short
        // trips still get a few intermediate markers along the route.
        if (movingDistanceMeters > 0 && movingDistanceMeters < sequenceIntervalMeters * 2) {
            sequenceIntervalMeters = (movingDistanceMeters / 3.0).coerceAtLeast(100.0)
        }

        // ---- PASS 2: stops (tiered by duration), U-turns, sequence markers ----
        val briefPauseMinMs = 10_000L        // below this = GPS noise, ignore
        val briefPauseMaxMs = 90_000L        // 10–90 sec = traffic-light-like pause
        val shortStopMaxMs = 5 * 60 * 1000L  // 90sec–5min = toll plaza / quick pickup (1 marker)
        // 5min+ = real stop like McDonald's/mosque/mall (arrival + departure markers)
        val moderateTurnThresholdDegrees = 55.0  // visible bend/turn (e.g. road curve) — also catches sharp U-turns
        val minMarkerSpacingMeters = 80.0
        val minMarkerSpacingMs = 60_000L
        val hardCap = 40

        data class BriefPause(val point: MovementPoint)

        val significant = mutableListOf<MovementPoint>()
        val briefPauses = mutableListOf<BriefPause>()
        significant.add(points.first()) // trip start

        var stopStartIndex: Int? = null
        var lastBearing: Double? = null
        var distanceSinceLastSeqMarker = 0.0

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val distance = DistanceCalculator.distanceBetween(prev.lat, prev.lng, curr.lat, curr.lng)
            val isStationary = distance < stationaryRadiusMeters

            if (isStationary) {
                if (stopStartIndex == null) stopStartIndex = i - 1
            } else {
                if (stopStartIndex != null) {
                    val stopStart = points[stopStartIndex!!]
                    val stopDurationMs = prev.timestamp - stopStart.timestamp
                    when {
                        stopDurationMs >= shortStopMaxMs -> {
                            // Real stop (5+ min) — McDonald's, mosque, mall
                            significant.add(stopStart)  // arrival
                            significant.add(prev)        // departure
                        }
                        stopDurationMs >= briefPauseMaxMs -> {
                            // Toll plaza / quick pickup (90sec–5min) — just one marker
                            significant.add(stopStart)
                        }
                        stopDurationMs >= briefPauseMinMs -> {
                            // Traffic-light-like brief pause — hold for cluster check below
                            briefPauses.add(BriefPause(stopStart))
                        }
                        // else: too brief, likely GPS noise — ignore entirely
                    }
                    stopStartIndex = null
                }

                // Turn detection via bearing (direction) change — catches
                // both moderate bends (road curves) and sharp U-turns,
                // since the lower threshold naturally covers both cases
                val bearing = bearingBetween(prev.lat, prev.lng, curr.lat, curr.lng)
                if (lastBearing != null) {
                    var delta = kotlin.math.abs(bearing - lastBearing!!)
                    if (delta > 180) delta = 360 - delta
                    if (delta > moderateTurnThresholdDegrees) {
                        significant.add(prev)
                    }
                }
                lastBearing = bearing

                // Vehicle-aware sequence marker during continuous travel
                distanceSinceLastSeqMarker += distance
                if (distanceSinceLastSeqMarker >= sequenceIntervalMeters) {
                    significant.add(curr)
                    distanceSinceLastSeqMarker = 0.0
                }
            }
        }

        significant.add(points.last()) // trip end

        // ---- Traffic/Rush clustering — 3+ brief pauses close together in
        // time AND space become ONE "Traffic/Rush" marker instead of many ----
        val trafficClusterWindowMs = 15 * 60 * 1000L
        val trafficClusterMinCount = 3
        val trafficClusterMaxRadiusMeters = 1500.0

        var idx = 0
        while (idx < briefPauses.size) {
            val clusterStart = briefPauses[idx]
            val cluster = mutableListOf(clusterStart)
            var j = idx + 1
            while (j < briefPauses.size) {
                val candidate = briefPauses[j]
                val timeSinceStart = candidate.point.timestamp - clusterStart.point.timestamp
                val distFromStart = DistanceCalculator.distanceBetween(
                    clusterStart.point.lat, clusterStart.point.lng,
                    candidate.point.lat, candidate.point.lng
                )
                if (timeSinceStart <= trafficClusterWindowMs && distFromStart <= trafficClusterMaxRadiusMeters) {
                    cluster.add(candidate)
                    j++
                } else {
                    break
                }
            }
            if (cluster.size >= trafficClusterMinCount) {
                // One consolidated "Traffic/Rush" marker for the whole cluster
                significant.add(cluster[cluster.size / 2].point)
            }
            // Isolated/small groups of brief pauses are just noise — skip
            idx = j
        }

        // ---- De-duplicate — sort by time, keep only meaningfully spaced points ----
        val sorted = significant.sortedBy { it.timestamp }
        val deduped = mutableListOf<MovementPoint>()
        for (p in sorted) {
            val last = deduped.lastOrNull()
            if (last == null) {
                deduped.add(p)
            } else {
                val gapMeters = DistanceCalculator.distanceBetween(last.lat, last.lng, p.lat, p.lng)
                val gapMs = p.timestamp - last.timestamp
                if (gapMeters >= minMarkerSpacingMeters || gapMs >= minMarkerSpacingMs) {
                    deduped.add(p)
                }
            }
        }

        // Safety net for unusually eventful trips
        return if (deduped.size > hardCap) downsamplePoints(deduped, hardCap) else deduped
    }

    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val start = android.location.Location("start").apply {
            latitude = lat1
            longitude = lng1
        }
        val end = android.location.Location("end").apply {
            latitude = lat2
            longitude = lng2
        }
        val bearing = start.bearingTo(end).toDouble()
        return if (bearing < 0) bearing + 360 else bearing
    }

    private fun downsamplePoints(points: List<MovementPoint>, maxCount: Int): List<MovementPoint> {
        if (points.size <= maxCount) return points
        val step = (points.size - 1).toDouble() / (maxCount - 1)
        val result = mutableListOf<MovementPoint>()
        for (i in 0 until maxCount) {
            val index = (i * step).toInt().coerceIn(0, points.size - 1)
            result.add(points[index])
        }
        return result.distinct()
    }

    // ---------- Map drawing ----------

    private fun drawRoute(allPoints: List<MovementPoint>, waypoints: List<MovementPoint>) {
        if (!mapReady) return
        mMap.clear()
        waypointMarkers.clear()

        val polylineOptions = PolylineOptions()
            .color(0xFF0D47A1.toInt()) // app's branding blue
            .width(8f)

        val bounds = LatLngBounds.Builder()

        for (point in allPoints) {
            val latLng = LatLng(point.lat, point.lng)
            polylineOptions.add(latLng)
            bounds.include(latLng)
        }
        mMap.addPolyline(polylineOptions)

        waypoints.forEachIndexed { index, point ->
            val icon = when (index) {
                0 -> com.google.android.gms.maps.model.BitmapDescriptorFactory
                    .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_GREEN)
                waypoints.size - 1 -> com.google.android.gms.maps.model.BitmapDescriptorFactory
                    .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_RED)
                else -> com.google.android.gms.maps.model.BitmapDescriptorFactory
                    .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_ORANGE)
            }
            val marker = mMap.addMarker(
                MarkerOptions()
                    .position(LatLng(point.lat, point.lng))
                    .title("${index + 1}")
                    .icon(icon)
            )
            // Keep this list in the SAME order/index as waypoints, so tapping
            // a marker can be matched back to its list row (and vice versa)
            if (marker != null) waypointMarkers.add(marker)
        }

        try {
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 80))
        } catch (e: Exception) {
            // Single point or empty bounds — safe to ignore
        }
    }

    // ---------- Reverse geocoding (background thread, never main thread) ----------

    private fun reverseGeocodeWaypoints(waypoints: List<MovementPoint>) {
        noMovementDataText.visibility = android.view.View.GONE
        pointAdapter.replaceAll(waypoints)

        // Fresh data loaded — reset any in-progress play/pause state
        isPlaying = false
        currentPlayIndex = 0
        playMovementButton.text = "▶  PLAY MOVEMENT"
        playHandler.removeCallbacksAndMessages(null)
        playMarker?.remove()
        playMarker = null

        Thread {
            for (i in waypoints.indices) {
                val point = waypoints[i]
                val address = ReverseGeocoder.getAddress(this, point.lat, point.lng)
                runOnUiThread {
                    pointAdapter.updateAddress(i, address)
                }
            }
        }.start()
    }

    // ---------- Play Movement (Play/Pause toggle, synced with list) ----------

    private fun playMovementAnimation() {
        if (!mapReady || waypoints.isEmpty()) {
            Toast.makeText(this, "Pehle Show Movement dabayein", Toast.LENGTH_SHORT).show()
            return
        }

        if (isPlaying) {
            // Currently playing -> PAUSE (keep position, stop scheduling further steps)
            isPlaying = false
            playHandler.removeCallbacksAndMessages(null)
            playMovementButton.text = "▶  PLAY MOVEMENT"
            return
        }

        // Not playing -> START (or RESUME from currentPlayIndex)
        isPlaying = true
        playMovementButton.text = "⏸  PAUSE"

        if (playMarker == null || currentPlayIndex == 0) {
            playMarker?.remove()
            playMarker = mMap.addMarker(
                MarkerOptions()
                    .position(LatLng(waypoints[0].lat, waypoints[0].lng))
                    .title("🏍 Moving")
            )
            currentPlayIndex = 0
        }

        animateStep(currentPlayIndex)
    }

    private fun animateStep(index: Int) {
        if (!isPlaying) return // paused mid-way — stop scheduling further steps

        if (index >= waypoints.size - 1) {
            // Reached the end — reset to allow replay from the start next time
            isPlaying = false
            currentPlayIndex = 0
            playMovementButton.text = "▶  PLAY MOVEMENT"
            pointAdapter.clearHighlight()
            return
        }

        currentPlayIndex = index

        // Keep the Movement Points list in sync — scroll to and highlight
        // whichever point the marker is currently animating towards, so
        // admin can see the corresponding time as it plays.
        pointsRecyclerView.smoothScrollToPosition(index)
        pointAdapter.setHighlighted(index)

        val start = LatLng(waypoints[index].lat, waypoints[index].lng)
        val end = LatLng(waypoints[index + 1].lat, waypoints[index + 1].lng)

        val steps = 20
        val stepDurationMs = 40L
        var currentStep = 0

        val runnable = object : Runnable {
            override fun run() {
                if (!isPlaying) return // paused mid-step

                if (currentStep > steps) {
                    animateStep(index + 1)
                    return
                }
                val fraction = currentStep.toDouble() / steps
                val lat = start.latitude + (end.latitude - start.latitude) * fraction
                val lng = start.longitude + (end.longitude - start.longitude) * fraction
                playMarker?.position = LatLng(lat, lng)
                mMap.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lng)))
                currentStep++
                playHandler.postDelayed(this, stepDurationMs)
            }
        }
        playHandler.post(runnable)
    }

    // Refreshes employee list (with latest bikeAverage) + fuel price every
    // time this screen becomes visible again — fixes the "stale cache" bug
    // where going to Fuel Settings and coming back showed OLD values,
    // even though Firebase itself had the correct new ones.
    override fun onResume() {
        super.onResume()
        loadEmployeeList()
        loadFuelPrice()
    }

    override fun onDestroy() {
        super.onDestroy()
        playHandler.removeCallbacksAndMessages(null)
        stopFuelBlink()
    }
}