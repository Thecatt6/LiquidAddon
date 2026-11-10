package com.liquidaddon.aiming

/**
 * The drift guard: what the engine does when the model stops agreeing with the curve on the same
 * input.
 *
 * A regression does not err noisily, it errs coherently. While the model is inside the situation it
 * was fitted on, its step and the curve's step over the same delta move together — the Pearson
 * correlation of the two traces over the last [AiStats.WINDOW] ticks stays high (this is the
 * `corr 0.71` of `/liquidaddon ai`). When the fight walks somewhere the training never saw — a
 * distance, an angle, a target speed outside the recorded cloud — the model keeps answering, but
 * its answers stop tracking the geometry of the situation: the correlation decays while the steps
 * still look confident. That is the shape of a model extrapolating, and extrapolation is where an
 * assist either walks into a wall or walks away from the target.
 *
 * The response is deliberately soft, and deliberately temporary:
 *
 *  - **halfway, not over**: below [MIN_CORR] the step becomes half model and half curve. The curve
 *    alone would slam the movement back into a geometric law for everyone watching; the model
 *    alone is the thing just caught guessing. Half keeps a human-shaped step with a geometric
 *    spine, and it converges — the curve's share of the delta still closes the angle;
 *  - **for the window's duration**: the correlation is a sliding window, so the blend lasts exactly
 *    as long as the drifted ticks are inside it. As soon as the model answers in-distribution
 *    again, fresh pairs push the correlation back up and the blend releases itself — no timer to
 *    tune, no state to reset;
 *  - **never on a young window**: with fewer pairs than a full window there is no evidence of
 *    drift yet ([AiStats.pearson] answers `NaN` below two pairs, and a fresh session must not open
 *    half-blind), so the guard stays out of the way until it has [AiStats.WINDOW] pairs to judge.
 *
 * Pure on purpose: the engine reads the correlation from [AiStats] and applies the share this file
 * returns, so the policy is testable without a client and the telemetry line shows the same number
 * the guard acted on.
 */
object AiDrift {

    /**
     * Below this correlation the model is judged to be extrapolating. Not a tight leash: in-
     * distribution play measures well above it (the two engines agree on *when* to move even where
     * they disagree on *how much*), and a healthy window oscillates with the fight. `NaN` — a dead
     * or too young trace — is never below anything.
     */
    const val MIN_CORR = 0.30f

    /** Half model, half curve: the blend below [MIN_CORR]. Never zero — see the class KDoc. */
    const val DRIFTED_MODEL_SHARE = 0.5f

    /**
     * The share of the step the model keeps this tick: `1` while the window is young, unreadable
     * (`NaN`) or agreeing (correlation at or above [MIN_CORR]); [DRIFTED_MODEL_SHARE] while it is
     * drifting. [windowFull] is the engine's promise that [AiStats.WINDOW] pairs have been
     * collected — the guard does not act on partial evidence.
     */
    fun modelShare(corr: Float, windowFull: Boolean): Float = when {
        !windowFull -> 1f
        corr.isNaN() -> 1f
        corr < MIN_CORR -> DRIFTED_MODEL_SHARE
        else -> 1f
    }
}
