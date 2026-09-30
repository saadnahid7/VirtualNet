package com.droidrooter.virtualnet.hooks.common

import android.net.DhcpInfo
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import com.droidrooter.virtualnet.config.Profile
import java.net.Inet4Address
import java.net.InetAddress

/** Rewrites framework objects so every field an app can read tells the same story. */
internal object Shape {
    const val CELL = 0
    const val WIFI = 1

    fun v4(text: String): Inet4Address? = runCatching { InetAddress.getByName(text) as? Inet4Address }.getOrNull()

    /** Little-endian int as used by WifiInfo.getIpAddress() and DhcpInfo. */
    fun v4Int(text: String): Int {
        val b = v4(text)?.address ?: return 0
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }

    fun mask(prefix: Int): Int {
        val m = if (prefix <= 0) 0 else (-1 shl (32 - prefix.coerceAtMost(32)))
        return Integer.reverseBytes(m)
    }

    // ---- legacy NetworkInfo -------------------------------------------------------------

    fun netInfo(ni: NetworkInfo, type: Int, connected: Boolean, p: Profile) {
        val state = if (connected) NetworkInfo.State.CONNECTED else NetworkInfo.State.DISCONNECTED
        val detail = if (connected) NetworkInfo.DetailedState.CONNECTED else NetworkInfo.DetailedState.DISCONNECTED
        Refl.set(ni, "mNetworkType", type)
        Refl.set(ni, "mTypeName", if (type == WIFI) "WIFI" else "MOBILE")
        Refl.set(ni, "mSubtype", if (type == CELL) p.techType else 0)
        Refl.set(ni, "mSubtypeName", if (type == CELL) p.techName else "")
        Refl.set(ni, "mState", state)
        Refl.set(ni, "mDetailedState", detail)
        Refl.set(ni, "mIsAvailable", true)
        Refl.set(ni, "mIsRoaming", false)
        Refl.set(ni, "mIsFailover", false)
        Refl.set(ni, "mExtraInfo", if (!connected) null else if (type == WIFI) "\"${p.ssid}\"" else "internet")
    }

    fun newNetInfo(type: Int): NetworkInfo? =
        Refl.new(NetworkInfo::class.java, type, 0, if (type == WIFI) "WIFI" else "MOBILE", "") as? NetworkInfo

    // ---- NetworkCapabilities ------------------------------------------------------------

    fun transport(nc: NetworkCapabilities, t: Int) = runCatching { nc.hasTransport(t) }.getOrDefault(false)

    fun caps(nc: NetworkCapabilities, kind: Int, p: Profile) {
        val n = NetworkCapabilities::class.java
        // Keep VPN (4) untouched; every other transport is replaced by the one we present.
        for (t in 0..10) if (t != 4) Refl.tryCall(nc, n, "removeTransportType", t)
        Refl.call(nc, n, "addTransportType", kind)
        for (c in intArrayOf(12, 13, 14, 16, 18, 19, 20, 21)) Refl.call(nc, n, "addCapability", c)
        Refl.call(nc, n, "removeCapability", 17) // captive portal
        if (kind == WIFI) {
            Refl.call(nc, n, "addCapability", 11) // NOT_METERED, the usual "is this Wi-Fi" test
            Refl.call(nc, n, "setSignalStrength", p.rssi)
            Refl.call(nc, n, "setLinkDownstreamBandwidthKbps", p.linkSpeed * 1000)
            Refl.call(nc, n, "setLinkUpstreamBandwidthKbps", p.linkSpeed * 500)
            Refl.tryCall(nc, n, "setTransportInfo", wifiInfo(p))
        } else {
            Refl.call(nc, n, "removeCapability", 11)
            Refl.tryCall(nc, n, "removeCapability", 25) // TEMPORARILY_NOT_METERED (Android 11+)
            Refl.call(nc, n, "setSignalStrength", -85)
            Refl.call(nc, n, "setLinkDownstreamBandwidthKbps", 60000)
            Refl.call(nc, n, "setLinkUpstreamBandwidthKbps", 20000)
            Refl.tryCall(nc, n, "setTransportInfo", null as Any?)
        }
    }

