package dev.virtualnet.hooks

import android.content.ContentResolver
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Message
import android.os.Process
import android.telephony.TelephonyManager
import android.util.Log
import dev.virtualnet.Mode
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Hooks every public way an app can ask "what am I connected to?".
 * Each hook runs the real method first, then rewrites its result according to the app's [Mode].
 * A failure in a rewrite is logged once and the real result is returned untouched.
 */
internal class NetHooks(private val m: XposedModule, private val s: State) {
    private val logged = ConcurrentHashMap.newKeySet<String>()
    private val renamed = Collections.synchronizedMap(WeakHashMap<NetworkInterface, String>())
    @Volatile private var cm: ConnectivityManager? = null
    /** A stand-in cellular Network handed out in BOTH mode when the device has no real cellular network. */
    @Volatile private var fakeCell: Network? = null

    private fun log(what: String, t: Throwable? = null) {
        if (logged.add(what)) m.log(Log.WARN, TAG, "[${s.pkg}:${Process.myPid()}] $what", t)
    }

    private fun methods(c: Class<*>?, name: String) =
        c?.declaredMethods?.filter { it.name == name && !Modifier.isAbstract(it.modifiers) }.orEmpty()

    /** Runs the original, then lets [body] replace the result. */
    private fun after(c: Class<*>?, name: String, body: (Chain, Any?, Mode) -> Any?) {
        val found = methods(c, name)
        if (found.isEmpty()) return
        for (mtd in found) {
            m.hook(mtd).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("vn-${c!!.simpleName}-$name-${mtd.parameterCount}")
                .intercept { chain ->
                    val orig = chain.proceed()
                    val mode = s.mode()
                    if (mode == Mode.OFF) orig else try {
                        body(chain, orig, mode)
                    } catch (t: Throwable) {
                        log("${c.simpleName}.$name failed", t)
                        orig
                    }
                }
        }
    }

    /** Lets [body] adjust arguments or messages before the original runs. */
    private fun before(c: Class<*>?, name: String, body: (Chain, Mode) -> Unit) {
        val found = methods(c, name)
        if (found.isEmpty()) return
        for (mtd in found) {
            m.hook(mtd).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("vn-${c!!.simpleName}-$name-${mtd.parameterCount}-pre")
                .intercept { chain ->
                    val mode = s.mode()
                    if (mode != Mode.OFF) try {
                        body(chain, mode)
                    } catch (t: Throwable) {
                        log("${c.simpleName}.$name pre failed", t)
                    }
                    chain.proceed()
                }
        }
    }

    fun install() {
        Refl.onMiss = { what, t -> log("reflection miss $what: ${t.javaClass.simpleName}") }
        val cmc = ConnectivityManager::class.java
        after(cmc, "getActiveNetworkInfo") { c, o, mode -> activeInfo(c, o, mode) }
        after(cmc, "getNetworkInfo") { c, o, mode -> networkInfo(c, o, mode) }
        after(cmc, "getAllNetworkInfo") { c, o, mode -> allInfo(c, o, mode) }
        after(cmc, "getAllNetworks") { c, o, mode -> allNetworks(c, o, mode) }
        after(cmc, "isActiveNetworkMetered") { _, _, mode -> mode == Mode.DATA }
        after(cmc, "getNetworkCapabilities") { c, o, mode -> caps(c, o, mode) }
        after(cmc, "getLinkProperties") { c, o, mode -> linkProps(c, o, mode) }
        for (n in listOf("registerNetworkCallback", "requestNetwork", "registerBestMatchingNetworkCallback")) {
            before(cmc, n) { c, mode -> loosenRequest(c, mode) }
        }
        val handler = runCatching { Class.forName("android.net.ConnectivityManager\$CallbackHandler") }.getOrNull()
        before(handler, "handleMessage") { c, mode -> rewriteCallback(c, mode) }

        val wm = WifiManager::class.java
        after(wm, "isWifiEnabled") { _, _, mode -> mode.wifi }
        after(wm, "getWifiState") { _, _, mode -> if (mode.wifi) WifiManager.WIFI_STATE_ENABLED else WifiManager.WIFI_STATE_DISABLED }
        after(wm, "getConnectionInfo") { _, _, mode -> if (mode.wifi) Shape.wifiInfo(s.profile()) else Shape.blankWifiInfo() }
        after(wm, "getScanResults") { _, o, mode -> scan(o, mode) }
        after(wm, "getDhcpInfo") { _, _, mode -> Shape.dhcp(mode.wifi, s.profile()) }

        val tm = TelephonyManager::class.java
        for (n in listOf("getNetworkType", "getDataNetworkType", "getVoiceNetworkType")) {
            after(tm, n) { _, o, mode -> if (mode.cell) s.profile().techType else o }
        }
        after(tm, "getDataState") { _, o, mode -> if (mode.cell) TelephonyManager.DATA_CONNECTED else o }
        after(tm, "isDataEnabled") { _, o, mode -> if (mode.cell) true else o }

        val global = runCatching { Class.forName("android.provider.Settings\$Global") }.getOrNull()
        after(global, "getInt") { c, o, mode -> setting(c, o, mode) }

        val io = runCatching { Class.forName("libcore.io.IoBridge") }.getOrNull()
        after(io, "getSocketLocalAddress") { _, o, mode -> localAddress(o, mode) }
        after(io, "getSocketOption") { c, o, mode -> if (c.args.getOrNull(1) == SO_BINDADDR) localAddress(o, mode) else o }

        val ni = NetworkInterface::class.java
        after(ni, "getNetworkInterfaces") { _, o, mode -> interfaces(o, mode) }
        after(ni, "getName") { c, o, _ -> renamed[c.thisObject as NetworkInterface] ?: o }
        after(ni, "getDisplayName") { c, o, _ -> renamed[c.thisObject as NetworkInterface] ?: o }
        after(ni, "getInetAddresses") { c, o, mode -> addresses(c.thisObject as NetworkInterface, o, mode) }
        after(ni, "getInterfaceAddresses") { c, o, mode -> interfaceAddresses(c.thisObject as NetworkInterface, o, mode) }
        after(ni, "getByName") { c, o, _ ->
            val want = c.args[0] as? String
            synchronized(renamed) { renamed.entries.firstOrNull { it.value == want }?.key } ?: o
        }
    }

