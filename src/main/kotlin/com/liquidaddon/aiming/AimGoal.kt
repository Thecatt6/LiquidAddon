package com.liquidaddon.aiming

/**
 * The one point an engagement is pulled towards, and the arithmetic that keeps it on the hitbox.
 *
 * The engine used to run two attractors at once: the model received the target's eyes while the
 * correction, the caps and the rest check worked against the nearest point on the hitbox plus a
 * random in-box offset. Two goals in the same tick mean the aim sits between them — a pull
 * towards eye height that neither setting nor training data actually asks for, on top of a
 * correction that never agrees with the model about where "there" is.
 *
 * So there is one goal now. The setting picks the *base* point (the eyes, or the nearest point on
 * the box), and the humanizing offset — the drift that stops the goal from being the same
 * coordinate every tick — is applied to whichever base was picked, clamped back inside the box.
 * Model, correction, caps and rest check all read the same rotation.
 *
 * The clamping is here, away from the engine, because it is the part with an edge worth testing:
 * an offset that leaves the hitbox aims at the air next to the enemy, and a box narrower than the
 * margin must not invert the clamp range (see `AimGoalTest`).
 */
internal object AimGoal {

    /** A target's bounding box, as the six numbers the clamp needs. */
    data class Box(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double,
    )

    /**
     * The base point plus the drawn offset, kept [margin] inside [box] on every axis. A box too
     * narrow for the margin collapses to its low edge instead of inverting the range.
     */
    fun withOffset(
        baseX: Double,
        baseY: Double,
        baseZ: Double,
        offX: Double,
        offY: Double,
        offZ: Double,
        box: Box,
        margin: Double,
    ): Triple<Double, Double, Double> = Triple(
        clampAxis(baseX + offX, box.minX, box.maxX, margin),
        clampAxis(baseY + offY, box.minY, box.maxY, margin),
        clampAxis(baseZ + offZ, box.minZ, box.maxZ, margin),
    )

    private fun clampAxis(v: Double, low: Double, high: Double, margin: Double): Double {
        val lo = low + margin
        return v.coerceIn(lo, maxOf(lo, high - margin))
    }
}
