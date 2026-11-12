package com.liquidaddon.aiming

import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * The one door silent rotations go through, bound to the loaded client at first use.
 *
 * A call site compiled against LiquidBounce 0.42 asks for
 * `setRotationTarget(RotationTarget, Priority, EventListener)`; a 0.41 client only has the same
 * method with `ClientModule` in that slot, and the JVM answers `NoSuchMethodError` — the aim
 * assist crashes the tick it first tries to hand a rotation over. [RotationBinding] picks the
 * overload the running client actually has, so one jar drives 0.40, 0.41, 0.42 and nextgen.
 *
 * The binding is resolved once and cached: a tick pays one `Method.invoke`, which is noise next to
 * the work around it. If no overload fits, [setRotationTarget] returns false and says why in
 * [state] — the caller has to report that, because an assist that cannot reach the client is an
 * assist that does nothing.
 */
internal object RotationBridge {

    private var method: Method? = null
    private var receiver: Any? = null
    private var intPriority = false
    private var attempted = false

    /** An attempt to bind or to call failed: silent rotations are not reaching the client. */
    var failed = false
        private set

    /** What happened, in one line, for the warning the engine prints. */
    var state = "not attempted"
        private set

    /**
     * Hands [plan] to the client's rotation manager at [priority], on behalf of [provider] (the
     * module that owns this aim). True when the request was made.
     */
    fun setRotationTarget(plan: RotationTarget, priority: Priority, provider: Any): Boolean {
        val bound = method ?: bind(provider.javaClass) ?: return false
        return try {
            bound.invoke(receiver, plan, if (intPriority) priority.priority else priority, provider)
            failed = false
            true
        } catch (e: InvocationTargetException) {
            // Whatever the client threw inside its own manager is not ours to swallow: a direct
            // call would have crashed the same way, and hiding it would hide a real bug.
            throw e.cause ?: e
        } catch (e: IllegalArgumentException) {
            fail("the bound door does not take a ${provider.javaClass.simpleName}")
            false
        } catch (e: ReflectiveOperationException) {
            fail("the call itself threw: ${e.cause ?: e}")
            false
        }
    }

    private fun bind(providerClass: Class<*>): Method? {
        if (attempted) return null
        attempted = true
        return try {
            val manager = Class.forName(RotationBinding.MANAGER)
            val pick = RotationBinding.pick(manager, RotationBinding.PLAN, RotationBinding.PRIORITY, providerClass)
            if (pick == null) {
                fail("no ${RotationBinding.METHOD}(RotationTarget, Priority, ${providerClass.simpleName}) in this client build")
                return null
            }
            method = pick.method
            intPriority = pick.intPriority
            // A Kotlin `object` keeps its single instance in a static field; a static method needs
            // no receiver at all.
            receiver = manager.getField("INSTANCE").get(null)
            state = "bound to (${pick.method.parameterTypes.joinToString(", ") { it.simpleName }})" +
                if (pick.intPriority) " with the raw priority int" else ""
            pick.method
        } catch (e: Throwable) {
            fail("${RotationBinding.MANAGER} is not usable here: $e")
            null
        }
    }

    private fun fail(reason: String) {
        failed = true
        state = reason
    }
}
