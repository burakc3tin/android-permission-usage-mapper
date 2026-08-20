package com.demo.app

import android.content.Context
import android.location.LocationManager
import com.google.android.gms.location.LocationServices

class LocationRepository(private val context: Context) {

    fun observeLocation() {
        val client = LocationServices.getFusedLocationProviderClient(context)
        client.lastLocation
        requestLegacyUpdates()
    }

    private fun requestLegacyUpdates() {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 10f) { }
    }
}
