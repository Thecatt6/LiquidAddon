package com.liquidaddon.aiming

/**
 * How much of the assist a *fast* target still gets, as a function of how fast the target is
 * turning against the cap of a single tick.
 *
 * Without this the assist has two regimes and a cliff between them: while the turn the target
 * demands stays under the cap (`MaxSpeed`), the loop closes the error every tick and the target
 * practically cannot leave the crosshair; the moment the demand passes the cap, every tick loses
 * ground and the target walks out. A hand does not have that cliff — a tracking hand falls
 * progressively further behind as the target speeds up, well before it saturates. The probability
 * of losing the target should grow with the target's angular speed, not stay flat and then jump.
 *
 * So the share of the help eases down smoothly between [KNEE] and a full load: at sixty percent of
 * the cap nothing changes, at the cap the assist grants [FLOOR] of what it would grant a slow
 * target, and past the cap the cap itself still rules (the share stays at the floor). The curve is
 * a smoothstep, flat at both ends: no new edge is introduced where the easing begins.
 *
 * The two constants are a design choice, not a measurement: they place the knee where a tracking
 * hand still looks unhurried and the floor where the assist still helps a strafing target without
 * gluing to it. The effect on any anticheat is **not measured** — what is measured is the shape
 * itself, in `ChaseLoadTest`.
 */
internal object ChaseLoad {

    /** Fraction of the cap below which the assist is unhindered. */
    const val KNEE = 0.6f

    /** Fraction of the help still granted at (and past) a full load. */
    const val FLOOR = 0.55f

    /**
     * The share of the help for a load of [load] = demanded turn per tick / cap per tick, 0..1 and
     * beyond. Negative or NaN loads (a delta that came out of nowhere) read as no load at all.
     */
    fun loadShare(load: Float): Float {
        if (load.isNaN() || load <= KNEE) return 1f
        val t = ((load - KNEE) / (1f - KNEE)).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return 1f - (1f - FLOOR) * s
    }
}