    // ---- WifiInfo -----------------------------------------------------------------------

    /** A disconnected WifiInfo, exactly what the framework hands out when Wi-Fi is off. */
    fun blankWifiInfo(): WifiInfo? = Refl.new(WifiInfo::class.java) as? WifiInfo

    fun wifiInfo(p: Profile): WifiInfo? {
        val wi = blankWifiInfo() ?: return null
        val w = WifiInfo::class.java
        val ssidObj = runCatching {
            val k = Class.forName("android.net.wifi.WifiSsid")
            runCatching { k.getMethod("fromBytes", ByteArray::class.java).invoke(null, p.ssid.toByteArray()) }.getOrNull()
                ?: k.getMethod("createFromAsciiEncoded", String::class.java).invoke(null, p.ssid)
        }.getOrNull()
        if (ssidObj != null && Refl.tryCall(wi, w, "setSSID", ssidObj).isFailure) Refl.set(wi, "mWifiSsid", ssidObj)
        if (Refl.tryCall(wi, w, "setBSSID", p.bssid).isFailure) Refl.set(wi, "mBSSID", p.bssid)
        if (Refl.tryCall(wi, w, "setRssi", p.rssi).isFailure) Refl.set(wi, "mRssi", p.rssi)
        if (Refl.tryCall(wi, w, "setLinkSpeed", p.linkSpeed).isFailure) Refl.set(wi, "mLinkSpeed", p.linkSpeed)
        if (Refl.tryCall(wi, w, "setFrequency", p.frequency).isFailure) Refl.set(wi, "mFrequency", p.frequency)
        if (Refl.tryCall(wi, w, "setNetworkId", 0).isFailure) Refl.set(wi, "mNetworkId", 0)
        if (Refl.tryCall(wi, w, "setMacAddress", "02:00:00:00:00:00").isFailure) Refl.set(wi, "mMacAddress", "02:00:00:00:00:00")
        val ip = v4(p.ip)
        if (ip != null && Refl.tryCall(wi, w, "setInetAddress", ip).isFailure) Refl.set(wi, "mIpAddress", ip)
        if (Refl.tryCall(wi, w, "setSupplicantState", SupplicantState.COMPLETED).isFailure) {
            Refl.set(wi, "mSupplicantState", SupplicantState.COMPLETED)
        }
        Refl.tryCall(wi, w, "setTxLinkSpeedMbps", p.linkSpeed)
        Refl.tryCall(wi, w, "setRxLinkSpeedMbps", p.linkSpeed)
        return wi
    }

    fun dhcp(connected: Boolean, p: Profile): DhcpInfo {
        val d = DhcpInfo()
        if (connected) {
            d.ipAddress = v4Int(p.ip)
            d.gateway = v4Int(p.gateway)
            d.netmask = mask(p.prefix)
            d.dns1 = v4Int(p.dns)
            d.serverAddress = v4Int(p.gateway)
            d.leaseDuration = 86400
        }
        return d
    }

    // ---- LinkProperties -----------------------------------------------------------------

    fun link(lp: LinkProperties, kind: Int, p: Profile) {
        val k = LinkProperties::class.java
        val iface = if (kind == WIFI) "wlan0" else p.mobileIface
        val ip = if (kind == WIFI) p.ip else p.mobileIp
        val prefix = if (kind == WIFI) p.prefix else 30
        val dns = if (kind == WIFI) p.dns else "8.8.8.8"
        Refl.call(lp, k, "setInterfaceName", iface)
        val addr = v4(ip) ?: return
        val la = runCatching {
            Class.forName("android.net.LinkAddress").getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
                .newInstance(addr, prefix)
        }.getOrNull()
        if (la != null) Refl.call(lp, k, "setLinkAddresses", listOf(la))
        v4(dns)?.let { Refl.call(lp, k, "setDnsServers", listOf(it)) }
    }
}
