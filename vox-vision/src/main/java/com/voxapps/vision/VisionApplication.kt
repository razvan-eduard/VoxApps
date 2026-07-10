package com.voxapps.vision

import android.app.Application
import com.voxapps.vision.di.VisionContainer

class VisionApplication : Application() {
    lateinit var container: VisionContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = VisionContainer(this)
    }
}