    // ---- ConnectivityManager ------------------------------------------------------------

    private fun primaryType(mode: Mode) = if (mode.wifi) Shape.WIFI else Shape.CELL

    private fun remember(c: Chain) {
        (c.thisObject as? ConnectivityManager)?.let { cm = it }
    }

    private fun activeInfo(c: Chain, orig: Any?, mode: Mode): Any? {
        remember(c)
        val t = primaryType(mode)
        val ni = (c.thisObject as ConnectivityManager).getNetworkInfo(t) ?: Shape.newNetInfo(t) ?: return orig
        Shape.netInfo(ni, t, true, s.profile())
        return ni
    }

    private fun networkInfo(c: Chain, orig: Any?, mode: Mode): Any? {
        remember(c)
        val arg = c.args[0]
        if (arg is Int) {
            if (arg != Shape.WIFI && arg != Shape.CELL) return orig
            val ni = (orig as? NetworkInfo) ?: Shape.newNetInfo(arg) ?: return orig
            Shape.netInfo(ni, arg, if (arg == Shape.WIFI) mode.wifi else mode.cell, s.profile())
            return ni
        }
        val isFake = arg == fakeCell
        val type = if (isFake) Shape.CELL else primaryType(mode)
        val ni = (orig as? NetworkInfo) ?: (if (isFake) Shape.newNetInfo(type) else null) ?: return orig
        Shape.netInfo(ni, type, true, s.profile())
        return ni
    }

    private fun allInfo(c: Chain, orig: Any?, mode: Mode): Any? {
        remember(c)
        @Suppress("UNCHECKED_CAST")
        val all = orig as? Array<NetworkInfo?> ?: return orig
        for (ni in all) {
            when (ni?.type) {
                Shape.WIFI -> Shape.netInfo(ni, Shape.WIFI, mode.wifi, s.profile())
                Shape.CELL -> Shape.netInfo(ni, Shape.CELL, mode.cell, s.profile())
            }
        }
        return all
    }

    private fun isDefault(n: Network?): Boolean {
        val manager = cm ?: return true
        return n == null || runCatching { manager.activeNetwork == n }.getOrDefault(true)
    }

    /** In BOTH mode the default network looks like Wi-Fi and any other real cellular network stays cellular. */
    private fun kindFor(mode: Mode, nc: NetworkCapabilities, n: Network?) = when (mode) {
        Mode.WIFI -> Shape.WIFI
        Mode.DATA -> Shape.CELL
        else -> if (Shape.transport(nc, Shape.CELL) && !Shape.transport(nc, Shape.WIFI) && !isDefault(n)) Shape.CELL else Shape.WIFI
    }

