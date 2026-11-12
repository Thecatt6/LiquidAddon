package com.liquidaddon.aiming

/**
 * One uniform draw, turned into a multiplier inside a span — the smallest piece of "every
 * engagement is its own": the lock that refuses an early target switch and the amplitude of the
 * goal wobble both used to be constants of the profile, and a constant repeated over hundreds of
 * engagements is a floor a check can sit on.
 *
 * [span] scales a base by `min + draw * (max - min)`, clamped: the setting keeps meaning what it
 * says (the base), while no two engagements share the exact same value. Tested in `DrawnTest`.
 */
internal object Drawn {

    fun span(base: Float, draw: Float, min: Float, max: Float): Float =
        base * (min + (max - min) * draw.coerceIn(0f, 1f))
}
