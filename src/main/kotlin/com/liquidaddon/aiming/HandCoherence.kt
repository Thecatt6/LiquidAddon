package com.liquidaddon.aiming


/**
 * How much of the assist's direction the hand was already going.
 *
 * An assist adds a target-directed component on top of whatever the hand does. While the hand
 * moves roughly towards the target that component reads as a person helping their own aim; while
 * the hand moves somewhere else entirely — sideways, vertically, anywhere but the target — the
 * added component comes from nowhere, and a rotation stream in which target-directed steps appear
 * on top of hand movement pointed elsewhere is exactly the stream an assist leaves behind.
 *
 * So the share of the help scales with the cosine between the hand's own turn of this tick and the
 * direction of the goal: parallel hands get all of it, perpendicular and opposed hands get [FLOOR]
 * — not zero, because a hand does drift while correcting, and zero would put a hard edge on every
 * change of direction. The hard cut for a player genuinely turning away stays where it was
 * (`turnCheck`); this is the gradient underneath it.
 *
 * When either direction is degenerate (a still hand, or a goal already reached) there is nothing
 * to compare and the factor is 1: the hand gate and the rest check own those ticks.
 */
internal object HandCoherence {

    /** The share of the help when the hand's direction says nothing in its favour. */
    const val FLOOR = 0.35f

    /**
     * The same rule one axis at a time: the assist may help an axis the hand is already moving,
     * and pays the floor for an axis it invents — a hand dragging vertically does not license a
     * horizontal pull from nowhere. [goal] of zero on an axis means nothing to follow there: the
     * factor is 1 and the caps own the tick.
     */
    fun factorAxis(own: Float, goal: Float): Float {
        if (kotlin.math.abs(goal) < 1.0e-4f) return 1f
        if (kotlin.math.abs(own) < 1.0e-4f) return FLOOR
        // Direction only, not magnitude: covering the whole error is the assist's job, and a
        // hand that moves towards the goal on this axis is participating in it however far it
        // goes. What pays the floor is an axis the hand is not on, or is fighting.
        return if (own * goal > 0f) 1f else FLOOR
    }

}
