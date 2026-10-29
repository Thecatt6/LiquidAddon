package com.liquidaddon.modules.jumpreset

/**
 * The text `JumpReset` shows next to its name in the HUD.
 *
 * The module has two numbers that look alike and mean different things, so the HUD shows the one a
 * player tunes and the one a player earns, side by side:
 *
 *  - **the probability** ([chance], in percent): how often a hit that qualifies is answered with a
 *    jump at all. It is the `Chance` slider, and seeing it in the HUD is the point — a module that
 *    silently sits at 20% looks broken in a fight, and the tag is where that is visible without
 *    opening the ClickGUI;
 *  - **the tally** (`perfect/attempts`): how many of the jumps that actually left were timed on the
 *    exact tick the knockback arrived. It is the measured side of the same story, and it only
 *    appears once there is something to measure.
 *
 * Two shapes, nothing else: `60%` while the module has not jumped yet, `60% 3/4` afterwards. The
 * formatting lives here instead of in the module because it is the only part of the HUD that can be
 * tested without a game running (see `JumpResetHudTest`).
 */
internal object JumpResetHud {

    /** The largest and smallest percentage a value can mean, whatever the settings say. */
    private const val MIN_PERCENT = 0
    private const val MAX_PERCENT = 100

    /**
     * `60%` before the first jump, `60% 3/4` after it.
     *
     * Both axes are clamped: a config file can hold anything, and a HUD that reads `140%` or
     * `9/4` would be a lie about the module rather than a bug in the game.
     */
    fun tag(chance: Int, perfect: Int, attempts: Int): String {
        val probability = chance.coerceIn(MIN_PERCENT, MAX_PERCENT)
        val jumps = attempts.coerceAtLeast(0)
        if (jumps == 0) return "$probability%"

        // A perfect jump is a subset of the jumps: four jumps can never hold nine of them.
        val landed = perfect.coerceIn(0, jumps)
        return "$probability% $landed/$jumps"
    }
}
