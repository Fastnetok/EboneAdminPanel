package com.example.eboneadminpanel

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.database.*

class MapActivity : AppCompatActivity(),
    OnMapReadyCallback {

    private lateinit var mMap: GoogleMap

    private var employeeMarker: Marker? = null
    private var geofenceMarker: Marker? = null
    private var geofenceCircle: Circle? = null
    private var selectedLocation: LatLng? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_map)
        val radiusInput =
            findViewById<android.widget.EditText>(
                R.id.radiusInput
            )

        val saveButton =
            findViewById<android.widget.Button>(
                R.id.saveGeofenceButton
            )
        val mapFragment =
            supportFragmentManager
                .findFragmentById(R.id.map)
                    as SupportMapFragment

        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(
        googleMap: GoogleMap
    ) {

        mMap = googleMap

        mMap.mapType =
            GoogleMap.MAP_TYPE_SATELLITE

        mMap.setOnMapLongClickListener {
            selectedLocation = it
            geofenceMarker?.remove()
            geofenceCircle?.remove()

            geofenceMarker =
                mMap.addMarker(
                    MarkerOptions()
                        .position(it)
                        .title("Geofence Center")
                )

            geofenceCircle =
                mMap.addCircle(
                    CircleOptions()
                        .center(it)
                        .radius(200.0)
                        .strokeWidth(4f)
                        .strokeColor(Color.RED)
                        .fillColor(0x30FF0000)
                )
        }

        val employeeId =
            intent.getStringExtra(
                "employeeId"
            ) ?: return

        FirebaseDatabase
            .getInstance()
            .getReference("employees")
            .child(employeeId)
            .addValueEventListener(
                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        val latitude =
                            snapshot.child("latitude")
                                .value?.toString()
                                ?.toDoubleOrNull()
                                ?: return

                        val longitude =
                            snapshot.child("longitude")
                                .value?.toString()
                                ?.toDoubleOrNull()
                                ?: return

                        val employeeName =
                            snapshot.child("employeeName")
                                .value?.toString()
                                ?: "Employee"

                        val employeeLocation =
                            LatLng(
                                latitude,
                                longitude
                            )

                        if (employeeMarker == null) {

                            employeeMarker =
                                mMap.addMarker(
                                    MarkerOptions()
                                        .position(employeeLocation)
                                        .title(employeeName)
                                )

                            mMap.moveCamera(
                                CameraUpdateFactory
                                    .newLatLngZoom(
                                        employeeLocation,
                                        18f
                                    )
                            )

                        } else {

                            employeeMarker?.position =
                                employeeLocation
                        }
                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }
                }
            )
    }
}