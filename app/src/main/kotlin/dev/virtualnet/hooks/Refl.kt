package dev.virtualnet.hooks

import java.lang.reflect.Field
import java.lang.reflect.Method

/** Tolerant reflection: hidden members differ across Android 9-17, so every access may miss. */
internal object Refl {
    /** Set by the hook layer so a missing hidden member is reported once instead of failing silently. */
    @Volatile var onMiss: ((String, Throwable) -> Unit)? = null

    private fun classes(c: Class<*>) = generateSequence(c) { it.superclass }

    fun field(c: Class<*>, name: String): Field? = classes(c).firstNotNullOfOrNull {
        runCatching { it.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
    }

    fun set(o: Any, name: String, value: Any?): Boolean =
        runCatching { field(o.javaClass, name)!!.set(o, value) }.isSuccess

    fun get(o: Any, name: String): Any? = runCatching { field(o.javaClass, name)!!.get(o) }.getOrNull()

    /** Invokes the first same-named method whose arity matches and that accepts [args]. */
    fun call(o: Any?, cls: Class<*>, name: String, vararg args: Any?): Result<Any?> = invoke(o, cls, name, true, *args)

    /** Same as [call] for members that legitimately do not exist on some releases. */
    fun tryCall(o: Any?, cls: Class<*>, name: String, vararg args: Any?): Result<Any?> = invoke(o, cls, name, false, *args)

    private fun invoke(o: Any?, cls: Class<*>, name: String, report: Boolean, vararg args: Any?): Result<Any?> {
        var last: Throwable = NoSuchMethodException("$name/${args.size}")
        for (k in classes(cls)) for (m: Method in k.declaredMethods) {
            if (m.name != name || m.parameterCount != args.size) continue
            try {
                m.isAccessible = true
                return Result.success(m.invoke(o, *args))
            } catch (t: IllegalArgumentException) {
                last = t
            } catch (t: java.lang.reflect.InvocationTargetException) {
                last = t.cause ?: t
            } catch (t: Throwable) {
                last = t
            }
        }
        if (report) onMiss?.invoke("${cls.simpleName}.$name/${args.size} ${last.message}", last)
        return Result.failure(last)
    }

    fun call(o: Any, name: String, vararg args: Any?) = call(o, o.javaClass, name, *args)

    fun new(cls: Class<*>, vararg args: Any?): Any? = runCatching {
        val k = cls.declaredConstructors.first { it.parameterCount == args.size }
        k.isAccessible = true
        k.newInstance(*args)
    }.getOrNull()
}
