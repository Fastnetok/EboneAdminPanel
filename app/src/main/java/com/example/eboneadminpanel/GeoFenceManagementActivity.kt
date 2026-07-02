package com.example.eboneadminpanel

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.database.*

class GeoFenceManagementActivity :
    AppCompatActivity(),
    OnMapReadyCallback {

    private lateinit var mMap: GoogleMap

    private lateinit var geofenceNameInput: EditText
    private lateinit var radiusInput: EditText
    private lateinit var saveButton: Button

    private var selectedLocation: LatLng? = null

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_geofence_management
        )

        geofenceNameInput =
            findViewById(R.id.geofenceNameInput)

        radiusInput =
            findViewById(R.id.radiusInput)

        saveButton =
            findViewById(R.id.saveGeofenceButton)

        val mapFragment =
            supportFragmentManager
                .findFragmentById(
                    R.id.map
                ) as SupportMapFragment

        mapFragment.getMapAsync(this)
        saveButton.setOnClickListener {

            FirebaseDatabase
                .getInstance()
                .getReference("geofences")
                .addListenerForSingleValueEvent(

                    object : ValueEventListener {

                        override fun onDataChange(
                            snapshot: DataSnapshot
                        ) {

                            if (snapshot.exists()) {

                                android.app.AlertDialog.Builder(
                                    this@GeoFenceManagementActivity
                                )
                                    .setTitle(
                                        "GeoFence Exists"
                                    )
                                    .setMessage(
                                        "Replace Existing GeoFence?"
                                    )
                                    .setPositiveButton(
                                        "YES"
                                    ) { _, _ ->

                                        saveGeoFence()

                                    }
                                    .setNegativeButton(
                                        "NO",
                                        null
                                    )
                                    .show()

                            } else {

                                saveGeoFence()

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

    override fun onMapReady(
        googleMap: GoogleMap
    ) {

        mMap = googleMap

        mMap.mapType =
            GoogleMap.MAP_TYPE_SATELLITE

        mMap.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(
                    30.8081,
                    73.4458
                ),
                14f
            )
        )

        loadActiveGeoFence()

        mMap.setOnMapLongClickListener {

            selectedLocation = it

            mMap.clear()

            val radius =
                radiusInput.text
                    .toString()
                    .toDoubleOrNull()
                    ?: 200.0

            mMap.addMarker(
                MarkerOptions()
                    .position(it)
                    .title("Active GeoFence")
            )

            mMap.addCircle(
                CircleOptions()
                    .center(it)
                    .radius(radius)
                    .strokeWidth(4f)
                    .strokeColor(Color.RED)
                    .fillColor(0x30FF0000)
            )
        }
    }

    private fun loadActiveGeoFence() {

        FirebaseDatabase
            .getInstance()
            .getReference("geofences")
            .limitToLast(1)
            .addListenerForSingleValueEvent(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        mMap.clear()

                        for (geo in snapshot.children) {

                            val latitude =
                                geo.child("latitude")
                                    .getValue(Double::class.java)
                                    ?: continue

                            val longitude =
                                geo.child("longitude")
                                    .getValue(Double::class.java)
                                    ?: continue

                            val radius =
                                geo.child("radius")
                                    .getValue(Double::class.java)
                                    ?: 200.0

                            val name =
                                geo.child("name")
                                    .getValue(String::class.java)
                                    ?: "GeoFence"

                            val center =
                                LatLng(
                                    latitude,
                                    longitude
                                )

                            mMap.addMarker(
                                MarkerOptions()
                                    .position(center)
                                    .title(name)
                            )

                            mMap.addCircle(
                                CircleOptions()
                                    .center(center)
                                    .radius(radius)
                                    .strokeWidth(4f)
                                    .strokeColor(Color.RED)
                                    .fillColor(0x30FF0000)
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

    private fun saveGeoFence() {

        val name =
            geofenceNameInput.text
                .toString()
                .trim()

        val radius =
            radiusInput.text
                .toString()
                .toDoubleOrNull()

        if (name.isEmpty()) {

            Toast.makeText(
                this,
                "Enter Name",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (radius == null) {

            Toast.makeText(
                this,
                "Enter Radius",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (selectedLocation == null) {

            Toast.makeText(
                this,
                "Long Press On Map",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val geoRef =
            FirebaseDatabase
                .getInstance()
                .getReference("geofences")

        geoRef.removeValue()
            .addOnSuccessListener {

                val geofenceId =
                    geoRef.push().key
                        ?: return@addOnSuccessListener

                val model =
                    GeoFenceModel(

                        geofenceId =
                            geofenceId,

                        name =
                            name,

                        latitude =
                            selectedLocation!!.latitude,

                        longitude =
                            selectedLocation!!.longitude,

                        radius =
                            radius
                    )

                geoRef
                    .child(geofenceId)
                    .setValue(model)
                    .addOnSuccessListener {

                        Toast.makeText(
                            this,
                            "GeoFence Saved",
                            Toast.LENGTH_SHORT
                        ).show()

                        loadActiveGeoFence()
                    }
            }
    }
}