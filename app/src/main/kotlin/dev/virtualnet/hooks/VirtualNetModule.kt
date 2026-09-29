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

/** Entry point named in META-INF/xposed/java_init.list. Installs the network hooks in scoped apps. */
class VirtualNetModule : XposedModule() {
    private var processName = ""
    private var installed = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName
        if (installed || pkg == "android" || pkg == "dev.virtualnet") return
        installed = true
        val state = State(pkg, getRemotePreferences(Config.GROUP))
        log(Log.INFO, TAG, "install package=$pkg process=$processName pid=${Process.myPid()} mode=${state.mode()} framework=$frameworkName/$frameworkVersion api=$apiVersion")
        NetHooks(this, state).install()
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
