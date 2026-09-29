package dev.virtualnet

import android.app.Application

class VirtualNetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}