    private fun caps(c: Chain, orig: Any?, mode: Mode): Any? {
        remember(c)
        if (orig == null && c.args[0] == fakeCell) {
            val fresh = Refl.new(NetworkCapabilities::class.java) as? NetworkCapabilities ?: return orig
            Shape.caps(fresh, Shape.CELL, s.profile())
            return fresh
        }
        val nc = orig as? NetworkCapabilities ?: return orig
        Shape.caps(nc, kindFor(mode, nc, c.args[0] as? Network), s.profile())
        return nc
    }

    private fun linkProps(c: Chain, orig: Any?, mode: Mode): Any? {
        remember(c)
        if (orig == null && c.args[0] == fakeCell) {
            val fresh = Refl.new(LinkProperties::class.java) as? LinkProperties ?: return orig
            Shape.link(fresh, Shape.CELL, s.profile())
            return fresh
        }
        val lp = orig as? LinkProperties ?: return orig
        val manager = c.thisObject as ConnectivityManager
        val nc = manager.getNetworkCapabilities(c.args[0] as? Network) // already shaped by our hook
        val kind = when (mode) {
            Mode.WIFI -> Shape.WIFI
            Mode.DATA -> Shape.CELL
            else -> if (nc != null && Shape.transport(nc, Shape.CELL)) Shape.CELL else Shape.WIFI
        }
        Shape.link(lp, kind, s.profile())
        return lp
    }

    /**
     * A request for TRANSPORT_WIFI (or NOT_METERED) would never match a phone that only has mobile data,
     * so drop the parts we impersonate. The matching real network is then reshaped by [rewriteCallback].
     */
    private fun loosenRequest(c: Chain, mode: Mode) {
        val req = c.args.firstOrNull { it is NetworkRequest } ?: return
        val nc = Refl.get(req, "networkCapabilities") as? NetworkCapabilities ?: return
        val k = NetworkCapabilities::class.java
        if (mode.wifi && Shape.transport(nc, Shape.WIFI)) {
            Refl.call(nc, k, "removeTransportType", Shape.WIFI)
            Refl.call(nc, k, "removeCapability", 11)
        }
        // BOTH leaves cellular requests alone so they still match a real cellular network when there is one.
        if (mode == Mode.DATA && Shape.transport(nc, Shape.CELL)) Refl.call(nc, k, "removeTransportType", Shape.CELL)
    }

    /** NetworkCallback events all pass through CallbackHandler.handleMessage with parcelables in the bundle. */
    private fun rewriteCallback(c: Chain, mode: Mode) {
        val msg = c.args[0] as? Message ?: return
        val data = msg.peekData() ?: return
        @Suppress("DEPRECATION")
        val nc = data.getParcelable<NetworkCapabilities>("NetworkCapabilities")
        @Suppress("DEPRECATION")
        val net = data.getParcelable<Network>("Network")
        if (nc != null) {
            Shape.caps(nc, kindFor(mode, nc, net), s.profile())
            data.putParcelable("NetworkCapabilities", nc)
        }
        @Suppress("DEPRECATION")
        val lp = data.getParcelable<LinkProperties>("LinkProperties")
        if (lp != null) {
            val kind = if (nc != null && Shape.transport(nc, Shape.CELL)) Shape.CELL else if (mode == Mode.DATA) Shape.CELL else Shape.WIFI
            Shape.link(lp, kind, s.profile())
            data.putParcelable("LinkProperties", lp)
        }
    }

    /** BOTH: make sure the app can enumerate a cellular network even when the device only has Wi-Fi (or the reverse). */
    private fun allNetworks(c: Chain, orig: Any?, mode: Mode): Any? {
        remember(c)
        if (mode != Mode.BOTH) return orig
        @Suppress("UNCHECKED_CAST")
        val all = orig as? Array<Network> ?: return orig
        val manager = c.thisObject as ConnectivityManager
        val hasCell = all.any { n -> manager.getNetworkCapabilities(n)?.let { Shape.transport(it, Shape.CELL) } == true }
        if (hasCell) return all
        val fake = fakeCell ?: runCatching {
            Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(FAKE_NET_ID)
        }.getOrNull()?.also { fakeCell = it } ?: return all
        return all + fake
    }

    // ---- Wi-Fi scan results -------------------------------------------------------------

