package com.liquidaddon.modules.jumpreset

/** What one jump reset attempt turns into. */
internal sealed interface ResetOutcome {
    /** The jump lands on the `hurtTime` value that actually resets the knockback. */
    data object Perfect : ResetOutcome

    /**
     * The hand moves, but late: the knockback has already been applied and the jump resets nothing.
     * [ticks] is counted from the perfect window, so it is always at least one tick late.
     */
    data class Late(val ticks: Int) : ResetOutcome

    /** The hand does not move at all — a hit taken with no reaction, like a player who missed it. */
    data object NoJump : ResetOutcome
}

/**
 * The roll behind a jump reset attempt, expressed in bands of one thousand instead of as a chain of
 * `Random` calls.
 *
 * The module has always been read from the outside as "85% right, 7.5% late, 7.5% nothing", and
 * that reading is what this class fixes in place: one roll decides the outcome, the bands are
 * computed from `Accuracy` and never overlap, and the three of them always add up to [BANDS]. A
 * second roll decides *how* late, uniformly inside the late window.
 *
 * Nothing here is a bypass claim — it is the same distribution the module already had, written so
 * that it can be counted instead of trusted.
 */
internal object ResetRoll {
    /** How many bands a whole roll is split into: one band is one tenth of a percent. */
    const val BANDS = 1000

    /**
     * Of the attempts that miss, how many are the late kind and how many are no jump at all, in
     * bands of the miss. Half and half: a miss that never moves the hand is exactly as common as a
     * miss that moves it too late.
     */
    const val LATE_OF_MISS = 500

    /** Bands per percent of `Accuracy`. */
    private const val BANDS_PER_PERCENT = BANDS / 100

    /**
     * The three bands — perfect, late, no jump — for an `Accuracy` in percent.
     *
     * The order matters and is the order [outcome] reads them in: perfect first, then late, then
     * nothing. `Accuracy = 85` gives `850 / 75 / 75`; `0` gives `0 / 500 / 500`; `100` gives
     * `1000 / 0 / 0`. The three always add up to [BANDS], whatever the accuracy.
     */
    fun bands(accuracy: Int): IntArray {
        val perfect = accuracy.coerceIn(0, 100) * BANDS_PER_PERCENT
        val miss = BANDS - perfect
        val late = miss * LATE_OF_MISS / BANDS
        return intArrayOf(perfect, late, miss - late)
    }

    /**
     * Rolls one attempt.
     *
     * @param roll the outcome roll, `0 until` [BANDS]; anything outside is clamped, so a bad roll
     *   can never produce an outcome the bands do not contain
     * @param accuracy the `Accuracy` setting, in percent
     * @param lateRoll the roll that picks *how* late, only read on the late band
     * @param lateMin the smallest lateness, in ticks after the perfect window
     * @param lateMax the largest lateness, in ticks after the perfect window
     */
    fun outcome(
        roll: Int,
        accuracy: Int,
        lateRoll: Int,
        lateMin: Int,
        lateMax: Int,
    ): ResetOutcome {
        val bands = bands(accuracy)
        val r = roll.coerceIn(0, BANDS - 1)
        if (r < bands[0]) return ResetOutcome.Perfect
        if (r < bands[0] + bands[1]) return ResetOutcome.Late(lateTicks(lateRoll, lateMin, lateMax))
        return ResetOutcome.NoJump
    }

    /**
     * How many ticks late, uniform inside `[lateMin, lateMax]`.
     *
     * A window that is upside down or empty collapses to its single legal value instead of throwing,
     * and the lateness is never below one tick: a "late" attempt that lands on the perfect window
     * would be a perfect attempt wearing a miss label.
     */
    fun lateTicks(roll: Int, lateMin: Int, lateMax: Int): Int {
        val low = lateMin.coerceAtLeast(1)
        val high = lateMax.coerceAtLeast(low)
        val span = high - low + 1
        val inside = ((roll % span) + span) % span
        return low + inside
    }
}
