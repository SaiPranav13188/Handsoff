package com.handsoff.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.handsoff.app.data.DeviceStore
import com.handsoff.app.nearby.NearbyManager
import com.handsoff.app.nearby.OutItem

class HandsoffApp : Application() {
    lateinit var store: DeviceStore
        private set
    lateinit var manager: NearbyManager
        private set

    /** Items picked to send; kept here so they survive screen rotation. */
    val selection = mutableStateListOf<OutItem>()

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        store = DeviceStore(this)
        manager = NearbyManager(this, store)
    }

    companion object {
        fun from(context: Context) = context.applicationContext as HandsoffApp
    }
}
