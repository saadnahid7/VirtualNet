package com.droidrooter.virtualnet

import android.app.Application
import com.droidrooter.virtualnet.config.Store

class VirtualNetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}
