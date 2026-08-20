package com.demo.app

import okhttp3.OkHttpClient
import okhttp3.Request

class SyncClient {

    private val client = OkHttpClient()

    fun push(payload: String): Boolean {
        val request = Request.Builder().url("https://api.example.com/sync").build()
        return client.newCall(request).execute().isSuccessful
    }
}
