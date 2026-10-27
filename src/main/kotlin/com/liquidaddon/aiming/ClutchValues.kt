package com.liquidaddon.aiming

import net.ccbluex.liquidbounce.config.types.group.ValueGroup

/**
 * The settings of `Clutch`: the aimbot's engine, tuned for putting a block under a falling player.
 *
 * The engine is the same one `Aimassist`, `AimbotAI` and `FireballDeflect` run ([AimassistCore]) —
 * the mouse-count lattice with its carried residual, the hand on top of it, the model LiquidBounce
 * already trained under `Engine = AI`, the per-tick pitch limit, the frame interpolation and the
 * silent rotation through `RotationManager`. Nothing about *how* it turns is written twice.
 *
 * The tuning is the one of a placement, and it is not the one of a fight:
 *
 *  - **no lead**: a block face does not move, so `Predict` is zero — the aim goes to the face, not
 *    past it;
 *  - **`AimSpeed 120`**: a clutch is a rescue, the aim has a few ticks to be on the face before the
 *    player is out of reach of it; on top of that the placement waits for the rotation to arrive, so
 *    speed here costs no safety, it only decides whether the save happens in time;
 *  - **`Predict = 0`, `PitchAssist = 100`**: the vertical is as important as the horizontal, a face
 *    two blocks below is a steep angle;
 *  - no reaction, no idle beat, no fade: the delay between "I am falling" and "there is a block
 *    under me" is the whole margin.
 */
internal class ClutchValues(group: ValueGroup) : AimSettings {

    // --- what the engine is allowed to look at -----------------------------------------------

    /** Only used for the debug line: the point is given by the module, not searched for. */
    override val range by group.float("Range", 4.5f, 1f..8f, "blocks")

    override val fov by group.float("Fov", 360f, 10f..360f, "°")
    override val priority by group.enumChoice("Priority", Priority.FOV)
    override val throughWalls by group.boolean("ThroughWalls", true)
    override val antiBot by group.boolean("AntiBot", false)
    override val minHealth by group.int("MinHealth", 0, 0..20, "HP")
    override val lockTarget by group.boolean("LockTarget", true)
    override val switchDelay by group.int("SwitchDelay", 0, 0..2000, "ms")

    // --- how it aims -------------------------------------------------------------------------

    /** Degrees per tick. A rescue has to be on the face within a couple of ticks. */
    override val maxSpeed by group.float("AimSpeed", 120f, 1f..180f, "°/tick")

    /** Share of the remaining angle per tick. 100 = it lands on the face at once. */
    override val strength by group.float("Strength", 100f, 10f..100f, "%")

    override val smoothing by group.enumChoice("Smoothing", Smoothing.LINEAR)

    /**
     * `AI` by default: the model LiquidBounce trained on recorded human fights is what makes the
     * snap look like a hand that noticed the fall — the same bypass the aimbot and the deflector
     * use. `Math` uses the curve above instead.
     */
    override val engine by group.enumChoice("Engine", Engine.AI)
    override val aiModel by group.enumChoice("AIModel", AiModel.MODEL_19KC8KP)
    override val aiYawMultiplier get() = 1.5f
    override val aiPitchMultiplier get() = 1.2f
    override val aiCorrection by group.float("AICorrection", 35f, 0f..100f, "%")

    override val pitchAssist by group.float("PitchAssist", 100f, 0f..100f, "%")
    override val aimPoint by group.enumChoice("AimPoint", AimPoint.CLOSEST)

    /** No lead: a face stays where it is. */
    override val predict get() = 0f
    override val predictSize get() = 0

    // --- how the rotation reaches the server -------------------------------------------------

    /** The camera stays the player's; only the server sees the snap, and the block still lands. */
    override val silent by group.boolean("Silent", true)
    override val moveFix by group.enumChoice("MoveFix", MoveFix.SILENT)
    override val frameInterpolation by group.boolean("FrameInterpolation", true)

    override val gcdFix by group.boolean("GcdFix", true)
    override val gcdDither by group.boolean("GcdDither", true)

    /** The hand on top of the lattice, as in the two aiming modules. A rescue gets less of it. */
    override val mouseNoise by group.int("MouseNoise", 1, 0..4, "counts")
    override val overshoot by group.int("Overshoot", 6, 0..30, "%")
    override val strengthSpread by group.int("StrengthSpread", 10, 0..50, "%")

    override val jitterYaw get() = 0.02f
    override val jitterPitch get() = 0.02f

    // --- fixed: this is a rescue, not an aim --------------------------------------------------

    override val mode get() = Mode.ASSIST
    override val autoAim get() = true
    override val onlyOnCrosshair get() = false
    override val turnCheck get() = false
    override val friction get() = 0f

    /** On the face means on the face: a placement needs the exact block. */
    override val deadZone get() = 0.02f

    /** No reaction and no idle beat: the margin of a clutch is the fall itself. */
    override val reactionDelay get() = 0
    override val reactionRandom get() = 0
    override val fadeIn get() = 0
    override val idleChance get() = 0

    /** The module clicks itself, once the rotation has arrived. */
    override val attackSync get() = false

    override val pauseOnInventory get() = true
    override val pauseOnUse get() = false
    override val chatLog get() = false
    override val debug get() = false

    override val presetName get() = ""
}
