package com.liquidaddon.aiming

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import kotlin.math.roundToInt

/**
 * The settings of `Clutch`: the aimbot's engine, pointed at the face of a block.
 *
 * The rotation still comes from the one shared engine ([AimassistCore]) — `Engine = AI` with the
 * model LiquidBounce already trained, the mouse-count lattice with its carried residual, the hand
 * on top of it, the frame interpolation and the silent rotation through `RotationManager`. Nothing
 * about *how* it turns is written in the module.
 *
 * What is **not** here, on purpose: `Range` and `Fov`. Those are how an aim assist decides *who* to
 * look at; a clutch looks at the face of a block the module already picked, so they would be two
 * sliders that do nothing. The engine still reads them (its interface has them), the values are
 * constants, and the ClickGUI does not show them.
 *
 * What is here is the part that makes the turn look like a person noticing the fall:
 *
 *  - [humanize] — one knob for all of the hand: noise in mouse counts, the occasional overshoot,
 *    the spread of the speed, and the wander of the point on the face;
 *  - `AimSpeed` — how fast the eyes can travel; a clutch is a look *down*, so the pitch has to be
 *    able to go with the yaw ([maxPitchStep]);
 *  - `Strength` — how much of the remaining angle each tick takes. Below 100 the aim arcs into the
 *    face over two or three ticks instead of jumping onto it, which is both what a hand does and
 *    what keeps the placement packets looking like a player's.
 */
internal class ClutchValues(group: ValueGroup) : AimSettings {

    // --- the hand ----------------------------------------------------------------------------

    /** One knob for everything that makes the turn human. 0 is a machine, 100 is a shaky hand. */
    private val humanizeSetting by group.int("Humanize", 70, 0..100, "%")

    /**
     * How much hand there is, 0..1. The module reads it too: the spot it picks inside a face is
     * *where* a person would put the crosshair, so it is part of the same knob as the noise.
     */
    val humanize: Float get() = humanizeSetting / 100f

    /** Degrees per tick the eyes may travel. A look down of 70° is a fast flick, not a snap. */
    override val maxSpeed by group.float("AimSpeed", 55f, 5f..180f, "°/tick")

    /** Share of the remaining angle per tick: 100 lands on the face at once, 80 arcs into it. */
    override val strength by group.float("Strength", 85f, 20f..100f, "%")

    /** The model's own share of the move; the rest is the correction that guarantees arrival. */
    private val aiCorrectionSetting by group.int("AICorrection", 45, 0..100, "%")

    // --- how the rotation reaches the server -------------------------------------------------

    /** The camera stays the player's; only the server sees the look down, and the block lands. */
    override val silent by group.boolean("Silent", true)
    override val moveFix by group.enumChoice("MoveFix", MoveFix.SILENT)

    // --- fixed: a clutch is not a fight ------------------------------------------------------

    /** A point has no identity, no cone and no health: none of the scanning settings apply. */
    override val range get() = 4.5f
    override val fov get() = 360f
    override val priority get() = Priority.FOV
    override val throughWalls get() = true
    override val antiBot get() = false
    override val minHealth get() = 0
    override val lockTarget get() = false
    override val switchDelay get() = 0

    /** The engine is the aimbot's, and it is the one that turns the aim. */
    override val mode get() = Mode.ASSIST
    override val autoAim get() = true
    override val engine get() = Engine.AI
    override val aiModel get() = AiModel.MODEL_19KC8KP
    override val aiYawMultiplier get() = 1.5f
    override val aiPitchMultiplier get() = 1.2f
    override val aiCorrection get() = aiCorrectionSetting.toFloat()

    override val smoothing get() = Smoothing.LINEAR
    override val pitchAssist get() = 100f
    override val aimPoint get() = AimPoint.CLOSEST

    /** A face does not move: no lead. */
    override val predict get() = 0f
    override val predictSize get() = 0

    override val onlyOnCrosshair get() = false
    override val turnCheck get() = false
    override val friction get() = 0f

    /** On the face means on the face: the placement needs the block, not the air next to it. */
    override val deadZone get() = 0.02f

    /** No reaction, no rest, no fade: the margin of a clutch is the fall itself. */
    override val reactionDelay get() = 0
    override val reactionRandom get() = 0
    override val fadeIn get() = 0
    override val idleChance get() = 0

    /** The vertical has as much budget as the horizontal, or the look down never arrives. */
    override val maxPitchStep get() = 18f

    override val jitterYaw get() = 0.02f + humanize * 0.04f
    override val jitterPitch get() = 0.02f + humanize * 0.04f

    override val mouseNoise get() = (humanize * 3.5f).roundToInt()
    override val overshoot get() = (humanize * 12f).roundToInt()
    override val strengthSpread get() = (humanize * 22f).roundToInt()

    override val gcdFix get() = true
    override val gcdDither get() = true
    override val frameInterpolation get() = true

    override val pauseOnInventory get() = true
    override val pauseOnUse get() = false
    override val chatLog get() = false
    override val debug get() = false
    override val presetName get() = ""

    /** The module clicks (the placement) itself, once the rotation has arrived. */
    override val attackSync get() = false
}
