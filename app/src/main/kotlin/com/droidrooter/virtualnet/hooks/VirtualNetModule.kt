package com.droidrooter.virtualnet.hooks

import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import com.droidrooter.virtualnet.config.Config
import com.droidrooter.virtualnet.hooks.app.AppHooks
import com.droidrooter.virtualnet.hooks.app.AppState
import com.droidrooter.virtualnet.hooks.system.PhoneHooks
import com.droidrooter.virtualnet.hooks.system.SysState
import com.droidrooter.virtualnet.hooks.system.SystemHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/** Entry point named in META-INF/xposed/java_init.list. Installs the network hooks in scoped apps. */
class VirtualNetModule : XposedModule() {
    private var processName = ""
    private var installed = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName
        if (installed || pkg == "android" || pkg == "com.droidrooter.virtualnet" || processName == "system") return
        if (pkg == "com.android.phone") {
            installed = true
            val prefs = runCatching { getRemotePreferences(Config.GROUP) }.getOrNull() ?: return
            PhoneHooks(this, SysState(prefs) { what, t -> log(Log.WARN, TAG, what, t) }).install(param.classLoader)
            return
        }
        installed = true
        val state = AppState(pkg, getRemotePreferences(Config.GROUP))
        log(Log.INFO, TAG, "install package=$pkg process=$processName pid=${Process.myPid()} mode=${state.mode()} framework=$frameworkName/$frameworkVersion api=$apiVersion")
        AppHooks(this, state).install()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val prefs = runCatching { getRemotePreferences(Config.GROUP) }.getOrNull()
        if (prefs == null) {
            log(Log.WARN, TAG, "system: remote preferences unavailable, system hooks skipped")
            return
        }
        log(Log.INFO, TAG, "system server starting framework=$frameworkName/$frameworkVersion api=$apiVersion")
        val state = SysState(prefs) { what, t -> log(Log.WARN, TAG, what, t) }
        SystemHooks(this, state).install(param.classLoader)
    }

    companion object {
        const val TAG = "VirtualNet"
    }
}
