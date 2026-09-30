package dev.virtualnet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Debug builds only: lets the lab scripts set a mode without tapping the UI. */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("cov")?.let { Store.setCoverage(Coverage.of(it)); Log.i("VirtualNet", "debug coverage $it") }
        val pkg = intent.getStringExtra("pkg") ?: return
        val mode = Mode.of(intent.getStringExtra("mode"))
        Store.setMode(pkg, mode)
        Log.i("VirtualNet", "debug set $pkg -> $mode service=${Store.service != null}")
    }
}
