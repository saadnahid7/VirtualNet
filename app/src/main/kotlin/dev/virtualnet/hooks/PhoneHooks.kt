package dev.virtualnet.hooks

import android.os.Binder
import android.os.Process
import android.util.Log
import dev.virtualnet.Mode
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs inside com.android.phone, which answers TelephonyManager for every app. Gated by the caller's uid,
 * so apps that only have a mode set (and are not scoped) still see the right mobile-data state.
 */
internal class PhoneHooks(private val m: XposedModule, private val st: SysState) {
    private val logged = ConcurrentHashMap.newKeySet<String>()

    private fun log(what: String, t: Throwable? = null) {
        if (logged.add(what)) m.log(Log.WARN, TAG, "[phone:${Process.myPid()}] $what", t)
    }

    fun install(loader: ClassLoader) {
        val pim = runCatching { loader.loadClass("com.android.phone.PhoneInterfaceManager") }.getOrNull()
            ?: return log("PhoneInterfaceManager not found")
        val types = listOf(
            "getNetworkTypeForSubscriber", "getDataNetworkTypeForSubscriber", "getVoiceNetworkTypeForSubscriber",
            "getNetworkType", "getDataNetworkType",
        )
        val flags = listOf("isDataEnabled", "isUserDataEnabled", "isDataConnectivityPossible", "isDataEnabledForReason")
        val states = listOf("getDataState", "getDataStateForSubId")
        var count = 0
        for (mtd in pim.declaredMethods) {
            if (Modifier.isAbstract(mtd.modifiers)) continue
            val n = mtd.name
            val pick: ((Mode) -> Any?)? = when {
                n in types && mtd.returnType == Int::class.javaPrimitiveType -> { _ -> st.profile().techType }
                n in states && mtd.returnType == Int::class.javaPrimitiveType -> { _ -> 2 }
                n in flags && mtd.returnType == Boolean::class.javaPrimitiveType -> { _ -> true }
                else -> null
            }
            if (pick == null) continue
            m.hook(mtd).setExceptionMode(ExceptionMode.PROTECTIVE).setId("vn-phone-$n-${mtd.parameterCount}")
                .intercept { chain ->
                    val orig = chain.proceed()
                    try {
                        val mode = st.mode(Binder.getCallingUid())
                        if (mode.cell) pick(mode) else orig
                    } catch (t: Throwable) {
                        log("$n failed", t)
                        orig
                    }
                }
            count++
        }
        m.log(Log.INFO, TAG, "[phone:${Process.myPid()}] hooked $count telephony methods")
    }

    companion object {
        private const val TAG = "VirtualNet"
    }
}
