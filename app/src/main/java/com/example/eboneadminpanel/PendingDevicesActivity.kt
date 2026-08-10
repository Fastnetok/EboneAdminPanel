package com.example.eboneadminpanel

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class PendingDevicesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView

    private lateinit var adapter: PendingDeviceAdapter

    private val deviceList =
        mutableListOf<PendingDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_pending_devices
        )

        recyclerView =
            findViewById(
                R.id.recyclerView
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            PendingDeviceAdapter(
                deviceList
            )

        recyclerView.adapter =
            adapter

        loadPendingDevices()
    }

    private fun loadPendingDevices() {

        FirebaseDatabase
            .getInstance()
            .getReference("PendingDevices")
            .addValueEventListener(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        deviceList.clear()

                        for (
                        deviceSnapshot
                        in snapshot.children
                        ) {

                            val device =
                                deviceSnapshot.getValue(
                                    PendingDevice::class.java
                                )

                            if (device != null) {

                                deviceList.add(
                                    device
                                )
                            }
                        }

                        adapter.notifyDataSetChanged()
                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }
                }
            )
    }
}