package com.droidrooter.virtualnet.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.droidrooter.virtualnet.R
import com.droidrooter.virtualnet.config.Profile
import com.droidrooter.virtualnet.config.ProfileEntry
import com.droidrooter.virtualnet.config.Store
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Edits or creates a single named profile. Extras: EXTRA_INDEX, EXTRA_NAME, EXTRA_PROFILE_JSON, EXTRA_FILL_CURRENT. */
class ProfileActivity : Activity() {
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var tech: Segmented
    private lateinit var error: TextView
    private lateinit var nameField: EditText
    private val techs = listOf("LTE", "NR", "HSPA+")
    private val io = Executors.newSingleThreadExecutor()

    private val entryIndex get() = intent.getIntExtra(EXTRA_INDEX, -1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        applyInsets(root)

        val isNew = entryIndex < 0
        val title = if (isNew) "New profile" else "Edit profile"

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(12), dp(16), dp(4)) }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(color(R.color.vn_text))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(label(title, 20f, medium = true))
        root.addView(bar)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }

        // Profile name
        val existingName = intent.getStringExtra(EXTRA_NAME) ?: if (isNew) "" else "Profile"
        val nameCard = card()
        nameCard.addView(label("Profile name", 12f, R.color.vn_muted, true))
        nameField = EditText(this).apply {
            setText(existingName)
            hint = "e.g. Home, Office…"
            setSingleLine()
            textSize = 15f
            setTextColor(color(R.color.vn_text))
            background = shape(color(R.color.vn_bg), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        nameCard.addView(nameField, lp(top = 6))
        body.addView(nameCard)

        // Load profile from intent or fall back to defaults
        val p: Profile = run {
            val json = intent.getStringExtra(EXTRA_PROFILE_JSON)
            if (json != null) runCatching { ProfileEntry.fromJson(JSONObject(json)).profile }.getOrDefault(Profile())
            else if (!isNew) Store.profiles().getOrNull(entryIndex)?.profile ?: Profile()
            else Profile()
        }

        val wifi = card()
        wifi.addView(label("Wi-Fi", 15f, medium = true))
        wifi.addView(label("What an app sees when it is shown Wi-Fi.", 13f, R.color.vn_muted), lp(top = 2))
        field(wifi, "ssid", "Network name (SSID)", p.ssid)
        field(wifi, "bssid", "Router MAC (BSSID)", p.bssid)
        field(wifi, "ip", "Local IP address", p.ip)
        field(wifi, "prefix", "Prefix length (24 = 255.255.255.0)", p.prefix.toString(), number = true)
        field(wifi, "gw", "Gateway", p.gateway)
        field(wifi, "dns", "DNS server", p.dns)
        field(wifi, "rssi", "Signal (dBm, -30 to -90)", p.rssi.toString(), number = true, signed = true)
        field(wifi, "speed", "Link speed (Mbps)", p.linkSpeed.toString(), number = true)
        field(wifi, "freq", "Frequency (MHz)", p.frequency.toString(), number = true)
        wifi.addView(button("Fill from current Wi-Fi", false) { fillWifi() }, lp(top = 12))
        body.addView(wifi)

        val cell = card()
        cell.addView(label("Mobile data", 15f, medium = true))
        cell.addView(label("What an app sees when it is shown mobile data.", 13f, R.color.vn_muted), lp(top = 2))
        cell.addView(label("Network type", 12f, R.color.vn_muted, true), lp(top = 14))
        tech = Segmented(this, techs) { tech.selected = it }
        tech.selected = techs.indexOf(p.tech).coerceAtLeast(0)
        cell.addView(tech, lp(top = 6))
        field(cell, "iface", "Interface name", p.mobileIface)
        field(cell, "cip", "Local IP address", p.mobileIp)
        cell.addView(button("Fill from current mobile data", false) { fillMobile() }, lp(top = 12))
        body.addView(cell, lp(top = 12))

        error = label("", 13f, R.color.vn_warn, true).apply { visibility = View.GONE }
        body.addView(error, lp(top = 12))

        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        actions.addView(button("Randomize", false) { randomize() })
        actions.addView(button("Reset", false) { fill(Profile()) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        actions.addView(button("Save", true) { save() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        body.addView(actions, lp(top = 16))

        root.addView(ScrollView(this).apply { addView(body); isFillViewport = true }, lp(h = 0, weight = 1f))
        setContentView(root)

        if (intent.getBooleanExtra(EXTRA_FILL_CURRENT, false)) {
            fillWifi()
            fillMobile()
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun field(parent: LinearLayout, key: String, hint: String, value: String, number: Boolean = false, signed: Boolean = false) {
        parent.addView(label(hint, 12f, R.color.vn_muted, true), lp(top = 14))
        val e = EditText(this).apply {
            setText(value)
            setSingleLine()
            textSize = 15f
            setTextColor(color(R.color.vn_text))
            background = shape(color(R.color.vn_bg), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = if (number) android.text.InputType.TYPE_CLASS_NUMBER or (if (signed) android.text.InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        fields[key] = e
        parent.addView(e, lp(top = 6))
    }

    private fun button(text: String, primary: Boolean, click: () -> Unit) =
        label(text, 14f, if (primary) R.color.vn_surface else R.color.vn_text, true).apply {
            gravity = Gravity.CENTER
            background = if (primary) shape(color(R.color.vn_accent), 10) else shape(color(R.color.vn_surface), 10, color(R.color.vn_line))
            setPadding(dp(22), dp(11), dp(22), dp(11))
            setOnClickListener { click() }
        }

    private fun text(k: String) = fields[k]!!.text.toString().trim()

    private fun fill(p: Profile) {
        fields["ssid"]!!.setText(p.ssid); fields["bssid"]!!.setText(p.bssid); fields["ip"]!!.setText(p.ip)
        fields["prefix"]!!.setText(p.prefix.toString()); fields["gw"]!!.setText(p.gateway); fields["dns"]!!.setText(p.dns)
        fields["rssi"]!!.setText(p.rssi.toString()); fields["speed"]!!.setText(p.linkSpeed.toString())
        fields["freq"]!!.setText(p.frequency.toString()); fields["iface"]!!.setText(p.mobileIface)
        fields["cip"]!!.setText(p.mobileIp); tech.selected = techs.indexOf(p.tech).coerceAtLeast(0)
        error.visibility = View.GONE
    }

    // ---- Fill from current network --------------------------------------------------------

    private fun fillWifi() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val wm = getSystemService(WifiManager::class.java) ?: return
        io.execute {
            val info = wm.connectionInfo
            val lp = runCatching { cm.getLinkProperties(cm.activeNetwork) }.getOrNull()

            val ipStr = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: ""
            val prefixLen = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.prefixLength ?: 24
            val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress ?: ""
            val dns = lp?.dnsServers?.firstOrNull { it is Inet4Address }?.hostAddress ?: ""
            val rssi = if (info != null && info.rssi != Integer.MIN_VALUE) info.rssi else null
            val speed = info?.linkSpeed
            val freq = if (Build.VERSION.SDK_INT >= 21) info?.frequency else null

            // SSID/BSSID require location or NEARBY_WIFI_DEVICES — try, leave blank if blocked
            val (ssid, bssid) = readSsidBssid(wm, info)

            runOnUiThread {
                if (ssid != null) fields["ssid"]!!.setText(ssid)
                if (bssid != null) fields["bssid"]!!.setText(bssid)
                if (ipStr.isNotEmpty()) fields["ip"]!!.setText(ipStr)
                fields["prefix"]!!.setText(prefixLen.toString())
                if (gw.isNotEmpty()) fields["gw"]!!.setText(gw)
                if (dns.isNotEmpty()) fields["dns"]!!.setText(dns)
                if (rssi != null) fields["rssi"]!!.setText(rssi.toString())
                if (speed != null && speed > 0) fields["speed"]!!.setText(speed.toString())
                if (freq != null && freq > 0) fields["freq"]!!.setText(freq.toString())
            }
        }
    }

    private fun fillMobile() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        io.execute {
            val techStr = runCatching {
                val type = if (Build.VERSION.SDK_INT >= 24) tm.dataNetworkType else @Suppress("DEPRECATION") tm.networkType
                when (type) {
                    TelephonyManager.NETWORK_TYPE_NR -> "NR"
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
                    else -> "LTE"
                }
            }.getOrDefault("LTE")

            // Find the cellular network's link properties
            val allNetworks = cm.allNetworks
            var cellIp = ""
            var cellIface = ""
            for (net in allNetworks) {
                val nc = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: continue
                if (!nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) continue
                val lp = runCatching { cm.getLinkProperties(net) }.getOrNull() ?: continue
                cellIp = lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: ""
                cellIface = lp.interfaceName ?: ""
                break
            }

            runOnUiThread {
                tech.selected = techs.indexOf(techStr).coerceAtLeast(0)
                if (cellIface.isNotEmpty()) fields["iface"]!!.setText(cellIface)
                if (cellIp.isNotEmpty()) fields["cip"]!!.setText(cellIp)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readSsidBssid(wm: WifiManager, info: android.net.wifi.WifiInfo?): Pair<String?, String?> {
        if (info == null) return null to null
        // On API 33+ request NEARBY_WIFI_DEVICES; on 29-32 need COARSE_LOCATION; below 29 no restriction.
        val canRead = when {
            Build.VERSION.SDK_INT >= 33 ->
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            Build.VERSION.SDK_INT >= 29 ->
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            else -> true
        }
        if (!canRead) {
            // Request permissions and show note — user can try again after granting
            val perms = if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            requestPermissions(perms, REQ_WIFI_PERMS)
            return null to null
        }
        val ssidRaw = info.ssid ?: return null to null
        val ssid = if (ssidRaw == "<unknown ssid>" || ssidRaw.isBlank()) null
                   else ssidRaw.removePrefix("\"").removeSuffix("\"")
        val bssid = info.bssid?.takeIf { it != "02:00:00:00:00:00" }
        return ssid to bssid
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_WIFI_PERMS && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            fillWifi()
        }
    }

    // ---- Randomize ----------------------------------------------------------------------

    private fun randomize() {
        val ssidPrefixes = listOf("HomeNetwork", "NETGEAR", "TP-Link", "Linksys", "ASUS", "D-Link", "BT-Hub", "SKY", "Virgin", "Vodafone", "MyWifi", "Router")
        val suffix = (0x1000..0xffff).random().toString(16).uppercase()
        val ssid = "${ssidPrefixes.random()}_$suffix"

        val bssid = (1..6).joinToString(":") { (0..255).random().toString(16).padStart(2, '0') }

        val subnet = (1..254).random()
        val host = (10..200).random()
        val ip = "192.168.$subnet.$host"
        val gw = "192.168.$subnet.1"
        val dns = listOf("8.8.8.8", "1.1.1.1", "8.8.4.4", "1.0.0.1", gw).random()

        val rssi = (-72..-38).random()
        val speed = listOf(72, 150, 300, 433, 600, 867, 1200).random()
        val freq = listOf(2412, 2417, 2422, 2437, 2462, 5180, 5200, 5220, 5240, 5745, 5765, 5785).random()

        val techStr = listOf("LTE", "LTE", "NR").random() // weight towards LTE
        val mobileA = (1..172).random()
        val mobileB = (0..255).random()
        val mobileC = (0..255).random()
        val mobileIp = "$mobileA.$mobileB.$mobileC.${(2..250).random()}"
        val iface = listOf("rmnet_data0", "rmnet_data1", "rmnet0", "ccmni0", "wwan0").random()

        fields["ssid"]!!.setText(ssid)
        fields["bssid"]!!.setText(bssid)
        fields["ip"]!!.setText(ip)
        fields["prefix"]!!.setText("24")
        fields["gw"]!!.setText(gw)
        fields["dns"]!!.setText(dns)
        fields["rssi"]!!.setText(rssi.toString())
        fields["speed"]!!.setText(speed.toString())
        fields["freq"]!!.setText(freq.toString())
        fields["iface"]!!.setText(iface)
        fields["cip"]!!.setText(mobileIp)
        tech.selected = techs.indexOf(techStr).coerceAtLeast(0)
        error.visibility = View.GONE
    }

    // ---- Save ---------------------------------------------------------------------------

    private fun save() {
        val v4 = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")
        val mac = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
        val name = nameField.text.toString().trim()
        val problem = when {
            name.isEmpty() -> "Profile name can't be empty."
            text("ssid").isEmpty() || text("ssid").length > 32 -> "Network name must be 1 to 32 characters."
            !mac.matches(text("bssid")) -> "Router MAC looks like aa:bb:cc:dd:ee:ff."
            !v4.matches(text("ip")) -> "Wi-Fi IP address isn't valid."
            text("prefix").toIntOrNull() !in 8..30 -> "Prefix length must be between 8 and 30."
            !v4.matches(text("gw")) -> "Gateway isn't valid."
            !v4.matches(text("dns")) -> "DNS server isn't valid."
            text("rssi").toIntOrNull() !in -100..-20 -> "Signal must be between -100 and -20 dBm."
            text("speed").toIntOrNull() !in 1..10000 -> "Link speed must be between 1 and 10000 Mbps."
            text("freq").toIntOrNull() !in 2400..7200 -> "Frequency must be between 2400 and 7200 MHz."
            text("iface").isEmpty() -> "Mobile interface name can't be empty."
            !v4.matches(text("cip")) -> "Mobile IP address isn't valid."
            else -> null
        }
        if (problem != null) {
            error.text = problem
            error.visibility = View.VISIBLE
            return
        }
        val entry = ProfileEntry(
            name = name,
            profile = Profile(
                ssid = text("ssid"), bssid = text("bssid").lowercase(), ip = text("ip"),
                prefix = text("prefix").toInt(), gateway = text("gw"), dns = text("dns"),
                rssi = text("rssi").toInt(), linkSpeed = text("speed").toInt(),
                frequency = text("freq").toInt(), tech = techs[tech.selected.coerceAtLeast(0)],
                mobileIface = text("iface"), mobileIp = text("cip"),
            )
        )
        Store.upsertProfile(entry, entryIndex)
        // If this was a new profile, make it active
        if (entryIndex < 0) {
            val newIdx = Store.profiles().size - 1
            if (newIdx >= 0 && Store.activeProfileIndex() != -1) Store.setProfiles(Store.profiles(), newIdx)
        }
        finish()
    }

    companion object {
        const val EXTRA_INDEX = "idx"
        const val EXTRA_NAME = "name"
        const val EXTRA_PROFILE_JSON = "profile_json"
        const val EXTRA_FILL_CURRENT = "fill_current"
        private const val REQ_WIFI_PERMS = 42
    }
}
