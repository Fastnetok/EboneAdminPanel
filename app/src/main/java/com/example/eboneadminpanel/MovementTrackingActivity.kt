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

    private val pointAdapter = MovementPointAdapter(mutableListOf())
    private var filteredPoints: List<MovementPoint> = emptyList() // full set -> polyline + stats
    private var waypoints: List<MovementPoint> = emptyList()      // downsampled -> numbered markers/list

    private var playMarker: Marker? = null
    private val playHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movement_tracking)

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

        // CHANGED: hamburger now opens a small popup menu containing ONLY
        // "Fuel Settings" (per request), instead of just finish()-ing back.
        // Back navigation still works normally via the system back button.
        findViewById<TextView>(R.id.movementBackButton).setOnClickListener { anchor ->
            showHamburgerMenu(anchor)
        }

        findViewById<TextView>(R.id.recenterButton).setOnClickListener {
            recenterMap()
        }

        findViewById<TextView>(R.id.toggleMapTypeButton).setOnClickListener {
            toggleMapType()
        }

        findViewById<TextView>(R.id.actualFuelButton).setOnClickListener {
            showActualFuelDialog()
        }

        pointsRecyclerView.layoutManager = LinearLayoutManager(this)
        pointsRecyclerView.adapter = pointAdapter

        val timeRanges = arrayOf("Last 2 Hours", "Today", "Yesterday", "Last 7 Days", "Custom Date")
        val timeRangeAdapter = ArrayAdapter(this, R.layout.spinner_item_white, timeRanges)
        timeRangeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        timeRangeSpinner.adapter = timeRangeAdapter

        timeRangeSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
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

    // NEW: hamburger popup with a single item — "Fuel Settings"
    private fun showHamburgerMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Fuel Settings")
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
                startActivity(
                    android.content.Intent(this, FuelSettingsActivity::class.java)
                )
                true
            } else {
                false
            }
        }
        popup.show()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.mapType = GoogleMap.MAP_TYPE_SATELLITE
        mapReady = true
        applyPendingDefaultCameraIfReady()
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

            waypoints = downsamplePoints(cleaned, 10)

            val stats = TrackingStatistics.computeStats(cleaned)
            val fuelUsed = FuelCalculator.fuelUsedLitres(stats.totalDistanceKm, bikeAverage)
            val fuelCost = FuelCalculator.fuelCost(fuelUsed, fuelPricePerLiter)
            lastCalculatedFuelCost = fuelCost

            distanceStatText.text = "%.1f km".format(stats.totalDistanceKm)
            hoursStatText.text = TrackingStatistics.formatDuration(stats.workingDurationMillis)

            if (bikeAverage <= 0.0 || fuelPricePerLiter <= 0.0) {
                // Missing config — show WHY instead of a silent "Rs 0"
                fuelStatText.text = "⚠️ Set"
                actualFuelCompareText.visibility = android.view.View.GONE
                val reason = if (bikeAverage <= 0.0)
                    "$employeeName ka Bike Average missing hai"
                else
                    "Fuel Price missing hai"
                Toast.makeText(this, "⚠️ $reason — Fuel Settings mein set karein", Toast.LENGTH_LONG).show()
            } else {
                // Wallet balance drives the top bar — deduct today's cost
                // automatically (once only) if "Today" is the selected range
                loadWalletBalance(employeeId) { balance ->
                    if (timeRangeSpinner.selectedItemPosition == 1) { // "Today"
                        processAutoDeductionForToday(employeeId, fuelCost, balance, bikeAverage)
                    } else {
                        updateFuelBarDisplay(balance, bikeAverage)
                    }
                }
            }

            drawRoute(cleaned, waypoints)
            reverseGeocodeWaypoints(waypoints)
        }
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

    // Deducts today's calculated fuel cost from the wallet — but only ONCE
    // per day, no matter how many times "Show Movement" is clicked for
    // Today. A "deductedDates" marker prevents double-deduction.
    private fun processAutoDeductionForToday(
        employeeId: String,
        todayCost: Double,
        currentBalance: Double,
        bikeAverage: Double
    ) {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(java.util.Date())

        val deductedRef = FirebaseDatabase.getInstance()
            .getReference("fuelWallet")
            .child(employeeId)
            .child("deductedDates")
            .child(todayKey)

        deductedRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    // Already deducted today — just show current balance
                    updateFuelBarDisplay(currentBalance, bikeAverage)
                } else {
                    val newBalance = currentBalance - todayCost
                    FirebaseDatabase.getInstance()
                        .getReference("fuelWallet")
                        .child(employeeId)
                        .child("balance")
                        .setValue(newBalance)
                    deductedRef.setValue(todayCost)
                    updateFuelBarDisplay(newBalance, bikeAverage)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                updateFuelBarDisplay(currentBalance, bikeAverage)
            }
        })
    }

    // Updates the top-bar Rs value + the "remaining km" line under Movement Points
    private fun updateFuelBarDisplay(balance: Double, bikeAverage: Double) {
        fuelStatText.text = "Rs %.0f".format(balance)

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
            mMap.addMarker(
                MarkerOptions()
                    .position(LatLng(point.lat, point.lng))
                    .title("${index + 1}")
                    .icon(icon)
            )
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

    // ---------- Play Movement (simple step animation along waypoints) ----------

    private fun playMovementAnimation() {
        if (!mapReady || waypoints.isEmpty()) {
            Toast.makeText(this, "Pehle Show Movement dabayein", Toast.LENGTH_SHORT).show()
            return
        }

        playMarker?.remove()
        playMarker = mMap.addMarker(
            MarkerOptions()
                .position(LatLng(waypoints[0].lat, waypoints[0].lng))
                .title("🏍 Moving")
        )

        animateStep(0)
    }

    private fun animateStep(index: Int) {
        if (index >= waypoints.size - 1) return

        val start = LatLng(waypoints[index].lat, waypoints[index].lng)
        val end = LatLng(waypoints[index + 1].lat, waypoints[index + 1].lng)

        val steps = 20
        val stepDurationMs = 40L
        var currentStep = 0

        val runnable = object : Runnable {
            override fun run() {
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

    override fun onDestroy() {
        super.onDestroy()
        playHandler.removeCallbacksAndMessages(null)
    }
}