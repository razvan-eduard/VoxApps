package com.voxapps.hub

import android.app.Application
import com.voxapps.hub.di.HubContainer

class HubApplication : Application() {
    lateinit var container: HubContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = HubContainer(this)
    }
}
