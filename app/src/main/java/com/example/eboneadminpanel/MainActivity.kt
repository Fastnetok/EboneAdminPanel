package com.example.eboneadminpanel

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.database.*

class MainActivity : AppCompatActivity(),
    OnMapReadyCallback {

    private lateinit var mMap: GoogleMap

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

        FirebaseDatabase
            .getInstance()
            .getReference("employees")
            .addValueEventListener(
                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {


                        mMap.clear()

                        val onlineList =
                            mutableListOf<String>()

                        val offlineList =
                            mutableListOf<String>()

                        var firstLocation:
                                LatLng? = null

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

                                mMap.addMarker(
                                    MarkerOptions()
                                        .position(location)
                                        .title(employeeName)
                                )

                                if (
                                    firstLocation ==
                                    null
                                ) {

                                    firstLocation =
                                        location
                                }
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

                        firstLocation?.let {

                            mMap.moveCamera(
                                CameraUpdateFactory
                                    .newLatLngZoom(
                                        it,
                                        15f
                                    )
                            )
                        }
                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }
                }
            )
    }

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