    private fun scan(orig: Any?, mode: Mode): Any? {
        if (!mode.wifi) return ArrayList<ScanResult>()
        @Suppress("UNCHECKED_CAST")
        val real = (orig as? List<ScanResult>) ?: return orig
        val p = s.profile()
        if (real.any { it.BSSID.equals(p.bssid, true) }) return orig
        val r = Refl.new(ScanResult::class.java) as? ScanResult ?: return orig
        r.SSID = p.ssid
        r.BSSID = p.bssid
        r.level = p.rssi
        r.frequency = p.frequency
        r.capabilities = "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"
        r.timestamp = android.os.SystemClock.elapsedRealtime() * 1000
        return ArrayList<ScanResult>(real.size + 1).also { it.add(r); it.addAll(real) }
    }

    // ---- Sockets ------------------------------------------------------------------------

    /** The address a connected socket reports as its own, which would otherwise expose the real interface IP. */
    private fun localAddress(orig: Any?, mode: Mode): Any? {
        val a = orig as? Inet4Address ?: return orig
        if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress) return orig
        val p = s.profile()
        return Shape.v4(if (mode == Mode.DATA) p.mobileIp else p.ip) ?: orig
    }

    // ---- Settings -----------------------------------------------------------------------

    private fun setting(c: Chain, orig: Any?, mode: Mode): Any? {
        if (c.args.getOrNull(0) !is ContentResolver) return orig
        return when (c.args.getOrNull(1) as? String) {
            "wifi_on" -> if (mode.wifi) 1 else 0
            "mobile_data", "mobile_data1" -> if (mode.cell) 1 else orig
            else -> orig
        }
    }

    // ---- java.net.NetworkInterface ------------------------------------------------------

    private fun tunnel(n: String) = n.startsWith("tun") || n.startsWith("wg") || n.startsWith("ppp") || n.startsWith("tap")

    private fun isCellName(n: String) = Regex("^(v4-)?(rmnet|ccmni|pdp|wwan|ccinet|radio).*").matches(n)

    private fun isWifiName(n: String) = Regex("^(wlan|swlan|p2p|wifi|ap).*").matches(n)

    @Suppress("UNCHECKED_CAST")
    private fun interfaces(orig: Any?, mode: Mode): Any? {
        val src = orig as? java.util.Enumeration<NetworkInterface> ?: return orig
        renamed.clear()
        val all = Collections.list(src)
        fun usable(i: NetworkInterface) = runCatching {
            i.isUp && !i.isLoopback && !tunnel(i.name) &&
                Collections.list(i.inetAddresses).any { it is Inet4Address && !it.isLinkLocalAddress }
        }.getOrDefault(false)
        val usableAll = all.filter(::usable)
        val wantCell = mode == Mode.DATA
        // Prefer an interface that already has the right name (up, even without IPv4), else any usable one.
        val preferred = all.firstOrNull { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) && (if (wantCell) isCellName(it.name) else isWifiName(it.name)) }
        val real = preferred ?: usableAll.firstOrNull()
        val kind = if (mode == Mode.DATA) Shape.CELL else Shape.WIFI
        if (real != null) renamed[real] = if (kind == Shape.WIFI) "wlan0" else s.profile().mobileIface
        val kept = all.filter { i ->
            i === real || when (mode) {
                Mode.WIFI -> !isCellName(i.name)
                Mode.DATA -> !isWifiName(i.name)
                else -> true
            }
        }
        return Collections.enumeration(kept)
    }

    @Suppress("UNCHECKED_CAST")
    private fun addresses(i: NetworkInterface, orig: Any?, mode: Mode): Any? {
        val name = renamed[i] ?: return orig
        val src = orig as? java.util.Enumeration<InetAddress> ?: return orig
        val p = s.profile()
        val fake = Shape.v4(if (name == "wlan0") p.ip else p.mobileIp) ?: return orig
        val rest = Collections.list(src).filter { it !is Inet4Address }
        return Collections.enumeration(listOf<InetAddress>(fake) + rest)
    }

    @Suppress("UNCHECKED_CAST")
    private fun interfaceAddresses(i: NetworkInterface, orig: Any?, mode: Mode): Any? {
        val name = renamed[i] ?: return orig
        val src = orig as? List<InterfaceAddress> ?: return orig
        val p = s.profile()
        val wifi = name == "wlan0"
        val fake = Shape.v4(if (wifi) p.ip else p.mobileIp) ?: return orig
        val entry = runCatching {
            val k = InterfaceAddress::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            Refl.set(k, "address", fake)
            Refl.set(k, "maskLength", (if (wifi) p.prefix else 30).toShort())
            k
        }.getOrNull() ?: return orig
        return listOf(entry) + src.filter { it.address !is Inet4Address }
    }

    companion object {
        private const val TAG = "VirtualNet"
        private const val SO_BINDADDR = 0x0F
        private const val FAKE_NET_ID = 9998
    }
}
