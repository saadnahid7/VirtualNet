package dev.virtualnet

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Settings live in local preferences first, and are mirrored into the framework's remote preferences
 * whenever the module is active, which is what the hooked apps read.
 */
object Store {
    private lateinit var local: SharedPreferences
    @Volatile var service: XposedService? = null
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val prefs: SharedPreferences get() = local

    fun init(context: Context) {
        local = context.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                this@Store.service = service
                mirrorAll()
                notifyChanged()
            }

            override fun onServiceDied(service: XposedService) {
                this@Store.service = null
                notifyChanged()
            }
        })
    }

    fun onChange(l: () -> Unit) { listeners += l }
    fun removeOnChange(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = listeners.forEach { it() }

    private fun remote(): SharedPreferences? = runCatching { service?.getRemotePreferences(Config.GROUP) }.getOrNull()

    private fun mirrorAll() {
        val r = remote() ?: return
        val e = r.edit().clear()
        for ((k, v) in local.all) when (v) {
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
        }
        e.apply()
    }

    fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val l = local.edit().also(block)
        l.apply()
        remote()?.edit()?.also(block)?.apply()
        notifyChanged()
    }

    fun mode(pkg: String) = Config.mode(local, pkg)
    fun setMode(pkg: String, mode: Mode) = edit { Config.setMode(it, pkg, mode) }
    fun coverage() = Config.coverage(local)
    fun setCoverage(c: Coverage) = edit { Config.setCoverage(it, c) }
    fun profile() = Config.profile(local)
    fun setProfile(p: Profile) = edit { Config.putProfile(it, p) }
}
