package dev.virtualnet.hooks

import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Binder
import android.os.Process
import android.util.Log
import dev.virtualnet.Mode
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * System-side hooks. They run inside system_server, so every app is covered without being scoped, and
 * the answer is rewritten only for callers whose uid has a mode. Anything that fails is logged once and
 * the real answer goes through, because a fault here would affect the whole device.
 */
internal class SystemHooks(private val m: XposedModule, private val st: SysState) {
    private val logged = ConcurrentHashMap.newKeySet<String>()
    private val hooked = ConcurrentHashMap.newKeySet<Class<*>>()
    @Volatile private var fakeCell: Network? = null

    private fun log(what: String, t: Throwable? = null) {
        if (logged.add(what)) m.log(Log.WARN, TAG, "[system:${Process.myPid()}] $what", t)
    }

    private fun info(what: String) = m.log(Log.INFO, TAG, "[system:${Process.myPid()}] $what")

    fun install(loader: ClassLoader? = null) {
        Refl.onMiss = { what, t -> log("reflection miss $what: ${t.javaClass.simpleName}") }
        val sm = runCatching { Class.forName("android.os.ServiceManager") }.getOrNull() ?: return log("no ServiceManager")
        // The service instance itself is the most reliable handle: its class is found whatever the
        // module, package relocation or Android version, and the hooks attach before any app calls it.
        val onPublish = { chain: Chain ->
            try {
                val name = chain.args.getOrNull(0) as? String
                val binder = chain.args.getOrNull(1)
                if (binder != null) when (name) {
                    "connectivity" -> hookConnectivity(binder.javaClass)
                    "wifi" -> hookWifi(binder.javaClass)
                }
            } catch (t: Throwable) {
                log("publish hook failed", t)
            }
            chain.proceed()
        }
        val entry = ArrayList<java.lang.reflect.Method>()
        entry += sm.declaredMethods.filter { it.name == "addService" && Modifier.isStatic(it.modifiers) }
        // Some builds inline ServiceManager.addService into its caller, so also watch SystemService itself.
        val ss = runCatching { (loader ?: sm.classLoader).loadClass("com.android.server.SystemService") }.getOrNull()
        if (ss != null) entry += ss.declaredMethods.filter { it.name == "publishBinderService" }
        for (mtd in entry) {
            m.hook(mtd).setExceptionMode(ExceptionMode.PROTECTIVE).setId("vn-sys-publish-${mtd.declaringClass.simpleName}-${mtd.parameterCount}")
                .intercept(onPublish)
            runCatching { m.deoptimize(mtd) }
        }
        info("watching ${entry.size} publish points")
        info("waiting for connectivity and wifi services")
    }

    private fun methods(c: Class<*>, name: String) =
        generateSequence(c) { it.superclass }.takeWhile { it != Any::class.java && it != android.os.Binder::class.java }
            .flatMap { it.declaredMethods.asSequence() }.filter { it.name == name && !Modifier.isAbstract(it.modifiers) }.toList()

    /** Runs the original, then lets [body] replace the result. [uidOf] tells which app is asking. */
    private fun after(c: Class<*>, name: String, uidOf: (Chain) -> Int = { Binder.getCallingUid() }, body: (Chain, Any?, Mode, Int) -> Any?) {
        val found = methods(c, name)
        if (found.isEmpty()) return log("system: ${c.simpleName}.$name not present")
        for (mtd in found) {
            m.hook(mtd).setExceptionMode(ExceptionMode.PROTECTIVE).setId("vn-sys-${c.simpleName}-$name-${mtd.parameterCount}")
                .intercept { chain ->
                    val orig = chain.proceed()
                    try {
                        val uid = uidOf(chain)
                        val mode = st.mode(uid)
                        if (mode == Mode.OFF) orig else body(chain, orig, mode, uid)
                    } catch (t: Throwable) {
                        log("system: ${c.simpleName}.$name failed", t)
                        orig
                    }
                }
        }
        info("hooked ${c.simpleName}.$name x${found.size}")
    }

    // ---- ConnectivityService ------------------------------------------------------------

