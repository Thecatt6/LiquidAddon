package com.liquidaddon.aiming

import net.ccbluex.liquidbounce.config.types.group.ValueGroup

/**
 * The settings of `FireballJumpAI`: the aimbot's engine, tuned for one flick and one throw.
 *
 * The engine is the same one `Aimassist`, `AimbotAI` and `FireballDeflect` run ([AimassistCore]) —
 * the mouse-count lattice with its carried residual, `GcdDither`, the hand on top of it
 * (`MouseNoise`, `Overshoot`, `StrengthSpread`), the hand profile that changes every half minute,
 * the frame interpolation, the silent rotation through `RotationManager` with `MoveFix`, and
 * LiquidBounce's already-trained combat model under `Engine = AI`. Nothing about *how* it turns is
 * written twice, and the throw is aimed exactly like a hit is.
 *
 * What changes is the tuning, and only because the aim is not a track but a flick:
 *
 *  - `Engine = AI` by default, with a large `AICorrection`: the model answers with the movement of a
 *    whole human tick, which is the shape a flick has, and the correction is what makes it arrive in
 *    three ticks instead of ten;
 *  - `PitchAssist = 100`: this aim is *all* pitch. The default (humans track yaw better than pitch)
 *    would halve the vertical speed of a turn that has nothing horizontal to do;
 *  - `AimSpeed = 45` and the vertical cap raised to it ([pitchStepMax]): looking down at your own
 *    feet is 80° and the moment to throw is two ticks after the jump. The combat default of 3°/tick
 *    needs a second and a half, which is why the engine lets a module raise it;
 *  - `Smoothing = Linear` and full `Strength`: the goal is far away and gets there fast, so the
 *    response curve has nothing to bend. What keeps the flick from being a teleport is the lattice,
 *    the soft limit at `AimSpeed`, the noise and the overshoot — not a slow curve;
 *  - no rest beats ([restBeats] off) and no reaction pause: a beat in the middle of the flick is
 *    300 ms of standing still staring at the floor, and the jump does not wait;
 *  - `Fov = 360`, `ThroughWalls`, no `AntiBot`, no `MinHealth`: the target is the player itself,
 *    named by the module ([AimassistCore.forcedTarget]), so nothing may filter it out;
 *  - no `Predict`, no `AimPoint`: the goal is a direction, not a body ([AimassistCore.forcedGoal]);
 *  - `AttackSync` off: the module spends an item, it never clicks, and the engine must not hold a
 *    click that belongs to the player.
 */
internal class FireballJumpValues(group: ValueGroup) : AimSettings {

    // --- what to aim at ----------------------------------------------------------------------

    /** Reach of the aim. The target is the player itself, so this is only the cone's outer edge. */
    override val range by group.float("Range", 4.2f, 1f..6f, "blocks")

    /** The module names the target, so the cone must not be able to filter it out. */
    override val fov by group.float("Fov", 360f, 10f..360f, "°")

    override val priority by group.enumChoice("Priority", Priority.FOV)
    override val throughWalls by group.boolean("ThroughWalls", true)
    override val antiBot by group.boolean("AntiBot", false)
    override val minHealth by group.int("MinHealth", 0, 0..20, "HP")
    override val lockTarget by group.boolean("LockTarget", true)
    override val switchDelay by group.int("SwitchDelay", 0, 0..2000, "ms")

    // --- how it aims -------------------------------------------------------------------------

    /**
     * Hard cap of one tick, and — through [pitchStepMax] — the cap of the vertical one too. 45 is a
     * hard flick down: three ticks from the horizon to the feet.
     */
    override val maxSpeed by group.float("AimSpeed", 45f, 1f..180f, "°/tick")

    /** Share of the remaining angle per tick. 100 = the flick goes as fast as the cap allows. */
    override val strength by group.float("Strength", 100f, 10f..100f, "%")

    override val smoothing by group.enumChoice("Smoothing", Smoothing.LINEAR)

