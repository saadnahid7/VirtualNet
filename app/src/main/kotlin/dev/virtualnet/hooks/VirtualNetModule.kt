package dev.virtualnet.hooks

import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import dev.virtualnet.Config
import dev.virtualnet.Mode
import dev.virtualnet.Profile
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
        if (installed || pkg == "android" || pkg == "dev.virtualnet" || processName == "system") return
        if (pkg == "com.android.phone") {
            installed = true
            val prefs = runCatching { getRemotePreferences(Config.GROUP) }.getOrNull() ?: return
            PhoneHooks(this, SysState(prefs) { what, t -> log(Log.WARN, TAG, what, t) }).install(param.classLoader)
            return
        }
        installed = true
        val state = State(pkg, getRemotePreferences(Config.GROUP))
        log(Log.INFO, TAG, "install package=$pkg process=$processName pid=${Process.myPid()} mode=${state.mode()} framework=$frameworkName/$frameworkVersion api=$apiVersion")
        NetHooks(this, state).install()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val prefs = runCatching { getRemotePreferences(Config.GROUP) }.getOrNull()
        if (prefs == null) {
            log(Log.WARN, TAG, "system: remote preferences unavailable, system hooks skipped")
            return
        }
        log(Log.INFO, TAG, "system server starting framework=$frameworkName/$frameworkVersion api=$apiVersion")
        val state = SysState(prefs) { what, t -> log(Log.WARN, TAG, what, t) }
        SystemHooks(this, state).install()
    }

    companion object {
        const val TAG = "VirtualNet"
    }
}

/** Per-process view of the module settings. Reads are cheap; the profile is cached until a change. */
internal class State(val pkg: String, private val prefs: SharedPreferences) {
    @Volatile private var cached: Profile? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cached = null }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun mode(): Mode = Config.mode(prefs, pkg)

    fun profile(): Profile = cached ?: Config.profile(prefs).also { cached = it }
}
