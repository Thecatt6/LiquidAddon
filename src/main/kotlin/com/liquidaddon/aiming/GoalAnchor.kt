package com.liquidaddon.aiming

/**
 * Where, in height, an engagement's goal sits between the centre of the hitbox and the eyes.
 *
 * Anchoring the goal on the eyes alone buys nothing: Minecraft has no headshot bonus, so a goal at
 * eye height deals exactly what a goal at the chest deals. What it does buy is a *signature* —
 * every impact of every engagement landing in a narrow band high on the box (with the in-box
 * wobble, roughly the top fifth of the hitbox), a distribution no duel between people produces,
 * because people drift between chest and head with the fight.
 *
 * So the height of the base point is drawn once per engagement, uniform between the centre of the
 * box and the eyes: one engagement is pulled at the chest, the next at the collar, the next at the
 * head. Within an engagement the anchor is a constant — the goal stays a single attractor, the
 * wobble drifting around it as before — and across engagements the impacts spread over most of the
 * box, which is the distribution the tell was about.
 *
 * The draw is handed in (the engine owns the random source); the class only does the lerp, which
 * is what makes it testable (see `GoalAnchorTest`).
 */
internal object GoalAnchor {

    /**
     * The y of the base point for a draw in 0..1: 0 sits on the centre of the box, 1 on the eyes,
     * anything between on the segment. A draw outside 0..1 clamps; a box whose centre is above the
     * eyes (never in practice) still answers a point between the two.
     */
    fun anchorY(minY: Double, maxY: Double, eyeY: Double, draw: Float): Double {
        val centre = (minY + maxY) / 2.0
        return centre + (eyeY - centre) * draw.coerceIn(0f, 1f)
    }
}
