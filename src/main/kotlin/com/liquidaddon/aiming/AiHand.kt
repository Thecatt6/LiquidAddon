package com.liquidaddon.aiming

/**
 * What is left of the hand layer when the model is the author.
 *
 * The lattice wobble — [AimSettings.mouseNoise], the extra count of [AimSettings.overshoot], the
 * per-target spread of [AimSettings.strengthSpread] — was written to humanise a *curve*: a
 * deterministic law whose steps come out of one formula and need a hand painted on top. A model
 * trained on recorded human ticks does not need that paint: its answer already carries the wobble,
 * the sloppy counts and the varied pull of the hand it learned from, and the layer on top does not
 * add humanity — it bends an authored step into a different one (and the telemetry of
 * `/liquidaddon ai` keeps measuring the step *before* the bend, so the line would lie).
 *
 * So with `AIHand = On` and `Engine = AI`:
 *
 *  - [AimSettings.mouseNoise] is capped at one count — the axis is never dead silent on the wire,
 *    but the model's own texture is no longer buried under up to four counts of added noise;
 *  - [AimSettings.overshoot] goes to zero — the lattice stops throwing the extra count, because a
 *    model overshoots on its own when the hand it learned from did (the AI branch's clamp still
 *    *allows* that overshoot up to the configured percent; what is removed is the invented one);
 *  - [AimSettings.strengthSpread] goes to zero — the pull is not the model's knob; spreading it per
 *    target only varies the trust and correction terms around an answer that was not built for them.
 *
 * What stays, because it is the floor and not the paint: the GCD quantisation and its carried
 * residual, the no-repeat rule of [MousePatternGuard], the hand gate ([AimSettings.handOnly] and
 * friends) and the undershoot that lands a step short now and then.
 *
 * With `Engine = MATH` — Profile A, the curve — nothing changes at all: the layer is exactly what
 * it was, because the curve is exactly what needs it.
 */
object AiHand {

    /** The three dials of the hand layer, as this tick's lattice should read them. */
    data class Layer(val mouseNoise: Int, val overshoot: Int, val strengthSpread: Int)

    /**
     * The effective layer. A `mouseNoise` the player set to zero stays zero — capping at one
     * never *adds* wobble somebody deliberately removed; it only takes away what would bury
     * the model's own.
     */
    fun layer(
        aiHandOn: Boolean,
        engineAi: Boolean,
        mouseNoise: Int,
        overshoot: Int,
        strengthSpread: Int,
    ): Layer =
        if (aiHandOn && engineAi) {
            Layer(mouseNoise.coerceAtMost(1), 0, 0)
        } else {
            Layer(mouseNoise, overshoot, strengthSpread)
        }
}
