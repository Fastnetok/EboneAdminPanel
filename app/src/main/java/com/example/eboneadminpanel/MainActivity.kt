package com.example.eboneadminpanel

import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.location.Location
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.database.*

class MainActivity : AppCompatActivity(),
    OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private var hasCenteredCameraOnce = false
    private val employeeMarkers = HashMap<String, Marker>()
    private var followedEmployeeName: String? = null

    // Geofence (Single Active Circle)
    private var currentGeofenceCircle: Circle? = null
    private var previewCircle: Circle? = null
    private data class DashboardGeofence(val lat: Double, val lng: Double, val radius: Double)
    private var currentGeofence: DashboardGeofence? = null
    private var lastUsedRadius: Double = 100.0
    private val employeeInsideGeofences = HashMap<String, MutableSet<String>>()

    private lateinit var onlineEmployeesText: TextView
    private lateinit var offlineEmployeesText: TextView

    private lateinit var onlineEmployeesNames: TextView
    private lateinit var offlineEmployeesNames: TextView

    private lateinit var totalComplaintText: TextView
    private lateinit var pendingComplaintText: TextView
    private lateinit var inProgressText: TextView
    private lateinit var resolvedText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_admin_dashboard)

        VersionChecker.checkForUpdate(this)

        findViewById<TextView>(R.id.menuButton)
            .setOnClickListener {

                startActivity(
                    Intent(
                        this,
                        MenuActivity::class.java
                    )
                )
            }

        onlineEmployeesText =
            findViewById(R.id.onlineEmployeesText)

        offlineEmployeesText =
            findViewById(R.id.offlineEmployeesText)

        onlineEmployeesNames =
            findViewById(R.id.onlineEmployeesNames)

        offlineEmployeesNames =
            findViewById(R.id.offlineEmployeesNames)

        totalComplaintText =
            findViewById(R.id.totalComplaintText)

        pendingComplaintText =
            findViewById(R.id.pendingComplaintText)

        pendingComplaintText
            .setOnClickListener {

                startActivity(

                    Intent(
                        this,
                        PendingSummaryActivity::class.java
                    )

                )

            }

        inProgressText =
            findViewById(R.id.inProgressText)

        inProgressText
            .setOnClickListener {

                startActivity(

                    Intent(
                        this,
                        ProgressActivity::class.java
                    )

                )

            }

        resolvedText =
            findViewById(R.id.resolvedText)

        resolvedText.setOnClickListener {

            startActivity(

                Intent(
                    this,
                    ResolvedSummaryActivity::class.java
                )

            )

        }
        totalComplaintText.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    TotalComplaintsActivity::class.java
                )
            )

        }

        val mapFragment =
            supportFragmentManager
                .findFragmentById(
                    R.id.dashboardMap
                ) as SupportMapFragment

        mapFragment.getMapAsync(this)

        loadDashboardCounters()
    }

    override fun onMapReady(
        googleMap: GoogleMap
    ) {

        mMap = googleMap

        mMap.mapType =
            GoogleMap.MAP_TYPE_SATELLITE

        mMap.setOnMarkerClickListener { marker ->
            followedEmployeeName = marker.title
            mMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(marker.position, 18f)
            )
            marker.showInfoWindow()
            true
        }

        mMap.setOnMapLongClickListener { latLng ->
            showRadiusDialog(latLng)
        }

        loadDashboardGeofences()

        FirebaseDatabase
            .getInstance()
            .getReference("employees")
            .addValueEventListener(
                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        val onlineList =
                            mutableListOf<String>()

                        val offlineList =
                            mutableListOf<String>()

                        var firstLocation:
                                LatLng? = null

                        val seenEmployeeNames = HashSet<String>()

                        for (employee in snapshot.children) {

                            val latitude =
                                employee.child("latitude")
                                    .value?.toString()
                                    ?.toDoubleOrNull()

                            val longitude =
                                employee.child("longitude")
                                    .value?.toString()
                                    ?.toDoubleOrNull()

                            val employeeName =
                                employee.child("employeeName")
                                    .value?.toString()
                                    ?: "Employee"

                            val status =
                                employee.child("status")
                                    .value?.toString()
                                    ?: "OFFLINE"

                            if (
                                status.uppercase() ==
                                "ONLINE"
                            ) {

                                onlineList.add(
                                    employeeName
                                )

                            } else {

                                offlineList.add(
                                    employeeName
                                )
                            }

                            if (
                                latitude != null &&
                                longitude != null
                            ) {

                                val location =
                                    LatLng(
                                        latitude,
                                        longitude
                                    )

                                seenEmployeeNames.add(employeeName)

                                val existingMarker = employeeMarkers[employeeName]
                                if (existingMarker != null) {
                                    existingMarker.position = location
                                    if (employeeName == followedEmployeeName) {
                                        existingMarker.showInfoWindow()
                                    }
                                } else {
                                    val newMarker = mMap.addMarker(
                                        MarkerOptions()
                                            .position(location)
                                            .title(employeeName)
                                    )
                                    if (newMarker != null) {
                                        employeeMarkers[employeeName] = newMarker
                                    }
                                }

                                if (employeeName == followedEmployeeName) {
                                    mMap.animateCamera(
                                        CameraUpdateFactory.newLatLng(location)
                                    )
                                }

                                checkGeofenceTransitions(employeeName, latitude, longitude)

                                if (
                                    firstLocation ==
                                    null
                                ) {

                                    firstLocation =
                                        location
                                }
                            }
                        }

                        val markersToRemove = employeeMarkers.keys.filter { it !in seenEmployeeNames }
                        for (name in markersToRemove) {
                            employeeMarkers[name]?.remove()
                            employeeMarkers.remove(name)
                            if (name == followedEmployeeName) {
                                followedEmployeeName = null
                            }
                        }

                        onlineEmployeesText.text =
                            "🟢 Online Employees (${onlineList.size})"

                        onlineEmployeesNames.text =
                            onlineList.joinToString(", ")

                        offlineEmployeesText.text =
                            "🔴 Offline Employees (${offlineList.size})"

                        offlineEmployeesNames.text =
                            offlineList.joinToString(", ")

                        if (!hasCenteredCameraOnce) {
                            firstLocation?.let {
                                mMap.moveCamera(
                                    CameraUpdateFactory
                                        .newLatLngZoom(
                                            it,
                                            15f
                                        )
                                )
                                hasCenteredCameraOnce = true
                            }
                        }
                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }
                }
            )
    }

    // ===================== GEOFENCE (SIMPLE CUSTOM INPUT + CONFIRM) =====================

    private fun showRadiusDialog(latLng: LatLng): Boolean {

        previewCircle?.remove()
        previewCircle = mMap.addCircle(
            CircleOptions()
                .center(latLng)
                .radius(lastUsedRadius)
                .strokeColor(0xFF2196F3.toInt())
                .strokeWidth(4f)
                .fillColor(0x332196F3)
        )

        val radiusInput = EditText(this)
        radiusInput.hint = "Radius in meters (e.g. 150)"
        radiusInput.inputType = InputType.TYPE_CLASS_NUMBER
        radiusInput.setText(lastUsedRadius.toInt().toString())

        AlertDialog.Builder(this)
            .setTitle("Set Geofence Radius")
            .setView(radiusInput)
            .setPositiveButton("OK") { _, _ ->
                val enteredRadius = radiusInput.text.toString().toDoubleOrNull()
                if (enteredRadius != null && enteredRadius > 0) {
                    previewCircle?.radius = enteredRadius

                    AlertDialog.Builder(this)
                        .setTitle("Confirm")
                        .setMessage("Replace the previous value with the new value (${enteredRadius.toInt()}m)?")
                        .setPositiveButton("Yes") { _, _ ->
                            lastUsedRadius = enteredRadius
                            createGeofence(latLng, enteredRadius)
                            previewCircle?.remove()
                            previewCircle = null
                        }
                        .setNegativeButton("No") { _, _ ->
                            previewCircle?.remove()
                            previewCircle = null
                        }
                        .setOnCancelListener {
                            previewCircle?.remove()
                            previewCircle = null
                        }
                        .show()
                } else {
                    previewCircle?.remove()
                    previewCircle = null
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                previewCircle?.remove()
                previewCircle = null
            }
            .setOnCancelListener {
                previewCircle?.remove()
                previewCircle = null
            }
            .show()

        return true
    }

    private fun createGeofence(latLng: LatLng, radiusMeters: Double) {
        val data = hashMapOf(
            "lat" to latLng.latitude,
            "lng" to latLng.longitude,
            "radius" to radiusMeters
        )
        FirebaseDatabase.getInstance()
            .getReference("dashboardGeofences")
            .child("active")
            .setValue(data)
    }

    private fun loadDashboardGeofences() {
        FirebaseDatabase.getInstance()
            .getReference("dashboardGeofences")
            .child("active")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {

                    currentGeofenceCircle?.remove()
                    currentGeofenceCircle = null
                    currentGeofence = null

                    val lat = snapshot.child("lat").getValue(Double::class.java)
                    val lng = snapshot.child("lng").getValue(Double::class.java)
                    val radius = snapshot.child("radius").getValue(Double::class.java)

                    if (lat != null && lng != null && radius != null) {
                        lastUsedRadius = radius
                        currentGeofence = DashboardGeofence(lat, lng, radius)

                        currentGeofenceCircle = mMap.addCircle(
                            CircleOptions()
                                .center(LatLng(lat, lng))
                                .radius(radius)
                                .strokeColor(0xFFFF0000.toInt())
                                .strokeWidth(4f)
                                .fillColor(0x22FF0000)
                        )
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun checkGeofenceTransitions(employeeName: String, lat: Double, lng: Double) {
        val geofence = currentGeofence ?: return
        val distanceResult = FloatArray(1)

        Location.distanceBetween(lat, lng, geofence.lat, geofence.lng, distanceResult)
        val isInsideNow = distanceResult[0] <= geofence.radius

        val wasInsideBefore = employeeInsideGeofences[employeeName]?.contains("active") == true

        if (isInsideNow != wasInsideBefore) {
            playGeofenceAlertSound()
        }

        if (isInsideNow) {
            employeeInsideGeofences[employeeName] = mutableSetOf("active")
        } else {
            employeeInsideGeofences[employeeName] = mutableSetOf()
        }
    }

    private fun playGeofenceAlertSound() {
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mediaPlayer = MediaPlayer.create(this, notificationUri)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (_: Exception) {
        }
    }

    // ===================== END GEOFENCE =====================

    private fun loadDashboardCounters() {
        AdminNotificationListener.startListening(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
        FirebaseDatabase
            .getInstance()
            .getReference("complaints")
            .addValueEventListener(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        var pending = 0
                        var progress = 0
                        var resolved = 0

                        val progressEmployees =
                            HashSet<String>()

                        val todayStart = java.util.Calendar
                            .getInstance()
                            .apply {
                                set(
                                    java.util.Calendar.HOUR_OF_DAY,
                                    0
                                )
                                set(
                                    java.util.Calendar.MINUTE,
                                    0
                                )
                                set(
                                    java.util.Calendar.SECOND,
                                    0
                                )
                                set(
                                    java.util.Calendar.MILLISECOND,
                                    0
                                )
                            }
                            .timeInMillis

                        for (item in snapshot.children) {

                            val status =
                                item.child("status")
                                    .getValue(String::class.java)
                                    ?: "Pending"

                            if (
                                status.equals(
                                    "Resolved",
                                    true
                                )
                            ) {

                                val resolvedTime =
                                    item.child("resolvedTime")
                                        .getValue(Long::class.java)
                                        ?: 0L

                                if (
                                    resolvedTime >= todayStart
                                ) {

                                    resolved++

                                }

                            } else {

                                val assignedTo =
                                    item.child("assignedTo")
                                        .getValue(String::class.java)
                                        ?: ""

                                if (
                                    assignedTo.isNotEmpty()
                                ) {

                                    progressEmployees.add(
                                        assignedTo
                                    )

                                }

                            }

                        }

                        progress =
                            progressEmployees.size

                        pending =
                            snapshot.childrenCount.toInt() -
                                    progress -
                                    snapshot.children.count {
                                        it.child("status")
                                            .getValue(String::class.java)
                                            ?.equals(
                                                "Resolved",
                                                true
                                            ) == true
                                    }

                        if (pending < 0) {
                            pending = 0
                        }

                        val total =
                            pending + progress

                        totalComplaintText.text =
                            "$total\nTotal"

                        pendingComplaintText.text =
                            "$pending\nPending"

                        inProgressText.text =
                            "$progress\nProgress"

                        resolvedText.text =
                            "$resolved\nResolved"

                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }

                }

            )

    }
}