    /**
     * `AI` by default: LiquidBounce's trained model, the same one the Aimassist, the aimbot and the
     * deflector can use. Its answer is a whole human tick of mouse movement, which is what a flick
     * is; the maths engine stays as the fallback and as `Engine = Math`.
     */
    override val engine by group.enumChoice("Engine", Engine.AI)
    override val aiModel by group.enumChoice("AIModel", AiModel.MODEL_19KC8KP)

    /** The model's own scale. Above one: the error here is always the whole way down. */
    override val aiYawMultiplier get() = 1.5f
    override val aiPitchMultiplier get() = 1.5f

    /**
     * How much of what is left is completed by the maths engine when the model under-shoots. High on
     * purpose: a flick has to arrive, and the correction is what guarantees the last degrees do —
     * the model answers for a body at eye level, not for the floor, and out of its domain it may
     * answer with less than nothing.
     */
    override val aiCorrection by group.float("AICorrection", 55f, 0f..100f, "%")

    /** The aim is all pitch: the vertical correction is not the one that rides along, it *is* it. */
    override val pitchAssist by group.float("PitchAssist", 100f, 0f..100f, "%")

    override val aimPoint by group.enumChoice("AimPoint", AimPoint.CLOSEST)

    /** Nothing to lead: the goal is a direction relative to the player, not a moving body. */
    override val predict get() = 0f
    override val predictSize get() = 0

    /** The flick is the whole point, so the vertical cap is the horizontal one (see [maxSpeed]). */
    override val pitchStepMax get() = maxSpeed

    /** A flick has an appointment: no beats of rest in the middle of it (see [AimSettings.restBeats]). */
    override val restBeats get() = false

    // --- how the rotation reaches the server -------------------------------------------------

    /** The camera stays the player's: the server is the only one that sees the look down. */
    override val silent by group.boolean("Silent", true)
    override val moveFix by group.enumChoice("MoveFix", MoveFix.SILENT)
    override val frameInterpolation by group.boolean("FrameInterpolation", true)

    /** Whole mouse counts, with the residual carried: every delta is one a mouse could produce. */
    override val gcdFix by group.boolean("GcdFix", true)
    override val gcdDither by group.boolean("GcdDither", true)

    /** The hand on top of the lattice, exactly as in the aiming modules: this is the "errors" part. */
    override val mouseNoise by group.int("MouseNoise", 2, 0..4, "counts")
    override val overshoot by group.int("Overshoot", 8, 0..30, "%")
    override val strengthSpread by group.int("StrengthSpread", 15, 0..50, "%")

    /** A trace of noise in degrees, on top of the counts (see `JitterYaw` in the Aimassist). */
    override val jitterYaw get() = 0.02f
    override val jitterPitch get() = 0.02f

    // --- fixed: this is a movement trick, not a fight -----------------------------------------

    /** It rotates; it never only damps a cursor that is already moving. */
    override val mode get() = Mode.ASSIST

    /** The module names the target, so the engine must not wait for the crosshair to agree. */
    override val autoAim get() = true

    override val onlyOnCrosshair get() = false

    /** Never fight the hand: the player is busy holding W and the flick has to happen anyway. */
    override val turnCheck get() = false
    override val friction get() = 0f

    /** Close enough to count as looking down; a hair of slack, so it stops instead of humming. */
    override val deadZone get() = 0.05f

    /** It starts the moment the jump is armed: the throw is waiting on the other side of the flick. */
    override val reactionDelay get() = 0
    override val reactionRandom get() = 0
    override val fadeIn get() = 0
    override val idleChance get() = 0

    /** This module spends items, it does not attack: the engine has no click to hold. */
    override val attackSync get() = false

    override val pauseOnInventory get() = true
    override val pauseOnUse get() = false
    override val chatLog get() = false
    override val debug get() = false

    /** No presets: the sliders above are the whole configuration. */
    override val presetName get() = ""
}
