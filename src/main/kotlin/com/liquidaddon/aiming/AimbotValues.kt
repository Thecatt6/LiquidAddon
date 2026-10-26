package com.liquidaddon.aiming

import net.ccbluex.liquidbounce.config.types.group.ValueGroup

/**
 * The settings of `AimbotAI`: the engine is the assist's ([AimassistCore]), the settings are not.
 *
 * The aimbot aims on its own — it picks the target itself ([autoAim], no crosshair requirement) and
 * pulls all the way to it — so the knobs that make the assist a nudge (reaction pause, fade-in, idle
 * beats, dead zone, friction) are not options here: they are fixed to the value an aimbot wants.
 * What is left is what actually describes *how* it aims: how fast, with which curve (or with
 * LiquidBounce's trained model), how much it leads the target, and the rotation hygiene that keeps
 * the packets looking like a mouse (`GcdFix`, `GcdDither`, `Jitter`, `FrameInterpolation`,
 * `Silent`/`MoveFix`).
 *
 * Everything the engine reads is declared here as an override of [AimSettings]; the two lists are
 * different on purpose, the maths behind them is the same one the Aimassist runs.
 */
internal class AimbotValues(group: ValueGroup) : AimSettings {

    // --- who to aim at -----------------------------------------------------------------------

    /** Reach of the aimbot: only targets closer than this, in blocks, are picked. */
    override val range by group.float("Range", 4.2f, 1f..6f, "blocks")

    /**
     * Half-angle is `Fov / 2`: 360 sees all around the player, without needing to look at anybody.
     * Lower it to keep the aimbot in front of you.
     */
    override val fov by group.float("Fov", 360f, 10f..360f, "°")

    override val priority by group.enumChoice("Priority", Priority.FOV)
    override val throughWalls by group.boolean("ThroughWalls", true)
    override val antiBot by group.boolean("AntiBot", true)
    override val minHealth by group.int("MinHealth", 0, 0..20, "HP")
    override val lockTarget by group.boolean("LockTarget", true)
    override val switchDelay by group.int("SwitchDelay", 120, 0..2000, "ms")

    // --- how it aims -------------------------------------------------------------------------

    /** Hard cap of a single tick, in degrees: how fast the aim may travel. 20 is a fast flick. */
    override val maxSpeed by group.float("AimSpeed", 20f, 1f..40f, "°")

    /** Share of the remaining angle covered per tick. 100 = it lands on the target at once. */
    override val strength by group.float("Strength", 100f, 10f..100f, "%")

    override val smoothing by group.enumChoice("Smoothing", Smoothing.BEZIER)

    /**
     * `Math` is the curve above, `AI` is the combat model LiquidBounce already trained on recorded
     * human fights — the same one the assist can use, and the same trick: nothing is trained here.
     */
    override val engine by group.enumChoice("Engine", Engine.MATH)
    override val aiModel by group.enumChoice("AIModel", AiModel.MODEL_19KC8KP)

    /** Share of the vertical correction (humans track yaw better than pitch). */
    override val pitchAssist by group.float("PitchAssist", 70f, 0f..100f, "%")

    override val aimPoint by group.enumChoice("AimPoint", AimPoint.CLOSEST)

    /** Lead the target by its own motion, in ticks. */
    override val predict by group.float("Predict", 0.15f, 0f..1f)
    override val predictSize by group.int("PredictSize", 2, 0..10, "ticks")

    // --- how the rotation reaches the server -------------------------------------------------

    /** Only the server sees the aim; the camera stays where the player points it. */
    override val silent by group.boolean("Silent", true)
    override val moveFix by group.enumChoice("MoveFix", MoveFix.SILENT)
    override val frameInterpolation by group.boolean("FrameInterpolation", true)

    /** Snap every step to whole mouse counts and carry the residual: the deltas stay plausible. */
    override val gcdFix by group.boolean("GcdFix", true)
    override val gcdDither by group.boolean("GcdDither", false)

    /** A trace of noise on the step, in degrees, so the path is not a perfect curve. */
    private val jitter by group.float("Jitter", 0.03f, 0f..2f, "°")

    override val debug by group.boolean("Debug", false)

    // --- fixed: the difference between aiming by itself and helping a hand --------------------

    /** Always the aiming mode: the aimbot rotates, it never only damps the cursor. */
    override val mode get() = Mode.ASSIST

    /** The engine picks the target and pulls at full weight instead of waiting for the crosshair. */
    override val autoAim get() = true

    override val onlyOnCrosshair get() = false
    override val turnCheck get() = false
    override val friction get() = 0f

    /** Close enough to count as on the target; a hair of slack, so it stops instead of humming. */
    override val deadZone get() = 0.02f

    /** No reaction pause, no ramp-up, no idle beat: it starts the moment the target is picked. */
    override val reactionDelay get() = 0
    override val reactionRandom get() = 0
    override val fadeIn get() = 0
    override val idleChance get() = 0

    override val jitterYaw get() = jitter
    override val jitterPitch get() = jitter

    override val aiYawMultiplier get() = 1.5f
    override val aiPitchMultiplier get() = 1f

    /** How much of the remaining angle the model's answer is completed with, when `Engine = AI`. */
    override val aiCorrection get() = 30f

    override val pauseOnInventory get() = true
    override val pauseOnUse get() = false
    override val chatLog get() = false

    /** No presets on the aimbot: the sliders above are the whole configuration. */
    override val presetName get() = ""
}
