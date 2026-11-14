package com.liquidaddon.modules.hitselect

/** The switches of HitSelect that decide a drop, read once per attack. */
internal data class HitSelectSettings(
    /** `true` = Pause (drop for a while after an attack goes through), `false` = Active. */
    val pauseMode: Boolean,
    val alwaysWhenHurt: Boolean,
    val onlyWhenHurt: Boolean,
    val onlyWhenTargetVulnerable: Boolean,
    val maxTargetHurtTime: Int,
    val preferKBReduction: Boolean,
)

/** What the fight looks like at the moment of the attack, in plain numbers and flags. */
internal data class HitSelectStance(
    /** Our own `hurtTime`: above zero means the knockback of a hit is still being applied. */
    val myHurtTime: Int,
    /** The target's `hurtTime`: above zero means it is inside its own invulnerability window. */
    val targetHurtTime: Int,
    /** Whether we are falling in a way that would make this attack a critical hit. */
    val fallingForCrit: Boolean,
    /** Whether we are sprinting or moving fast enough that the knockback we deal is the reduced one. */
    val moving: Boolean,
)

/**
 * The decision behind one attack: drop it, or let it through — and, when it is dropped, why.
 *
 * The rule is the module's whole behaviour, so it is counted here instead of inside an event
 * handler: every branch is a plain function of numbers, which means the signature the module shows
 * from the outside can be asserted rather than believed.
 *
 * Nothing here makes an attack stronger. Dropping an attack is the opposite of hitting more, and
 * the only timing this class touches ([pauseLength]) keeps its own average.
 */
internal object DropRule {
    /** The pause is spread by a quarter of its own length, so it is a pause and not a metronome. */
    const val PAUSE_SPREAD_DIVISOR = 4

    /**
     * How long the pause armed by an attack that went through lasts, in ticks.
     *
     * A fixed pause turns the module into a metronome: with `PauseTicks = 10` exactly one attack in
     * eleven is let through, every time, and a period that never moves is a signature on its own.
     * Spreading it by a quarter either side keeps the average where the slider says it is — no more
     * attacks over time, only a pause whose length cannot be read off a clock — and never drops the
     * pause below one tick, so it cannot become more permissive than the slider's own minimum.
     */
    fun pauseLength(base: Int, roll: Int): Int {
        val floor = base.coerceAtLeast(1)
        val spread = floor / PAUSE_SPREAD_DIVISOR
        if (spread <= 0) return floor

        val span = spread * 2 + 1
        val inside = ((roll % span) + span) % span
        return (floor - spread + inside).coerceAtLeast(1)
    }

    /**
     * How many rolls [pauseLength] needs to cover its whole spread with no bias: roll inside
     * `0 until pauseRolls(base)` and every length the spread allows comes out equally often.
     */
    fun pauseRolls(base: Int): Int {
        val spread = base.coerceAtLeast(1) / PAUSE_SPREAD_DIVISOR
        return if (spread <= 0) 1 else spread * 2 + 1
    }

    /**
     * Why this attack is dropped, or `null` when it goes through.
     *
     * The order of the branches is the order the settings are read in, and it matters: the pause
     * answers first, then the two "when am I hurt" switches, then the target's invulnerability, and
     * only in Active mode the movement/crit preference at the end.
     */
    fun reason(
        settings: HitSelectSettings,
        pauseRemaining: Int,
        stance: HitSelectStance,
    ): String? {
        val hurt = stance.myHurtTime > 0

        // Pause: after an attack that went through, drop for a while — unless the knockback of a hit
        // is on us, because that is the one attack that must not be lost.
        if (settings.pauseMode && pauseRemaining > 0) {
            if (settings.alwaysWhenHurt && hurt) return null
            if (settings.onlyWhenHurt && !hurt) {
                return "paused ($pauseRemaining ticks) and not hurt"
            }
            return "paused ($pauseRemaining ticks)"
        }

        if (settings.alwaysWhenHurt && hurt) return null
        if (settings.onlyWhenHurt) return if (hurt) null else "not hurt"

        if (settings.onlyWhenTargetVulnerable && stance.targetHurtTime > settings.maxTargetHurtTime) {
            return "target invulnerable (hurtTime ${stance.targetHurtTime} > ${settings.maxTargetHurtTime})"
        }

        if (!settings.pauseMode) {
            return if (settings.preferKBReduction) {
                if (stance.moving && !hurt) "moving with no knockback on us" else null
            } else {
                if (stance.fallingForCrit) null else "no crit fall"
            }
        }

        return null
    }
}
