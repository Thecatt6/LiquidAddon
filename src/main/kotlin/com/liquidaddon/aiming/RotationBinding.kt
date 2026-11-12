package com.liquidaddon.aiming

import java.lang.reflect.Method

/**
 * Which `RotationManager.setRotationTarget` a client build actually has, picked by name.
 *
 * LiquidBounce 0.40 and 0.41 declare the provider of a rotation request as `ClientModule`; 0.42
 * and nextgen widened it to `EventListener`. The instance an add-on passes is a module either way
 * (`ClientModule` implements `EventListener` in both), but the JVM matches a call site against the
 * *descriptor* it was compiled with, so a jar built against one throws `NoSuchMethodError` on the
 * other — the whole silent aim dies on the first tick, in a client that is otherwise fine.
 *
 * So the call is bound at runtime instead: [pick] reads the overloads the loaded client really has
 * and chooses the one this add-on can call, preferring the `Priority` overload it was written
 * against and falling back to the `@AddonApi` one that takes the raw priority int.
 *
 * Pure: JDK reflection over names, no client class anywhere in this file, so the choice is testable
 * with fakes standing in for either build (see `RotationBindingTest`).
 */
internal object RotationBinding {

    const val MANAGER = "net.ccbluex.liquidbounce.utils.aiming.RotationManager"
    const val METHOD = "setRotationTarget"
    const val PLAN = "net.ccbluex.liquidbounce.utils.aiming.RotationTarget"
    const val PRIORITY = "net.ccbluex.liquidbounce.utils.kotlin.Priority"
    const val INT = "int"

    /** The overload to call, and whether it wants [Priority]'s raw int instead of the enum. */
    class Pick(val method: Method, val intPriority: Boolean)

    /**
     * The overload on [owner] named [METHOD] that takes ([planType], [priorityType] or int,
     * something [provider] is), or `null` when the client has none — a build this add-on cannot
     * drive, which the caller has to say out loud instead of failing silently.
     */
    fun pick(
        owner: Class<*>,
        planType: String,
        priorityType: String,
        provider: Class<*>,
    ): Pick? {
        val overloads = owner.methods.filter { it.name == METHOD }
        val supers = supertypeNames(provider)
        return matching(overloads, planType, priorityType, supers)?.let { Pick(it, false) }
            ?: matching(overloads, planType, INT, supers)?.let { Pick(it, true) }
    }

    private fun matching(
        overloads: List<Method>,
        planType: String,
        priorityType: String,
        supers: Set<String>,
    ): Method? = overloads.firstOrNull { m ->
        val p = m.parameterTypes
        p.size == 3 && p[0].name == planType && p[1].name == priorityType && p[2].name in supers
    }

    /** The names of [cls] and everything above it, superclasses and interfaces, transitively. */
    fun supertypeNames(cls: Class<*>): Set<String> {
        val names = LinkedHashSet<String>()
        val queue = ArrayDeque<Class<*>>()
        queue.add(cls)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!names.add(current.name)) continue
            current.superclass?.let { queue.add(it) }
            queue.addAll(current.interfaces)
        }
        return names
    }
}
