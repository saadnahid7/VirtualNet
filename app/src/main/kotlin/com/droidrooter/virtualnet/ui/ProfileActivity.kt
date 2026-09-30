package com.droidrooter.virtualnet.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.droidrooter.virtualnet.R
import com.droidrooter.virtualnet.config.Profile
import com.droidrooter.virtualnet.config.Store

/** Edits the one identity every spoofed app sees. Values are validated so the profile stays coherent. */
class ProfileActivity : Activity() {
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var tech: Segmented
    private lateinit var error: TextView
    private val techs = listOf("LTE", "NR", "HSPA+")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        applyInsets(root)
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(12), dp(16), dp(4)) }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(color(R.color.vn_text))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(label("Network profile", 20f, medium = true))
        root.addView(bar)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        val p = Store.profile()

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
        body.addView(cell, lp(top = 12))

        error = label("", 13f, R.color.vn_warn, true).apply { visibility = View.GONE }
        body.addView(error, lp(top = 12))

        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        actions.addView(button("Reset", false) { fill(Profile()) })
        actions.addView(button("Save", true) { save() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        body.addView(actions, lp(top = 16))

        root.addView(ScrollView(this).apply { addView(body); isFillViewport = true }, lp(h = 0, weight = 1f))
        setContentView(root)
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
            inputType = if (number) InputType.TYPE_CLASS_NUMBER or (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
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

    private fun save() {
        val v4 = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")
        val mac = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
        val problem = when {
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
        Store.setProfile(
            Profile(
                ssid = text("ssid"), bssid = text("bssid").lowercase(), ip = text("ip"), prefix = text("prefix").toInt(),
                gateway = text("gw"), dns = text("dns"), rssi = text("rssi").toInt(), linkSpeed = text("speed").toInt(),
                frequency = text("freq").toInt(), tech = techs[tech.selected.coerceAtLeast(0)],
                mobileIface = text("iface"), mobileIp = text("cip"),
            )
        )
        finish()
    }
}
