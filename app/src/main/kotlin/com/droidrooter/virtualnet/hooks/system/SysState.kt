package com.droidrooter.virtualnet.hooks.system

import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import com.droidrooter.virtualnet.config.Config
import com.droidrooter.virtualnet.config.Mode
import com.droidrooter.virtualnet.config.Profile
import java.util.concurrent.ConcurrentHashMap

/**
 * system_server view of the settings. Callers are identified by uid, so the package-keyed settings are
 * resolved to app ids on a background thread; a hooked call never waits on the package manager.
 */
internal class SysState(private val prefs: SharedPreferences, private val log: (String, Throwable?) -> Unit) {
    @Volatile private var byAppId: Map<Int, Mode> = emptyMap()
    @Volatile private var profileCache: Profile? = null
    private val thread = HandlerThread("virtualnet-sys").apply { start() }
    private val handler = Handler(thread.looper)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> profileCache = null; refresh() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    fun profile(): Profile = profileCache ?: Config.profile(prefs).also { profileCache = it }

    /** Mode for the app that owns [uid]; system and root callers are never touched. */
    fun mode(uid: Int): Mode {
        val appId = uid % 100000
        if (appId < 10000 || !Config.coverage(prefs).system) return Mode.OFF
        return byAppId[appId] ?: Mode.OFF
    }

    fun refresh(attempt: Int = 0) {
        handler.removeCallbacksAndMessages(null)
        handler.post {
            val wanted = Config.modes(prefs).filterValues { it != Mode.OFF }
            if (wanted.isEmpty()) { byAppId = emptyMap(); log("modes resolved: none configured", null); return@post }
            val pm = runCatching {
                val at = Class.forName("android.app.ActivityThread")
                val thread = at.getMethod("currentActivityThread").invoke(null)
                (at.getMethod("getSystemContext").invoke(thread) as android.content.Context).packageManager
            }.getOrNull()
            if (pm == null) {
                if (attempt < 20) handler.postDelayed({ refresh(attempt + 1) }, 3000)
                return@post
            }
            val map = ConcurrentHashMap<Int, Mode>()
            for ((pkg, mode) in wanted) {
                val uid = runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: continue
                map[uid % 100000] = mode
            }
            byAppId = map
            log("modes resolved: ${map.size} of ${wanted.size} apps", null)
        }
    }
}
