package com.liquidaddon.aiming

import net.ccbluex.liquidbounce.config.types.group.ValueGroup

/**
 * The settings of `FireballDeflect`: the aimbot's engine, tuned for something flying at your face.
 *
 * The engine is the same one `Aimassist` and `AimbotAI` run ([AimassistCore]) — the mouse-count
 * lattice with its carried residual, `GcdDither`, the hand on top of it (`MouseNoise`, `Overshoot`,
 * `StrengthSpread`), the per-tick pitch limit, the frame interpolation, the silent rotation through
 * `RotationManager`, and LiquidBounce's already-trained combat model under `Engine = AI`. Nothing
 * about *how* it turns is written twice.
 *
 * What changes is the tuning, and only because the target is a projectile:
 *
 *  - `Engine = AI` by default: the model is what makes the correction look like a hand, and a
 *    deflection is the one case where the aim has to be both fast and believable;
 *  - `Predict = 1` tick: a fireball moves about a block per tick, so the aim goes where it *will*
 *    be. Combined with the module's rule (attack only when the rotation the **server** has is
 *    already on the projectile) the lead is exactly right: the rotation sent on one tick points at
 *    the position the fireball reaches on the next one;
 *  - `Fov = 180` and `ThroughWalls = true`: the thing is picked by the module, not by the cone;
 *  - no `AntiBot`, no `MinHealth`, and full `Strength` — a fireball is not a player and there is
 *    nothing to be gentle about;
 *  - `AttackSync` off: this module does its own clicking, and the engine must not hold a click that
 *    the deflector never made.
 */
internal class FireballValues(group: ValueGroup) : AimSettings {

    // --- what to aim at ----------------------------------------------------------------------

    /** Pick-up distance. The attack itself always happens from inside the real interaction range. */
    override val range by group.float("Range", 5.0f, 1f..8f, "blocks")

    /**
     * The module chooses the projectile, so the cone must not filter it out: a fireball coming from
     * behind still has to be hit.
     */
    override val fov by group.float("Fov", 180f, 10f..360f, "°")

    override val priority by group.enumChoice("Priority", Priority.FOV)
    override val throughWalls by group.boolean("ThroughWalls", true)
    override val antiBot by group.boolean("AntiBot", false)
    override val minHealth by group.int("MinHealth", 0, 0..20, "HP")
    override val lockTarget by group.boolean("LockTarget", true)
    override val switchDelay by group.int("SwitchDelay", 0, 0..2000, "ms")

    // --- how it aims -------------------------------------------------------------------------

    /** Hard cap of one tick. A deflection is a snap: the fireball is a block away and closing. */
    override val maxSpeed by group.float("AimSpeed", 45f, 1f..180f, "°/tick")

    /** Share of the remaining angle per tick. 100 = it lands on the aim point at once. */
    override val strength by group.float("Strength", 100f, 10f..100f, "%")

    override val smoothing by group.enumChoice("Smoothing", Smoothing.LINEAR)

    /**
     * `AI` by default: LiquidBounce's trained model, the same one the Aimassist and the aimbot can
     * use. It answers with the movement of a whole human tick, which is exactly the shape a
     * deflection needs — a fast move that is still not a lerp.
     */
    override val engine by group.enumChoice("Engine", Engine.AI)
    override val aiModel by group.enumChoice("AIModel", AiModel.MODEL_19KC8KP)

    /** The model's own scale. Slightly above one: the error here is always large. */
    override val aiYawMultiplier get() = 1.5f
    override val aiPitchMultiplier get() = 1f

    /** How much of what is left is completed by the maths engine when the model under-shoots. */
    override val aiCorrection by group.float("AICorrection", 35f, 0f..100f, "%")

    /** A projectile is at eye height as often as not: the vertical correction matters here. */
    override val pitchAssist by group.float("PitchAssist", 100f, 0f..100f, "%")

    override val aimPoint by group.enumChoice("AimPoint", AimPoint.CLOSEST)

    /** Lead, in ticks: one is a fireball's whole speed, and it is what makes the hit land. */
    override val predict by group.float("Predict", 1f, 0f..3f, "ticks")
    override val predictSize by group.int("PredictSize", 1, 0..10, "ticks")

    // --- how the rotation reaches the server -------------------------------------------------

    /** The camera stays the player's: the server is the only one that sees the snap. */
    override val silent by group.boolean("Silent", true)
    override val moveFix by group.enumChoice("MoveFix", MoveFix.SILENT)
    override val frameInterpolation by group.boolean("FrameInterpolation", true)

    /** Whole mouse counts, with the residual carried: every delta is one a mouse could produce. */
    override val gcdFix by group.boolean("GcdFix", true)
    override val gcdDither by group.boolean("GcdDither", true)

    /** The hand on top of the lattice, exactly as in the two aiming modules. */
    override val mouseNoise by group.int("MouseNoise", 1, 0..4, "counts")
    override val overshoot by group.int("Overshoot", 8, 0..30, "%")
    override val strengthSpread by group.int("StrengthSpread", 15, 0..50, "%")

    /** The sequence check of `MousePatternGuard`, always on: a flick is the one aim a check reads. */
    override val patternGuard get() = true

    /** A trace of noise in degrees, on top of the counts (see `JitterYaw` in the Aimassist). */
    override val jitterYaw get() = 0.02f
    override val jitterPitch get() = 0.02f

    // --- fixed: this is a defence, not an assist ----------------------------------------------

    /** It rotates; it never only damps a cursor that is already moving. */
    override val mode get() = Mode.ASSIST

    /** The module names the target, so the engine must not wait for the crosshair to agree. */
    override val autoAim get() = true

    override val onlyOnCrosshair get() = false
    override val turnCheck get() = false
    override val friction get() = 0f

    /** Converge and stay there: a hair of slack, so it stops instead of humming. */
    override val deadZone get() = 0.02f

    /**
     * A fireball does not wait. A short reaction on a *switch* (there is one projectile at a time,
     * so this is the first tick of each one) is enough to not be a perfect machine, and short enough
     * that the hit still lands.
     */
    override val reactionDelay get() = 30
    override val reactionRandom get() = 20
    override val fadeIn get() = 0
    override val idleChance get() = 0

    /** This module clicks itself, synchronised with the rotation: the engine has nothing to hold. */
    override val attackSync get() = false

    override val pauseOnInventory get() = true
    override val pauseOnUse get() = false
    override val chatLog get() = false
    override val debug get() = false

    /** No presets: the sliders above are the whole configuration. */
    override val presetName get() = ""
}
