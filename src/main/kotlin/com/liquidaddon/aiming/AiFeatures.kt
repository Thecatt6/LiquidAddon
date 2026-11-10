package com.liquidaddon.aiming

import kotlin.math.hypot

/**
 * The model's input, written down once — the six numbers `CombatSample.fillAsInput` (LiquidBounce
 * v0.41.0) hands to every bundled combat model, in the order the training used:
 *
 * ```
 * [0] deltaYaw   (current -> target, wrapped)     [3] deltaPitch (previous -> current)
 * [1] deltaPitch (current -> target, wrapped)     [4] speed: |playerDiff|xz + |targetDiff|xz
 * [2] deltaYaw   (previous -> current, wrapped)   [5] squared distance to the target, in blocks²
 * ```
 *
 * Pure on purpose, and the mirror of `configs/ai_eval.py`: the offline evaluation fits on exactly
 * these features, so what the evaluation measures and what the engine feeds the model cannot drift
 * apart unnoticed — a change of order breaks [AiFeaturesTest] and the evaluation together.
 *
 * The engine does not re-implement the forward input with this (LiquidBounce's `CombatSample`
 * stays the builder); it uses [usable] as the guard that keeps a corrupt vector — a NaN out of a
 * degenerate rotation, an Inf out of a distance — from ever reaching `predict`, and the tests pin
 * the order this KDoc documents.
 */
object AiFeatures {

    /** Bundled combat models take six floats (`CombatSample.INPUT_SIZE`). */
    const val INPUT_SIZE = 6

    /** Degrees to `(-180, 180]`, what `Mth.wrapDegrees` / `Rotation.angleDifference` do. */
    fun wrap(degrees: Float): Float = ((degrees + 180f) % 360f + 360f) % 360f - 180f

    /**
     * The six features, from the three rotations of the tick (current, previous, target), the two
     * horizontal movement diffs and the squared distance — or `null` when any of them is not a
     * finite number, because a model asked about a corrupt situation answers with a corrupt step.
     */
    fun features(
        currentYaw: Float,
        currentPitch: Float,
        previousYaw: Float,
        previousPitch: Float,
        targetYaw: Float,
        targetPitch: Float,
        playerDiffX: Float,
        playerDiffZ: Float,
        targetDiffX: Float,
        targetDiffZ: Float,
        squaredDistance: Float,
    ): FloatArray? {
        val out = floatArrayOf(
            wrap(targetYaw - currentYaw),
            wrap(targetPitch - currentPitch),
            wrap(currentYaw - previousYaw),
            wrap(currentPitch - previousPitch),
            hypot(playerDiffX, playerDiffZ) + hypot(targetDiffX, targetDiffZ),
            squaredDistance,
        )
        return if (usable(out)) out else null
    }

    /** True when [input] is something a forward pass may be asked about: six slots, all finite. */
    fun usable(input: FloatArray): Boolean =
        input.size >= INPUT_SIZE && input.all { it.isFinite() }
}
