package com.liquidaddon.aiming

/**
 * How fast the help is allowed to come back.
 *
 * Every gate in the engine can take the help away in a tick, and that is right: a hand stops, the
 * crosshair slips off the hitbox, the target changes, and the pull is simply gone. What no gate may
 * do is hand the help *back* in one tick. Six of them could — the end of a rest beat (nothing to
 * full), the end of an idle glide (15% to full), the crosshair coming back onto the box (a faded
 * share to full), the end of a target lock, the re-arm after a freeze, the hand starting to move
 * again — and each of those returns lands on the biggest step of the beat, because the target kept
 * moving while the help was off. That single tick is the "snap" of "snap, stop, snap".
 *
 * So the product of all the gates goes through here on its way to the step: falls pass untouched,
 * rises are limited to [risePerTick] of the full help per tick — two to six ticks from nothing to
 * everything, drawn per engagement. In steady state the limit is never reached and nothing changes.
 *
 * The tick number is what makes it hold for the gates that never get this far. Half of them answer
 * with an early return — a rest beat, a freeze, the dead zone, the reaction gate, a target lock —
 * so they leave no value behind, and comparing this tick against the last *computed* one would find
 * no rise to limit: exactly the snap this exists to remove. A gap in the tick numbers says the help
 * was nothing for at least one tick, whatever the reason, and the walk starts again from zero.
 *
 * The three counters are the measurable half: how often the limit bit, how many dips a session had,
 * and the biggest single-tick jump the gates asked for (see `SlewTest`).
 */
internal class Slew {

    private var last = 1f
    private var lastTick = -1L

    /** Ticks where a gate asked to come back faster than this engagement allows. */
    var bites = 0
        private set

    /** Times the help fell from half or more to under half: the stops a player feels. */
    var dips = 0
        private set

    /** The biggest single-tick rise the gates asked for, as a fraction of the full help. */
    var worstAsk = 0f
        private set

    /**
     * The gated help for tick [tick]: [target], or as much of the rise as one tick may take.
     * [risePerTick] of 0 or less switches the limit off.
     */
    fun apply(target: Float, risePerTick: Float, tick: Long): Float {
        if (lastTick >= 0L && tick - lastTick > 1L) last = 0f
        lastTick = tick

        val rise = target - last
        if (rise > worstAsk) worstAsk = rise
        if (last >= HALF && target < HALF) dips++
        val out = if (risePerTick > 0f && rise > risePerTick) {
            bites++
            last + risePerTick
        } else {
            target
        }
        last = out
        return out
    }

    /** Forgets the walk; the counters go with it, because they describe one session. */
    fun reset() {
        last = 1f
        lastTick = -1L
        bites = 0
        dips = 0
        worstAsk = 0f
    }

    private companion object {
        const val HALF = 0.5f
    }
}
