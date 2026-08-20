package com.demo.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TrackingService : Service() {

    private val locationRepository = LocationRepository(this)
    private val syncClient = SyncClient()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        locationRepository.observeLocation()
        syncClient.push("tick")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
