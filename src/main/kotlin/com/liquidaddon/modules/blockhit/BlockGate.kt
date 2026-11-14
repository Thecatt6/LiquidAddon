package com.liquidaddon.modules.blockhit

/**
 * The two timings of a block hit that are easy to read wrong from the outside: when the block opens
 * after a click, and how far apart the block has to be from the actions around it.
 *
 * Both are pure counting, so both are testable: the first is the `Delay` slider (where 0 and 1 are
 * the same tick, because the tick of the click is already running), the second is the gate that
 * keeps an attack and a use from sharing a tick.
 */
internal object BlockGate {
    /**
     * How many ticks after the click the block opens.
     *
     * `Delay = 0` and `Delay = 1` are the same tick on purpose: the click is read inside a tick that
     * has already started, so the earliest a block can open is the tick after it. Sliding from 1 to 0
     * therefore changes nothing observable, and from 1 to 2 pushes the block one whole tick later.
     */
    fun startIn(delay: Int): Int = (delay - 1).coerceAtLeast(0)

    /**
     * Why a block may not open on this tick, or `null` when it may. The string is the log line the
     * module prints, so the reason is decided here and not reassembled at the call site.
     *
     * Two rules, both about keeping the actions apart:
     *
     *  - [releaseCooldown] ticks must have gone by since the last block of ours closed;
     *  - [attackGap] ticks since your own attack — and one more when the aim has not moved since it,
     *    because a swing and a raise carrying identical angles inside one tick is what a script
     *    looks like. A hand that swings and then blocks always has a different angle between the two.
     *
     * @param sinceRelease ticks since our last release, or `null` when we never blocked
     * @param sinceAttack ticks since your last attack, or `null` when you never attacked
     * @param sameRotationAsAttack whether the aim is still exactly where it was at that attack
     */
    fun reason(
        sinceRelease: Int?,
        releaseCooldown: Int,
        sinceAttack: Int?,
        attackGap: Int,
        sameRotationAsAttack: Boolean,
    ): String? {
        if (sinceRelease != null && sinceRelease < releaseCooldown.coerceAtLeast(0)) {
            return "released $sinceRelease ticks ago (cooldown $releaseCooldown)"
        }

        if (sinceAttack != null) {
            val gap = attackGap.coerceAtLeast(0)
            if (sinceAttack < gap) {
                return "your attack was $sinceAttack ticks ago (gap $attackGap)"
            }
            // The extra tick only exists for an unchanged aim, and only on the tick right after the
            // gap: `attackGap = 0` still refuses an attack and a block sharing one tick.
            if (sinceAttack < gap + 1 && sameRotationAsAttack) {
                return "same rotation as your attack"
            }
        }

        return null
    }
}
