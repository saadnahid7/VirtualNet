package com.droidrooter.virtualnet.hooks.app

import android.content.SharedPreferences
import com.droidrooter.virtualnet.config.Config
import com.droidrooter.virtualnet.config.Mode
import com.droidrooter.virtualnet.config.Profile

/** Per-process view of the module settings. Reads are cheap; the profile is cached until a change. */
internal class AppState(val pkg: String, private val prefs: SharedPreferences) {
    @Volatile private var cached: Profile? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cached = null }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun mode(): Mode = if (Config.coverage(prefs).apps) Config.mode(prefs, pkg) else Mode.OFF

    fun profile(): Profile = cached ?: Config.profile(prefs).also { cached = it }
}
