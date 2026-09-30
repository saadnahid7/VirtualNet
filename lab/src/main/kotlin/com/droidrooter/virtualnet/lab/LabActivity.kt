package com.droidrooter.virtualnet.lab

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections

/**
 * Reads the connection state through every public route an app could use, and logs one line per probe
 * (tag VNL) so a script can compare runs with and without VirtualNet. It answers "what does this app see?".
 */
class LabActivity : Activity() {
    private class Probe(val section: String, val name: String, val value: String, val says: String)

    private val probes = ArrayList<Probe>()
    private val events = ArrayList<String>()
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var callbacksRegistered = false
    private var httpResult = "not run"
    private var udpResult = "not run"
    private var tcpResult = "not run"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(8)) }
        root.addView(TextView(this).apply { text = "VirtualNet Lab"; textSize = 22f; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD })
        summary = TextView(this).apply { textSize = 15f; setTextColor(Color.DKGRAY); setPadding(0, dp(6), 0, dp(6)) }
        root.addView(summary)
        val bar = LinearLayout(this)
        bar.addView(Button(this).apply { text = "Run"; setOnClickListener { run() } })
        bar.addView(Button(this).apply { text = "Copy report"; setOnClickListener { copy() } })
        root.addView(bar)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val need = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty() && intent.getBooleanExtra("run", false).not()) requestPermissions(need.toTypedArray(), 1)
        registerCallbacks()
        run()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        run()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) = run()

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun add(section: String, name: String, value: String, says: String = "") {
        probes += Probe(section, name, value, says)
        Log.i("VNL", "$section|$name|$value|$says")
    }

    private inline fun probe(section: String, name: String, says: (String) -> String = { "" }, block: () -> String) {
        val v = try { block() } catch (t: Throwable) { "ERR ${t.javaClass.simpleName}: ${t.message}" }
        add(section, name, v, if (v.startsWith("ERR")) "" else says(v))
    }

    private fun typeSays(type: Int?) = when (type) { 1 -> "WIFI"; 0 -> "CELL"; else -> "" }

    private fun run() {
        probes.clear()
        Log.i("VNL", "BEGIN api=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        val cm = getSystemService(ConnectivityManager::class.java)
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val tm = getSystemService(TelephonyManager::class.java)

        val s1 = "ConnectivityManager (legacy NetworkInfo)"
        @Suppress("DEPRECATION")
        run {
            probe(s1, "getActiveNetworkInfo", { typeSays(cm.activeNetworkInfo?.type) }) {
                cm.activeNetworkInfo?.let { "type=${it.type} ${it.typeName} state=${it.state} avail=${it.isAvailable} sub=${it.subtype}/${it.subtypeName} extra=${it.extraInfo}" } ?: "null"
            }
            for (t in intArrayOf(1, 0)) probe(s1, "getNetworkInfo($t)", { if (cm.getNetworkInfo(t)?.isConnected == true) typeSays(t) else "" }) {
                cm.getNetworkInfo(t)?.let { "${it.typeName} connected=${it.isConnected} state=${it.detailedState}" } ?: "null"
            }
            probe(s1, "getAllNetworkInfo") { cm.allNetworkInfo.joinToString { "${it.type}:${it.typeName}=${it.state}" } }
            probe(s1, "isActiveNetworkMetered", { if (cm.isActiveNetworkMetered) "CELL" else "WIFI" }) { cm.isActiveNetworkMetered.toString() }
        }

        val s2 = "ConnectivityManager (NetworkCapabilities)"
        probe(s2, "activeNetwork") { cm.activeNetwork?.toString() ?: "null" }
        val active = cm.activeNetwork
        val nc = active?.let { cm.getNetworkCapabilities(it) }
        probe(s2, "transports", { transportSays(nc) }) { transports(nc) }
        probe(s2, "NOT_METERED", { if (nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) "WIFI" else "CELL" }) {
            nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).toString()
        }
        probe(s2, "capabilities") { caps(nc) }
        probe(s2, "transportInfo") {
            val ti = nc?.transportInfo
            if (ti is WifiInfo) "WifiInfo ssid=${ti.ssid} rssi=${ti.rssi}" else ti?.javaClass?.simpleName ?: "null"
        }
        probe(s2, "signalStrength", { "" }) { nc?.signalStrength.toString() }
        probe(s2, "bandwidth down/up kbps") { "${nc?.linkDownstreamBandwidthKbps}/${nc?.linkUpstreamBandwidthKbps}" }
        probe(s2, "toString") { nc.toString().take(220) }
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n)
            val lp = cm.getLinkProperties(n)
            probe(s2, "network $n", { transportSays(c) }) {
                "${transports(c)} iface=${lp?.interfaceName} addr=${lp?.linkAddresses?.joinToString { it.address.hostAddress ?: "" }} dns=${lp?.dnsServers?.joinToString { it.hostAddress ?: "" }}"
            }
        }

        val s3 = "WifiManager"
        probe(s3, "isWifiEnabled", { if (wm.isWifiEnabled) "WIFI" else "CELL" }) { wm.isWifiEnabled.toString() }
        probe(s3, "wifiState") { wm.wifiState.toString() }
        @Suppress("DEPRECATION")
        val wi = wm.connectionInfo
        probe(s3, "ssid / bssid", { if (wi?.ssid.orEmpty().let { it.isNotEmpty() && !it.contains("unknown") }) "WIFI" else "" }) { "${wi?.ssid} / ${wi?.bssid}" }
        probe(s3, "rssi / linkSpeed / freq") { "${wi?.rssi} dBm / ${wi?.linkSpeed} Mbps / ${wi?.frequency} MHz" }
        probe(s3, "ipAddress", { if ((wi?.ipAddress ?: 0) != 0) "WIFI" else "" }) { wi?.ipAddress?.let { ipText(it) } ?: "null" }
        probe(s3, "networkId / supplicant / mac") { "${wi?.networkId} / ${wi?.supplicantState} / ${wi?.macAddress}" }
        @Suppress("DEPRECATION")
        probe(s3, "dhcpInfo") { wm.dhcpInfo?.let { "ip=${ipText(it.ipAddress)} gw=${ipText(it.gateway)} mask=${ipText(it.netmask)} dns=${ipText(it.dns1)}" } ?: "null" }

        val s4 = "TelephonyManager"
        probe(s4, "networkType", { if (tm.networkType != 0) "CELL" else "" }) { tm.networkType.toString() }
        probe(s4, "dataNetworkType", { if (tm.dataNetworkType != 0) "CELL" else "" }) { tm.dataNetworkType.toString() }
        probe(s4, "dataState (2=connected)", { if (tm.dataState == TelephonyManager.DATA_CONNECTED) "CELL" else "" }) { tm.dataState.toString() }
        probe(s4, "isDataEnabled", { if (tm.isDataEnabled) "CELL" else "" }) { tm.isDataEnabled.toString() }
        probe(s4, "simState / operator") { "${tm.simState} / ${tm.networkOperatorName}" }

        val s5 = "java.net.NetworkInterface"
        probe(s5, "interfaces") {
            Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }.joinToString(" ; ") { i ->
                "${i.name} ${Collections.list(i.inetAddresses).filterIsInstance<Inet4Address>().joinToString { it.hostAddress ?: "" }}"
            }
        }
        for (i in Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }) {
            val hasV4 = Collections.list(i.inetAddresses).any { it is Inet4Address }
            if (hasV4) add(s5, "up+ipv4 ${i.name}", Collections.list(i.inetAddresses).filterIsInstance<Inet4Address>().joinToString { it.hostAddress ?: "" },
                when { i.name.startsWith("wlan") -> "WIFI"; i.name.matches(Regex("^(v4-)?(rmnet|ccmni|pdp|wwan).*")) -> "CELL"; else -> "" })
        }

        val s6 = "Settings"
        probe(s6, "Global wifi_on", { if (Settings.Global.getInt(contentResolver, "wifi_on", -1) == 1) "WIFI" else "" }) { Settings.Global.getInt(contentResolver, "wifi_on", -1).toString() }
        probe(s6, "Global mobile_data", { if (Settings.Global.getInt(contentResolver, "mobile_data", -1) == 1) "CELL" else "" }) { Settings.Global.getInt(contentResolver, "mobile_data", -1).toString() }

        val s8 = "Socket routes"
        probe(s8, "UDP local address") { udpResult }
        probe(s8, "TCP local address") { tcpResult }

        val s9 = "More interface / Wi-Fi routes"
        probe(s9, "getAllNetworks count") { cm.allNetworks.size.toString() }
        probe(s9, "interfaceAddresses") {
            Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }.joinToString(" ; ") { i ->
                "${i.name}: " + i.interfaceAddresses.filter { it.address is Inet4Address }.joinToString { "${it.address.hostAddress}/${it.networkPrefixLength}" }
            }
        }
        probe(s9, "getByName(wlan0)") { NetworkInterface.getByName("wlan0")?.let { "${it.name} up=${it.isUp}" } ?: "null" }
        probe(s9, "scanResults") {
            @Suppress("DEPRECATION")
            val r = wm.scanResults
            "${r.size}: " + r.take(3).joinToString { "${it.SSID}@${it.BSSID} ${it.level}dBm" }
        }
        probe(s9, "/proc/net/dev") { File("/proc/net/dev").readLines().drop(2).joinToString(" ") { it.substringBefore(":").trim() } }
        probe(s9, "/proc/net/if_inet6") { File("/proc/net/if_inet6").readLines().joinToString(" ") { it.trim().split(" ").last() } }
        probe(s9, "/sys/class/net") { File("/sys/class/net").list()?.sorted()?.joinToString(" ") ?: "null" }

        val s7 = "Real path (not hookable from Java)"
        probe(s7, "/proc/net/route default iface") {
            File("/proc/net/route").readLines().drop(1).map { it.split(Regex("\\s+")) }.firstOrNull { it.size > 2 && it[1] == "00000000" }?.get(0) ?: "n/a"
        }
        probe(s7, "HTTPS generate_204") { httpResult }
        fetch()

        probe("Live callbacks", "events (${events.size})") { events.takeLast(6).joinToString("\n").ifEmpty { "waiting" } }
        render()
    }

    private fun fetch() {
        Thread {
            udpResult = try {
                DatagramSocket().use { it.connect(InetAddress.getByName("1.1.1.1"), 53); it.localAddress.hostAddress ?: "" }
            } catch (t: Throwable) {
                "ERR ${t.javaClass.simpleName}"
            }
            Log.i("VNL", "Socket routes|UDP local address|$udpResult|")
            tcpResult = try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("1.1.1.1", 443), 4000); "${it.localAddress.hostAddress} / ${it.localSocketAddress}" }
            } catch (t: Throwable) {
                "ERR ${t.javaClass.simpleName}"
            }
            Log.i("VNL", "Socket routes|TCP local address|$tcpResult|")
            val t0 = System.nanoTime()
            httpResult = try {
                val c = URL("https://connectivitycheck.gstatic.com/generate_204").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                "HTTP ${c.responseCode} in ${(System.nanoTime() - t0) / 1_000_000} ms"
            } catch (t: Throwable) {
                "ERR ${t.javaClass.simpleName}"
            }
            Log.i("VNL", "Real path (not hookable from Java)|HTTPS generate_204|$httpResult|")
            runOnUiThread { render() }
        }.start()
    }

    private fun registerCallbacks() {
        if (callbacksRegistered) return
        callbacksRegistered = true
        val cm = getSystemService(ConnectivityManager::class.java)
        fun cb(tag: String) = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = log("$tag onAvailable $network")
            override fun onLost(network: Network) = log("$tag onLost $network")
            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) =
                log("$tag caps $network ${transports(c)} notMetered=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)} says=${transportSays(c)}")
        }
        runCatching { cm.registerDefaultNetworkCallback(cb("default")) }
        for ((tag, t) in listOf("req-wifi" to NetworkCapabilities.TRANSPORT_WIFI, "req-cell" to NetworkCapabilities.TRANSPORT_CELLULAR)) {
            runCatching {
                cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(t).build(), cb(tag))
            }
        }
    }

    private fun log(text: String) {
        events += text
        Log.i("VNL", "Live callbacks|event|$text|")
        runOnUiThread { render() }
    }

    private fun transports(nc: NetworkCapabilities?): String {
        if (nc == null) return "null"
        val names = mapOf(0 to "CELLULAR", 1 to "WIFI", 2 to "BLUETOOTH", 3 to "ETHERNET", 4 to "VPN", 5 to "WIFI_AWARE", 6 to "LOWPAN", 8 to "USB")
        return names.filter { runCatching { nc.hasTransport(it.key) }.getOrDefault(false) }.values.joinToString("+").ifEmpty { "none" }
    }

    private fun transportSays(nc: NetworkCapabilities?): String {
        if (nc == null) return ""
        val w = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val c = nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        return when { w && c -> "BOTH"; w -> "WIFI"; c -> "CELL"; else -> "" }
    }

    private fun caps(nc: NetworkCapabilities?): String {
        if (nc == null) return "null"
        val names = mapOf(11 to "NOT_METERED", 12 to "INTERNET", 13 to "NOT_RESTRICTED", 14 to "TRUSTED", 15 to "NOT_VPN", 16 to "VALIDATED",
            17 to "CAPTIVE_PORTAL", 18 to "NOT_ROAMING", 19 to "FOREGROUND", 20 to "NOT_CONGESTED", 21 to "NOT_SUSPENDED")
        return names.filter { runCatching { nc.hasCapability(it.key) }.getOrDefault(false) }.values.joinToString(" ")
    }

    private fun ipText(v: Int) = "${v and 255}.${v shr 8 and 255}.${v shr 16 and 255}.${v shr 24 and 255}"

    private fun render() {
        list.removeAllViews()
        val api = probes.filter { it.section != "Real path (not hookable from Java)" && it.section != "Live callbacks" }
        val wifi = api.count { it.says == "WIFI" || it.says == "BOTH" }
        val cell = api.count { it.says == "CELL" || it.says == "BOTH" }
        summary.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  ·  says Wi-Fi: $wifi   says mobile: $cell\n" +
            when {
                wifi > 0 && cell == 0 -> "Verdict: WI-FI only"
                cell > 0 && wifi == 0 -> "Verdict: MOBILE only"
                wifi > 0 -> "Verdict: BOTH"
                else -> "Verdict: nothing"
            }
        Log.i("VNL", "END wifi=$wifi cell=$cell")
        var section = ""
        for (p in probes) {
            if (p.section != section) {
                section = p.section
                list.addView(TextView(this).apply {
                    text = section; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#0F8B6D")); setPadding(0, dp(14), 0, dp(4))
                })
            }
            list.addView(TextView(this).apply {
                text = "${p.name}\n${p.value}${if (p.says.isNotEmpty()) "   [${p.says}]" else ""}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.MONOSPACE
                setTextColor(when (p.says) { "WIFI" -> Color.parseColor("#1F4FD8"); "CELL" -> Color.parseColor("#B45309"); else -> Color.DKGRAY })
                setPadding(0, dp(3), 0, dp(3))
                gravity = Gravity.START
                visibility = View.VISIBLE
            })
        }
    }

    private fun copy() {
        val text = summary.text.toString() + "\n\n" + probes.joinToString("\n") { "${it.section} | ${it.name} | ${it.value} | ${it.says}" }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("VirtualNet Lab", text))
    }
}
