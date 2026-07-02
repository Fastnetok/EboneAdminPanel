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
import com.google.firebase.database.FirebaseDatabase

class GeoFenceActivity : AppCompatActivity(),
    OnMapReadyCallback {

    private lateinit var mMap: GoogleMap

    private lateinit var geofenceNameInput: EditText
    private lateinit var radiusInput: EditText
    private lateinit var saveButton: Button

    private var selectedLocation: LatLng? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_geofence
        )
        Toast.makeText(
            this,
            "GeoFence Opened",
            Toast.LENGTH_LONG
        ).show()
        geofenceNameInput =
            findViewById(
                R.id.geofenceNameInput
            )

        radiusInput =
            findViewById(
                R.id.radiusInput
            )

        saveButton =
            findViewById(
                R.id.saveGeofenceButton
            )

        val mapFragment =
            supportFragmentManager
                .findFragmentById(
                    R.id.map
                ) as SupportMapFragment

        mapFragment.getMapAsync(this)

        saveButton.setOnClickListener {

            saveGeoFence()
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
                15f
            )
        )

        mMap.setOnMapLongClickListener {

            selectedLocation = it

            mMap.clear()

            mMap.addMarker(
                MarkerOptions()
                    .position(it)
                    .title("GeoFence")
            )

            val radius =
                radiusInput.text
                    .toString()
                    .toDoubleOrNull()
                    ?: 100.0

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

    private fun saveGeoFence() {

        val name =
            geofenceNameInput.text
                .toString()
                .trim()

        val radius =
            radiusInput.text
                .toString()
                .toDoubleOrNull()

        if (
            name.isEmpty()
        ) {

            Toast.makeText(
                this,
                "Enter GeoFence Name",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (
            radius == null
        ) {

            Toast.makeText(
                this,
                "Enter Radius",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (
            selectedLocation == null
        ) {

            Toast.makeText(
                this,
                "Long Press On Map",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val geofenceId =

            FirebaseDatabase
                .getInstance()
                .getReference(
                    "geofences"
                )
                .push()
                .key ?: return

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

        FirebaseDatabase
            .getInstance()
            .getReference(
                "geofences"
            )
            .child(
                geofenceId
            )
            .setValue(
                model
            )
            .addOnSuccessListener {

                Toast.makeText(
                    this,
                    "GeoFence Saved",
                    Toast.LENGTH_SHORT
                ).show()

                geofenceNameInput.setText("")
                radiusInput.setText("")
            }
    }
}