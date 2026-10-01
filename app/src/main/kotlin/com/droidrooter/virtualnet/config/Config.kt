package com.droidrooter.virtualnet.config

import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** What the target app is told about its connection. */
enum class Mode(val key: String) {
    OFF("off"), WIFI("wifi"), DATA("data"), BOTH("both");

    val wifi get() = this == WIFI || this == BOTH
    val cell get() = this == DATA || this == BOTH

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** Which layer does the work. Android 9 defaults to Apps because the system layer is untested there. */
enum class Coverage(val key: String) {
    SYSTEM("system"), APPS("apps"), BOTH("both");

    /** System framework hooks act. */
    val system get() = this != APPS
    /** Hooks inside scoped apps act. */
    val apps get() = this != SYSTEM

    companion object {
        fun default() = if (Build.VERSION.SDK_INT < 29) APPS else BOTH
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: default()
    }
}

/** The identity shown to apps. One coherent profile so SSID, IP range and interface always agree. */
data class Profile(
    val ssid: String = "Home-WiFi",
    val bssid: String = "d8:07:b6:4a:21:9c",
    val ip: String = "192.168.1.24",
    val prefix: Int = 24,
    val gateway: String = "192.168.1.1",
    val dns: String = "192.168.1.1",
    val rssi: Int = -52,
    val linkSpeed: Int = 433,
    val frequency: Int = 5180,
    val tech: String = "LTE",
    val mobileIface: String = "rmnet_data0",
    val mobileIp: String = "10.72.14.201",
) {
    /** Telephony network-type constant for [tech]. */
    val techType get() = when (tech) { "NR" -> 20; "HSPA+" -> 15; else -> 13 }
    val techName get() = when (tech) { "NR" -> "NR"; "HSPA+" -> "HSPAP"; else -> "LTE" }
}

/** A named saved profile entry. */
data class ProfileEntry(val name: String, val profile: Profile) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        val p = profile
        put("ssid", p.ssid); put("bssid", p.bssid); put("ip", p.ip)
        put("prefix", p.prefix); put("gw", p.gateway); put("dns", p.dns)
        put("rssi", p.rssi); put("speed", p.linkSpeed); put("freq", p.frequency)
        put("tech", p.tech); put("iface", p.mobileIface); put("cip", p.mobileIp)
    }

    companion object {
        fun fromJson(o: JSONObject): ProfileEntry {
            val d = Profile()
            return ProfileEntry(
                name = o.optString("name", "Profile"),
                profile = Profile(
                    ssid = o.optString("ssid", d.ssid),
                    bssid = o.optString("bssid", d.bssid),
                    ip = o.optString("ip", d.ip),
                    prefix = o.optInt("prefix", d.prefix),
                    gateway = o.optString("gw", d.gateway),
                    dns = o.optString("dns", d.dns),
                    rssi = o.optInt("rssi", d.rssi),
                    linkSpeed = o.optInt("speed", d.linkSpeed),
                    frequency = o.optInt("freq", d.frequency),
                    tech = o.optString("tech", d.tech),
                    mobileIface = o.optString("iface", d.mobileIface),
                    mobileIp = o.optString("cip", d.mobileIp),
                )
            )
        }
    }
}

/**
 * Single source of truth for settings. The UI writes here; the hooked process reads the same
 * keys from the framework's remote preferences, so a change needs no reboot and no app restart.
 */
object Config {
    const val GROUP = "virtualnet"
    const val RANDOM_INDEX = -1
    private const val M = "m."
    private const val PROF_LIST = "prof_list"
    private const val ACTIVE_PROF = "active_prof"

    fun coverage(p: SharedPreferences) = Coverage.of(p.getString("cov", null))

    fun setCoverage(e: SharedPreferences.Editor, c: Coverage) { e.putString("cov", c.key) }

    fun mode(p: SharedPreferences, pkg: String) = Mode.of(p.getString(M + pkg, null))

    fun setMode(e: SharedPreferences.Editor, pkg: String, mode: Mode) {
        if (mode == Mode.OFF) e.remove(M + pkg) else e.putString(M + pkg, mode.key)
    }

    fun modes(p: SharedPreferences): Map<String, Mode> =
        p.all.filterKeys { it.startsWith(M) }.mapKeys { it.key.removePrefix(M) }.mapValues { Mode.of(it.value as? String) }

    // ---- Multi-profile storage ----------------------------------------------------------

    fun profileEntries(p: SharedPreferences): List<ProfileEntry> {
        val json = p.getString(PROF_LIST, null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                if (arr.length() > 0)
                    return (0 until arr.length()).map { ProfileEntry.fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) {}
        }
        // Migration: wrap the existing flat profile as a single "Default" entry.
        return listOf(ProfileEntry("Default", profile(p)))
    }

    fun putProfileEntries(e: SharedPreferences.Editor, entries: List<ProfileEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        e.putString(PROF_LIST, arr.toString())
    }

    fun activeProfileIndex(p: SharedPreferences) = p.getInt(ACTIVE_PROF, 0)

    fun setActiveProfileIndex(e: SharedPreferences.Editor, idx: Int) { e.putInt(ACTIVE_PROF, idx) }

    /**
     * Resolves the active (or a random) profile from the multi-profile list. This is what the hooks
     * should call. The result is cached by [AppState]/[SysState], so random picks once per process.
     */
    fun resolvedProfile(p: SharedPreferences): Profile {
        val entries = profileEntries(p)
        if (entries.isEmpty()) return profile(p)
        val idx = activeProfileIndex(p)
        return if (idx == RANDOM_INDEX) entries.random().profile
        else entries.getOrElse(idx.coerceAtLeast(0)) { entries.first() }.profile
    }

    // ---- Legacy single-profile read/write (used for migration and direct flat-key access) ----

    fun profile(p: SharedPreferences): Profile {
        val d = Profile()
        return Profile(
            ssid = p.getString("w.ssid", d.ssid) ?: d.ssid,
            bssid = p.getString("w.bssid", d.bssid) ?: d.bssid,
            ip = p.getString("w.ip", d.ip) ?: d.ip,
            prefix = p.getInt("w.prefix", d.prefix),
            gateway = p.getString("w.gw", d.gateway) ?: d.gateway,
            dns = p.getString("w.dns", d.dns) ?: d.dns,
            rssi = p.getInt("w.rssi", d.rssi),
            linkSpeed = p.getInt("w.speed", d.linkSpeed),
            frequency = p.getInt("w.freq", d.frequency),
            tech = p.getString("c.tech", d.tech) ?: d.tech,
            mobileIface = p.getString("c.iface", d.mobileIface) ?: d.mobileIface,
            mobileIp = p.getString("c.ip", d.mobileIp) ?: d.mobileIp,
        )
    }

    fun putProfile(e: SharedPreferences.Editor, v: Profile) {
        e.putString("w.ssid", v.ssid).putString("w.bssid", v.bssid).putString("w.ip", v.ip)
            .putInt("w.prefix", v.prefix).putString("w.gw", v.gateway).putString("w.dns", v.dns)
            .putInt("w.rssi", v.rssi).putInt("w.speed", v.linkSpeed).putInt("w.freq", v.frequency)
            .putString("c.tech", v.tech).putString("c.iface", v.mobileIface).putString("c.ip", v.mobileIp)
    }
}