    private fun hookConnectivity(cs: Class<*>) {
        if (!hooked.add(cs)) return
        info("connectivity service: ${cs.name}")
        info("caps candidates: " + cs.declaredMethods.filter { it.returnType == NetworkCapabilities::class.java }.joinToString { it.name + "/" + it.parameterCount + (if (Modifier.isStatic(it.modifiers)) "s" else "") })
        // The caller uid is the last Int argument in every variant of these methods.
        val argUid = { c: Chain -> c.args.filterIsInstance<Int>().last() }

        // One chokepoint for direct queries and every callback delivery. Its name changed across
        // releases: Android 17 calls it createWithSensitiveInfoSanitizedIfNecessaryWhenParceled.
        val capsMethod = listOf(
            "networkCapabilitiesRestrictedForCallerPermissions",
            "createWithSensitiveInfoSanitizedIfNecessaryWhenParceled",
        ).firstOrNull { name -> methods(cs, name).any { it.returnType == NetworkCapabilities::class.java } }
        if (capsMethod != null) {
            after(cs, capsMethod, argUid) { _, o, mode, _ ->
                (o as? NetworkCapabilities)?.let { copyNc(it) }?.also { Shape.caps(it, kindFor(mode), st.profile()) } ?: o
            }
        }
        after(cs, "linkPropertiesRestrictedForCallerPermissions", argUid) { _, o, mode, _ ->
            (o as? LinkProperties)?.also { Shape.link(it, kindFor(mode), st.profile()) } ?: o
        }
        // Without a chokepoint the binder entry points are the fallback.
        if (capsMethod == null) {
            after(cs, "getNetworkCapabilities") { c, o, mode, _ -> capsFallback(c, o, mode) }
            after(cs, "getLinkProperties") { c, o, mode, _ ->
                (o as? LinkProperties)?.also { Shape.link(it, kindFor(mode), st.profile()) } ?: o
            }
        }

        after(cs, "getActiveNetworkInfo") { _, o, mode, _ -> shapeInfo(o as? NetworkInfo, kindFor(mode), true) }
        after(cs, "getActiveNetworkInfoForUid", { c -> c.args[0] as Int }) { _, o, mode, _ -> shapeInfo(o as? NetworkInfo, kindFor(mode), true) }
        after(cs, "getNetworkInfo") { c, o, mode, _ ->
            val t = c.args[0] as? Int ?: return@after o
            if (t != Shape.WIFI && t != Shape.CELL) return@after o
            shapeInfo(o as? NetworkInfo, t, if (t == Shape.WIFI) mode.wifi else mode.cell, t)
        }
        after(cs, "getNetworkInfoForUid", { c -> c.args[1] as Int }) { c, o, mode, _ ->
            val fake = isFake(c.args[0] as? Network)
            val t = if (fake) Shape.CELL else kindFor(mode)
            shapeInfo(o as? NetworkInfo, t, true, t)
        }
        after(cs, "getAllNetworkInfo") { _, o, mode, _ ->
            @Suppress("UNCHECKED_CAST")
            (o as? Array<NetworkInfo?>)?.also { all ->
                for (ni in all) when (ni?.type) {
                    Shape.WIFI -> Shape.netInfo(ni, Shape.WIFI, mode.wifi, st.profile())
                    Shape.CELL -> Shape.netInfo(ni, Shape.CELL, mode.cell, st.profile())
                }
            } ?: o
        }
        after(cs, "isActiveNetworkMetered") { _, _, mode, _ -> mode == Mode.DATA }
        after(cs, "getAllNetworks") { _, o, mode, _ -> allNetworks(o, mode) }
        // Only reached for the stand-in cellular network, which the real service does not know.
        after(cs, "getNetworkCapabilities") { c, o, _, _ ->
            if (o == null && isFake(c.args[0] as? Network)) {
                (Refl.new(NetworkCapabilities::class.java) as? NetworkCapabilities)?.also { Shape.caps(it, Shape.CELL, st.profile()) }
            } else o
        }
        after(cs, "getLinkProperties") { c, o, _, _ ->
            if (o == null && isFake(c.args[0] as? Network)) {
                (Refl.new(LinkProperties::class.java) as? LinkProperties)?.also { Shape.link(it, Shape.CELL, st.profile()) }
            } else o
        }
    }

    private fun kindFor(mode: Mode) = if (mode == Mode.DATA) Shape.CELL else Shape.WIFI

    private fun capsFallback(c: Chain, o: Any?, mode: Mode): Any? {
        if (o == null && isFake(c.args[0] as? Network)) {
            return (Refl.new(NetworkCapabilities::class.java) as? NetworkCapabilities)?.also { Shape.caps(it, Shape.CELL, st.profile()) }
        }
        return (o as? NetworkCapabilities)?.let { copyNc(it) }?.also { Shape.caps(it, kindFor(mode), st.profile()) } ?: o
    }

    /** Never edit the service's own object: it may be the live capabilities of a network. */
    private fun copyNc(nc: NetworkCapabilities): NetworkCapabilities =
        Refl.new(NetworkCapabilities::class.java, nc) as? NetworkCapabilities ?: nc

    private fun isFake(n: Network?) = n != null && n.toString() == FAKE_NET_ID.toString()

    private fun shapeInfo(orig: NetworkInfo?, type: Int, connected: Boolean, wanted: Int = type): NetworkInfo? {
        val ni = orig ?: Shape.newNetInfo(wanted) ?: return orig
        Shape.netInfo(ni, wanted, connected, st.profile())
        return ni
    }

    private fun allNetworks(orig: Any?, mode: Mode): Any? {
        if (mode != Mode.BOTH) return orig
        @Suppress("UNCHECKED_CAST")
        val all = orig as? Array<Network> ?: return orig
        if (all.any { isFake(it) }) return all
        val fake = fakeCell ?: runCatching {
            Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(FAKE_NET_ID)
        }.getOrNull()?.also { fakeCell = it } ?: return all
        return all + fake
    }

    // ---- WifiService --------------------------------------------------------------------

    private fun hookWifi(wifi: Class<*>) {
        if (!hooked.add(wifi)) return
        info("wifi service: ${wifi.name}")
        after(wifi, "getConnectionInfo") { _, o, mode, _ ->
            if (mode.wifi) Shape.wifiInfo(st.profile()) ?: o else Shape.blankWifiInfo() ?: o
        }
        after(wifi, "getDhcpInfo") { _, _, mode, _ -> Shape.dhcp(mode.wifi, st.profile()) }
        after(wifi, "getWifiEnabledState") { _, _, mode, _ -> if (mode.wifi) 3 else 1 }
        after(wifi, "isWifiEnabled") { _, _, mode, _ -> mode.wifi }
    }

    companion object {
        private const val TAG = "VirtualNet"
        const val FAKE_NET_ID = 9998
    }
}
