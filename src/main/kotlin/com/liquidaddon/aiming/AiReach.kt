package com.liquidaddon.aiming

import kotlin.math.min

/**
 * How much of the model's answer to use, against how far the situation is from its training.
 *
 * A regression answers inside the distribution it was fitted on and keeps answering outside it. The
 * numbers it was trained with say where "inside" is: LiquidBounce recorded `CombatTrainer` slimes at
 * two to three blocks with the crosshair on them, and `Combat` fights inside ten, with a sample
 * written only while the crosshair was closing on the target. A 40° swing at eight blocks is
 * therefore not a hard case for the model, it is a case it has never seen — and a confident wrong
 * answer is worse than the curve, which at least has a known shape.
 *
 * So the answer is weighted by distance and by angle of error: full inside, nothing past the end of
 * the ramp, linear in between. Two independent ramps rather than one product of both, because a
 * close target behind a wide swing and a far target with a small error are two different failures.
 *
 * The class has no dependencies, so the whole rule is testable without a game (see `AiReachTest`).
 */
internal object AiReach {

    /** Everything up to here was in the data: two to six blocks, the range the profiles fight in. */
    const val FULL_BLOCKS = 6f

    /** Past here the model is guessing; the range slider cannot even hold a target that far. */
    const val NONE_BLOCKS = 8f

    /** A correction is a degree or two; thirty is a swing the hand does in one tick. */
    const val FULL_DEGREES = 30f

    /** Past this the aim is not being "helped", it is being pointed somewhere else. */
    const val NONE_DEGREES = 45f

    /**
     * The share of the model's answer to take this tick, 0..1: [blocks] is the distance to the
     * target in blocks, [degrees] the size of the correction it was asked for.
     *
     * A NaN is not a range: it is a number the loop could not compute, and the model must not be
     * asked to answer for it.
     */
    fun blend(blocks: Float, degrees: Float): Float {
        val reach = min(ramp(blocks, FULL_BLOCKS, NONE_BLOCKS), ramp(degrees, FULL_DEGREES, NONE_DEGREES))
        if (reach.isNaN()) return 0f
        return reach.coerceIn(0f, 1f)
    }

    /** 1 up to [full], 0 from [none] on, linear in between. */
    private fun ramp(value: Float, full: Float, none: Float): Float = when {
        value.isNaN() -> 0f
        value <= full -> 1f
        value >= none -> 0f
        else -> 1f - (value - full) / (none - full)
    }
}
