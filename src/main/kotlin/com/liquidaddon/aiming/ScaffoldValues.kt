package com.liquidaddon.aiming

import net.ccbluex.liquidbounce.config.types.group.ValueGroup

/**
 * The settings of `ScaffoldLegit`: the aimbot's rotation engine, tuned for looking at a block face
 * only when a click needs it.
 *
 * The engine is the same one `Aimassist`, `AimbotAI`, `FireballDeflect` and `Clutch` run
 * ([AimassistCore]) — the mouse-count lattice with its carried residual, the hand on top of it, the
 * model LiquidBounce already trained under `Engine = AI`, the per-tick pitch limit, the frame
 * interpolation and the silent rotation through `RotationManager` with `MoveFix`. Nothing about
 * *how* it turns is written twice, and every bypass the aimbot has is here too.
 *
 * What is **not** here is what a placement does not need: no `Fov`, no `Range`, no `Priority`, no
 * `ThroughWalls`, no reaction or idle gates — the point to look at is handed in by the module, the
 * scan never runs, and those knobs would only be decoration in the GUI. They are fixed to the value
 * the engine wants for a forced point, and the GUI shows only the hand: how fast the look travels,
 * which curve (or the model) drives it, and the hygiene that keeps the packets looking like a mouse.
 *
 * The tuning is the one of a bridge, and it is calmer than a clutch's:
 *
 *  - **no lead**: a face does not move, so `Predict` is zero;
 *  - **`AimSpeed 90`, `Strength 100`**: the look has to be on the face within a tick or two, because
 *    the click waits for it — but the module asks for a look only when the look it already has cannot
 *    place, so most steps of a bridge never turn the head at all;
 *  - **`MouseNoise 1`**: a bridge is a long, repeated task; the hand on the lattice stays light, so
 *    a hundred blocks do not accumulate a hundred counts of drift.
 */
internal class ScaffoldValues(group: ValueGroup) : AimSettings {

    // --- fixed: the module hands the point in, the scan never runs ---------------------------

    /** Only read by the engine's debug line: the reach of the *click* is the module's own `Range`. */
    override val range get() = 4.5f

    /** Irrelevant without a scan: 360 would see everything, there is nothing to see. */
    override val fov get() = 360f

    override val priority get() = Priority.FOV
    override val throughWalls get() = true
    override val antiBot get() = false
    override val minHealth get() = 0
    override val lockTarget get() = true
    override val switchDelay get() = 0

    // --- how it aims: the only knobs the GUI shows, besides the hand -------------------------

    /** Degrees per tick. The click waits for the look, so speed here costs no safety. */
    override val maxSpeed by group.float("AimSpeed", 90f, 1f..180f, "°/tick")

    /** Share of the remaining angle per tick. 100 = the look lands on the face at once. */
    override val strength by group.float("Strength", 100f, 10f..100f, "%")

    override val smoothing by group.enumChoice("Smoothing", Smoothing.LINEAR)

    /**
     * `AI` by default: the model LiquidBounce trained on recorded human fights is what makes the
     * snap to the face look like a hand that noticed the block — the same bypass the aimbot uses.
     * `Math` uses the curve above instead.
     */
    override val engine by group.enumChoice("Engine", Engine.AI)
    override val aiModel by group.enumChoice("AIModel", AiModel.MODEL_19KC8KP)
    override val aiYawMultiplier get() = 1.5f
    override val aiPitchMultiplier get() = 1.2f
    override val aiCorrection by group.float("AICorrection", 35f, 0f..100f, "%")

    /** The vertical is as important as the horizontal: the face of a bridge is at your feet. */
    override val pitchAssist by group.float("PitchAssist", 100f, 0f..100f, "%")

    override val aimPoint by group.enumChoice("AimPoint", AimPoint.CLOSEST)

    /** No lead: a face stays where it is. */
    override val predict get() = 0f
    override val predictSize get() = 0

    // --- how the rotation reaches the server -------------------------------------------------

    /** The camera stays the player's; only the server sees the look at the block. */
    override val silent by group.boolean("Silent", true)
    override val moveFix by group.enumChoice("MoveFix", MoveFix.SILENT)
    override val frameInterpolation by group.boolean("FrameInterpolation", true)

    override val gcdFix by group.boolean("GcdFix", true)
    override val gcdDither by group.boolean("GcdDither", true)

    /** The hand on top of the lattice, lighter than the aimbot's: a bridge is a long task. */
    override val mouseNoise by group.int("MouseNoise", 1, 0..4, "counts")
    override val overshoot by group.int("Overshoot", 6, 0..30, "%")
    override val strengthSpread by group.int("StrengthSpread", 10, 0..50, "%")

    override val jitterYaw get() = 0.02f
    override val jitterPitch get() = 0.02f

    // --- fixed: this is a placement, not an aim ----------------------------------------------

    override val mode get() = Mode.ASSIST
    override val autoAim get() = true
    override val onlyOnCrosshair get() = false
    override val turnCheck get() = false
    override val friction get() = 0f

    /** On the face means on the face: a placement needs the exact block. */
    override val deadZone get() = 0.02f

    /** No reaction and no idle beat: the module asks for the look only when a click is waiting. */
    override val reactionDelay get() = 0
    override val reactionRandom get() = 0
    override val fadeIn get() = 0
    override val idleChance get() = 0

    /** The module clicks itself, once the rotation the server has is on the face. */
    override val attackSync get() = false

    override val pauseOnInventory get() = true
    override val pauseOnUse get() = false
    override val chatLog get() = false
    override val debug get() = false

    override val presetName get() = ""
}
