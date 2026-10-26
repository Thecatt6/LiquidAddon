package com.liquidaddon.aiming

import net.ccbluex.liquidbounce.config.autoconfig.AutoConfig
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine
import net.ccbluex.liquidbounce.deeplearn.ModelManager
import net.ccbluex.liquidbounce.deeplearn.data.CombatSample
import net.ccbluex.liquidbounce.deeplearn.models.TwoDimensionalRegressionModel
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.misc.antibot.ModuleAntiBot
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.lastPos
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.kotlin.Priority as RotationPriority
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.round
import kotlin.math.tanh
import kotlin.random.Random

/**
 * The aiming engine behind `Aimassist` and `AimbotAI` — one implementation, two settings lists.
 *
 * There is no second aiming implementation: both modules drive an [AimassistCore], so every bypass
 * trick the assist has (the mouse-count lattice and its carried residual, the dither, the frame
 * interpolation, the silent rotation through `RotationManager`, the trained model) is the one the
 * aimbot uses too, running under another name in the chat and in `RotationManager`'s request log.
 *
 * What they do not share is the configuration. The assist reads [AimValues] — the whole humanization
 * toolbox, because every knob there is what keeps it a nudge. The aimbot reads [AimbotValues], a
 * shorter list with the values an aimbot wants fixed (`autoAim`, full pull, no reaction pause, no
 * idle beat), so it picks the target by itself instead of helping the hand that is already on it.
 * Both satisfy [AimSettings], which is all the engine knows about them.
 *
 * The pipeline, once per tick: [findTarget] -> [bubble] -> [shape] (or [aiStep]) -> [quantizeDelta]
 * -> the step is granted to the camera, or handed to `RotationManager` when `silent`.
 *
 *  - [findTarget] only looks inside a narrow cone around where you are already pointing ([fov]),
 *    unless the module aims by itself (`autoAim`), in which case the cone is just the reach;
 *  - [bubble] is the weight of the help: 1.0 while the crosshair is on the hitbox, falling to 0 at
 *    the edge of the cone — strongest where it cannot be seen, gone where it would be a snap;
 *  - [shape] bends the response (Linear/Bezier/Sigmoid), while [strength] is the share of the
 *    remaining angle covered per tick and [maxSpeed] the hard cap of a single tick;
 *  - [Engine.AI] replaces [shape] with a model LiquidBounce already trained on recorded human
 *    fights (see [aiStep]), scaled by the same [strength] and finished by [aiCorrection];
 *  - [quantizeDelta] snaps every step to whole mouse counts and carries the rounding residual, so
 *    the deltas are the ones a real mouse would produce;
 *  - [frameInterpolation] (not silent) spreads a tick's step over the frames of that tick.
 *
 * What keeps it looking like a hand: [turnCheck] (never fight the player), [fadeIn] (no initial
 * kick), [onlyOnCrosshair] with its grace, [reactionDelay]/[reactionRandom], [idleChance],
 * [deadZone], [gcdFix]/[gcdDither] and a trace of [jitterYaw]/[jitterPitch], plus a sticky target
 * ([lockTarget]) so the aim never wobbles between two enemies.
 *
 * [silent] `false` helps the camera itself; `true` goes through `RotationManager` with [moveFix] at
 * `IMPORTANT_FOR_USAGE_1`, i.e. below KillAura/Aimbot, which always keep the rotation themselves.
 */

/** A turn smaller than this many degrees counts as "the player is not moving the mouse". */
private const val STILL = 0.02f

/** How far (in mouse counts) the rounding boundary may wobble when `gcdDither` is on. */
private const val DITHER_WOBBLE = 0.18

/**
 * The share of the step an "idle" tick still applies when the camera is ours: a skipped tick would
 * freeze the view for 50 ms and then catch up with a bigger step, which is the stutter the engine
 * is trying not to have.
 */
private const val IDLE_GLIDE = 0.15f

/** A push away from the target of this many degrees in one tick silences the pull completely. */
private const val TURN_AWAY = 2f

/** How long `onlyOnCrosshair` keeps accepting a target after the ray left the hitbox. */
private const val CROSSHAIR_GRACE_MS = 140L

/** How often the engine may say in chat that the AI model could not answer. */
private const val AI_NOTICE_MS = 15000L

enum class Mode(override val tag: String) : Tagged {
    ASSIST("Assist"), FRICTION("Friction"), BOTH("Both")
}

enum class Priority(override val tag: String) : Tagged {
    FOV("Fov"), DISTANCE("Distance"), HEALTH("Health"), ARMOR("Armor")
}

enum class Smoothing(override val tag: String) : Tagged {
    LINEAR("Linear"), BEZIER("Bezier"), SIGMOID("Sigmoid")
}

/**
 * Who computes the step: [MATH] is the curve, [AI] is the model LiquidBounce already trained.
 */
enum class Engine(override val tag: String) : Tagged {
    MATH("Math"), AI("AI")
}

/**
 * The combat models LiquidBounce ships inside its own JAR. They are already trained on recorded
 * human fights (see `DebugCombatRecorder`), so nothing has to be trained here.
 */
enum class AiModel(override val tag: String) : Tagged {
    MODEL_19KC8KP("19KC8KP"), MODEL_21KC11KP("21KC11KP")
}

enum class AimPoint(override val tag: String) : Tagged {
    CLOSEST("Closest"), HEAD("Head"), CHEST("Chest")
}

enum class MoveFix(override val tag: String) : Tagged {
    OFF("Off"), STRICT("Strict"), SILENT("Silent")
}

enum class Preset(override val tag: String) : Tagged {
    CUSTOM("Custom"), BLATANT("Blatant"), LEGIT("Legit"), GHOST("Ghost"), CLOSET("Closet"),
    AI("AI")
}

/**
 * What the aiming engine reads, and the only thing the two modules share.
 *
 * The engine itself (target selection, gates, the step maths, the mouse lattice, the rotation) is
 * one implementation, and both [AimValues] (Aimassist) and [AimbotValues] (AimbotAI) drive it. The
 * two *setting lists* are deliberately not the same: the assist exposes the whole humanization
 * toolbox because every one of those knobs is what keeps it a nudge, while the aimbot exposes the
 * few that describe how it aims (speed, curve, model) and fixes the rest to the values an aimbot
 * wants — full pull, no reaction pause, no idle beat, no friction, and a target that is picked by
 * the engine instead of by where the crosshair happens to point ([autoAim]).
 */
internal interface AimSettings {

    // --- target selection ---
    val range: Float
    val fov: Float
    val priority: Priority
    val throughWalls: Boolean
    val antiBot: Boolean
    val minHealth: Int
    val lockTarget: Boolean
    val switchDelay: Int

    // --- how much the aim is helped ---
    val mode: Mode
    val strength: Float
    val maxSpeed: Float
    val smoothing: Smoothing
    val engine: Engine
    val aiModel: AiModel
    val aiYawMultiplier: Float
    val aiPitchMultiplier: Float
    val aiCorrection: Float
    val pitchAssist: Float
    val aimPoint: AimPoint
    val predict: Float
    val predictSize: Int
    val onlyOnCrosshair: Boolean
    val turnCheck: Boolean
    val friction: Float
    val deadZone: Float
    val reactionDelay: Int
    val reactionRandom: Int
    val fadeIn: Int
    val idleChance: Int
    val jitterYaw: Float
    val jitterPitch: Float
    val gcdFix: Boolean
    val gcdDither: Boolean

    // --- how the rotation reaches the server ---
    val silent: Boolean
    val moveFix: MoveFix
    val frameInterpolation: Boolean

    // --- misc ---
    val pauseOnInventory: Boolean
    val pauseOnUse: Boolean
    val chatLog: Boolean
    val debug: Boolean

    /** Name of the active preset, only used in the chat/debug lines (empty when there is none). */
    val presetName: String

    /**
     * The engine chooses the target on its own: the cone around the crosshair stops filtering, and
     * the pull does not fade with the distance from the aim, so the module aims at whoever it
     * picked instead of helping the hand that is already there.
     */
    val autoAim: Boolean
}

/**
 * Every setting the engine reads, declared once for both modules: `Aimassist` and `AimbotAI` build
 * one of these over themselves, so the two ClickGUIs are the same list, with the same defaults, the
 * same ranges and the same presets. Changing a slider here changes it in both.
 *
 * A [Preset] writes all of the values at once (see [applyPreset]), so the GUI always shows what the
 * engine is really doing; [Preset.CUSTOM] leaves the sliders alone.
 */
@Suppress("TooManyFunctions", "LongParameterList")
internal class AimValues(
    val group: ValueGroup,
) : MinecraftShortcuts, AimSettings {

    // --- target selection -------------------------------------------------------------------

    val rangeValue = group.float("Range", 3.4f, 1f..6f, "blocks")
    override val range by rangeValue

    /** Radius of the assist cone, measured from where the player is already pointing. */
    val fovValue = group.float("Fov", 24f, 5f..120f, "°")
    override val fov by fovValue

    override val priority by group.enumChoice("Priority", Priority.FOV)
    val throughWallsValue = group.boolean("ThroughWalls", false)
    override val throughWalls by throughWallsValue
    val antiBotValue = group.boolean("AntiBot", true)
    override val antiBot by antiBotValue
    override val minHealth by group.int("MinHealth", 0, 0..20, "HP")
    override val lockTarget by group.boolean("LockTarget", true)
    val switchDelayValue = group.int("SwitchDelay", 350, 0..2000, "ms")
    override val switchDelay by switchDelayValue

    // --- how much the aim is helped ---------------------------------------------------------

    val presetValue = group.enumChoice("Preset", Preset.LEGIT)
    val preset by presetValue

    val modeValue = group.enumChoice("Mode", Mode.BOTH)
    override val mode by modeValue

    /** Share of the remaining angle covered per tick. 100 = the aim lands on the target at once. */
    val strengthValue = group.float("Strength", 35f, 1f..100f, "%")
    override val strength by strengthValue

    /** Hard cap on a single tick, in degrees. It is what keeps a correction a nudge, never a flick. */
    val maxSpeedValue = group.float("MaxSpeed", 7f, 0.5f..40f, "°")
    override val maxSpeed by maxSpeedValue

    val smoothingValue = group.enumChoice("Smoothing", Smoothing.BEZIER)
    override val smoothing by smoothingValue

    /**
     * Who computes the step, see [Engine]. [Engine.MATH] is the curve, [Engine.AI] is the model
     * LiquidBounce already trained; the rest of the module is identical for both.
     */
    val engineValue = group.enumChoice("Engine", Engine.MATH)
    override val engine by engineValue

    /** Which bundled LiquidBounce model answers while [engine] is [Engine.AI]. */
    val aiModelValue = group.enumChoice("AIModel", AiModel.MODEL_19KC8KP)
    override val aiModel by aiModelValue

    /** The per-axis gain of the model's answer, the same knob LiquidBounce's AI smooth has. */
    val aiYawMultiplierValue = group.float("AIYawMultiplier", 1.5f, 0.5f..2f)
    override val aiYawMultiplier by aiYawMultiplierValue
    val aiPitchMultiplierValue = group.float("AIPitchMultiplier", 1f, 0.5f..2f)
    override val aiPitchMultiplier by aiPitchMultiplierValue

    /**
     * Share of the remaining angle added on top of the model's answer, so the assist still
     * converges when the model under-shoots. It is what LiquidBounce calls "Correction".
     */
    val aiCorrectionValue = group.float("AICorrection", 12f, 0f..100f, "%")
    override val aiCorrection by aiCorrectionValue

    /** Share of the vertical correction, relative to the horizontal one (humans track yaw better). */
    val pitchAssistValue = group.float("PitchAssist", 55f, 0f..100f, "%")
    override val pitchAssist by pitchAssistValue

    val aimPointValue = group.enumChoice("AimPoint", AimPoint.CLOSEST)
    override val aimPoint by aimPointValue
    val predictValue = group.float("Predict", 0.1f, 0f..1f)
    override val predict by predictValue
    val predictSizeValue = group.int("PredictSize", 2, 0..10, "ticks")
    override val predictSize by predictSizeValue

    /** Assist only while the crosshair ray crosses the hitbox ([onCrosshair]). */
    val onlyOnCrosshairValue = group.boolean("OnlyOnCrosshair", false)
    override val onlyOnCrosshair by onlyOnCrosshairValue

    /** Do not fight the player: the pull fades out while the mouse is pushed away from the target. */
    val turnCheckValue = group.boolean("TurnCheck", true)
    override val turnCheck by turnCheckValue

    /** How much of the cursor movement is eaten while it glides over a hitbox ([Mode.FRICTION]). */
    val frictionValue = group.float("Friction", 20f, 0f..90f, "%")
    override val friction by frictionValue

    /** Inside this angle nothing is corrected: the aim is already where it should be. */
    val deadZoneValue = group.float("DeadZone", 0.12f, 0f..3f, "°")
    override val deadZone by deadZoneValue

    val reactionDelayValue = group.int("ReactionDelay", 140, 0..500, "ms")
    override val reactionDelay by reactionDelayValue
    val reactionRandomValue = group.int("ReactionRandom", 70, 0..300, "ms")
    override val reactionRandom by reactionRandomValue

    /** Time over which the help ramps back in after a reaction pause or a switch: no initial kick. */
    val fadeInValue = group.int("FadeIn", 220, 0..800, "ms")
    override val fadeIn by fadeInValue

    val idleChanceValue = group.int("IdleChance", 8, 0..40, "%")
    override val idleChance by idleChanceValue

    val jitterYawValue = group.float("JitterYaw", 0.12f, 0f..2f, "°")
    override val jitterYaw by jitterYawValue
    val jitterPitchValue = group.float("JitterPitch", 0.08f, 0f..2f, "°")
    override val jitterPitch by jitterPitchValue

    val gcdFixValue = group.boolean("GcdFix", true)
    override val gcdFix by gcdFixValue
    val gcdDitherValue = group.boolean("GcdDither", true)
    override val gcdDither by gcdDitherValue

    // --- how the rotation reaches the server ------------------------------------------------

    /** Only the server sees the assist; the camera stays exactly where the player points it. */
    val silentValue = group.boolean("Silent", false)
    override val silent by silentValue
    val moveFixValue = group.enumChoice("MoveFix", MoveFix.SILENT)
    override val moveFix by moveFixValue

    /**
     * Not silent: draw the tick's step slice by slice on every frame instead of all at once on the
     * tick. This is purely visual — per tick the camera (and therefore the packets) still advance
     * by exactly the quantized step the tick decided on, so turning this off only brings the 20 Hz
     * staircase back, it does not make anything safer.
     */
    override val frameInterpolation by group.boolean("FrameInterpolation", true)

    override val pauseOnInventory by group.boolean("PauseOnInventory", true)
    override val pauseOnUse by group.boolean("PauseOnUse", false)

    override val chatLog by group.boolean("ChatLog", false)
    override val debug by group.boolean("Debug", false)

    /** The assist is not an aimbot: the target comes from the crosshair, not from the module. */
    override val autoAim get() = false

    /** Only used in the chat/debug lines: the preset the values were written by. */
    override val presetName get() = preset.tag

    // --- presets and the values they write --------------------------------------------------

    /**
     * The values each preset writes, declared once ([Preset] -> [applyPreset]). A full profile, not
     * just a speed, so picking a preset leaves the GUI showing exactly what the engine does.
     */
    private class Profile(
        val mode: Mode,
        val strength: Float,
        val maxSpeed: Float,
        val smoothing: Smoothing,
        val pitchAssist: Float,
        val aimPoint: AimPoint,
        val predict: Float,
        val predictSize: Int,
        val onlyOnCrosshair: Boolean,
        val turnCheck: Boolean,
        val friction: Float,
        val deadZone: Float,
        val reactionDelay: Int,
        val reactionRandom: Int,
        val fadeIn: Int,
        val idleChance: Int,
        val jitterYaw: Float,
        val jitterPitch: Float,
        val gcdFix: Boolean,
        val gcdDither: Boolean,
        val switchDelay: Int,
        val range: Float,
        val fov: Float,
        val silent: Boolean,
        val moveFix: MoveFix,
        val throughWalls: Boolean,
        val antiBot: Boolean,
        val engine: Engine = Engine.MATH,
        val aiModel: AiModel = AiModel.MODEL_19KC8KP,
        val aiYawMultiplier: Float = 1.5f,
        val aiPitchMultiplier: Float = 1f,
        val aiCorrection: Float = 12f
    )

    /** The values each preset writes. Add or tweak here; the GUI follows automatically. */
    private fun profileOf(preset: Preset): Profile? = when (preset) {
        // No profile: the sliders stay as they are.
        Preset.CUSTOM -> null

        // Aimbot-like, only to see what the module can do (still never clicks, still never snaps
        // outside the cone). Test on your own world: it is not a legit setting.
        Preset.BLATANT -> Profile(
            mode = Mode.BOTH, strength = 90f, maxSpeed = 40f, smoothing = Smoothing.LINEAR,
            pitchAssist = 100f, aimPoint = AimPoint.CLOSEST, predict = 0.2f, predictSize = 3,
            onlyOnCrosshair = false, turnCheck = false, friction = 0f, deadZone = 0f,
            reactionDelay = 40, reactionRandom = 20, fadeIn = 60, idleChance = 0,
            jitterYaw = 0.05f, jitterPitch = 0.05f, gcdFix = true, gcdDither = false,
            switchDelay = 100, range = 4.2f, fov = 90f, silent = true, moveFix = MoveFix.SILENT,
            throughWalls = false, antiBot = true
        )

        // The default: the camera is helped, the mouse is damped on the hitbox, nothing is taken
        // away from the hand.
        Preset.LEGIT -> Profile(
            mode = Mode.BOTH, strength = 35f, maxSpeed = 7f, smoothing = Smoothing.BEZIER,
            pitchAssist = 55f, aimPoint = AimPoint.CLOSEST, predict = 0.1f, predictSize = 2,
            onlyOnCrosshair = false, turnCheck = true, friction = 20f, deadZone = 0.12f,
            reactionDelay = 140, reactionRandom = 70, fadeIn = 220, idleChance = 8,
            jitterYaw = 0.12f, jitterPitch = 0.08f, gcdFix = true, gcdDither = true,
            switchDelay = 350, range = 3.4f, fov = 24f, silent = false, moveFix = MoveFix.SILENT,
            throughWalls = false, antiBot = true
        )

        // Small, slow and rare: for strict rotation statistics. You will barely feel it, except
        // when a target slides past the crosshair.
        Preset.GHOST -> Profile(
            mode = Mode.BOTH, strength = 18f, maxSpeed = 3.5f, smoothing = Smoothing.SIGMOID,
            pitchAssist = 35f, aimPoint = AimPoint.CLOSEST, predict = 0.05f, predictSize = 2,
            onlyOnCrosshair = true, turnCheck = true, friction = 35f, deadZone = 0.3f,
            reactionDelay = 220, reactionRandom = 120, fadeIn = 320, idleChance = 18,
            jitterYaw = 0.1f, jitterPitch = 0.06f, gcdFix = true, gcdDither = true,
            switchDelay = 600, range = 3.1f, fov = 18f, silent = false, moveFix = MoveFix.SILENT,
            throughWalls = false, antiBot = true
        )

        // Silent: the camera is yours, only the rotation the server receives is helped.
        Preset.CLOSET -> Profile(
            mode = Mode.ASSIST, strength = 28f, maxSpeed = 5f, smoothing = Smoothing.BEZIER,
            pitchAssist = 45f, aimPoint = AimPoint.CHEST, predict = 0.1f, predictSize = 2,
            onlyOnCrosshair = true, turnCheck = true, friction = 0f, deadZone = 0.2f,
            reactionDelay = 180, reactionRandom = 90, fadeIn = 260, idleChance = 12,
            jitterYaw = 0.1f, jitterPitch = 0.07f, gcdFix = true, gcdDither = true,
            switchDelay = 450, range = 3.2f, fov = 24f, silent = true, moveFix = MoveFix.SILENT,
            throughWalls = false, antiBot = true
        )

        // The model instead of the curve. Strength is the share of the human move the assist
        // copies; the correction is small because the model already aims at the target, and the
        // jitter is almost zero because the model's answer is noisy in the right way by itself.
        Preset.AI -> Profile(
            mode = Mode.BOTH, strength = 80f, maxSpeed = 9f, smoothing = Smoothing.BEZIER,
            pitchAssist = 60f, aimPoint = AimPoint.CLOSEST, predict = 0.15f, predictSize = 2,
            onlyOnCrosshair = true, turnCheck = true, friction = 15f, deadZone = 0.15f,
            reactionDelay = 160, reactionRandom = 80, fadeIn = 240, idleChance = 6,
            jitterYaw = 0.05f, jitterPitch = 0.04f, gcdFix = true, gcdDither = true,
            switchDelay = 400, range = 3.4f, fov = 30f, silent = true, moveFix = MoveFix.SILENT,
            throughWalls = false, antiBot = true,
            engine = Engine.AI, aiModel = AiModel.MODEL_19KC8KP,
            aiYawMultiplier = 1.5f, aiPitchMultiplier = 1f, aiCorrection = 12f
        )
    }

    /**
     * Writes [Preset] into the module's values, so the GUI always shows what is actually being
     * used. Called whenever the preset changes; [Preset.CUSTOM] does nothing.
     */
    private fun applyPreset(preset: Preset) {
        val p = profileOf(preset) ?: return

        modeValue.set(p.mode)
        strengthValue.set(p.strength)
        maxSpeedValue.set(p.maxSpeed)
        smoothingValue.set(p.smoothing)
        engineValue.set(p.engine)
        aiModelValue.set(p.aiModel)
        aiYawMultiplierValue.set(p.aiYawMultiplier)
        aiPitchMultiplierValue.set(p.aiPitchMultiplier)
        aiCorrectionValue.set(p.aiCorrection)
        pitchAssistValue.set(p.pitchAssist)
        aimPointValue.set(p.aimPoint)
        predictValue.set(p.predict)
        predictSizeValue.set(p.predictSize)
        onlyOnCrosshairValue.set(p.onlyOnCrosshair)
        turnCheckValue.set(p.turnCheck)
        frictionValue.set(p.friction)
        deadZoneValue.set(p.deadZone)
        reactionDelayValue.set(p.reactionDelay)
        reactionRandomValue.set(p.reactionRandom)
        fadeInValue.set(p.fadeIn)
        idleChanceValue.set(p.idleChance)
        jitterYawValue.set(p.jitterYaw)
        jitterPitchValue.set(p.jitterPitch)
        gcdFixValue.set(p.gcdFix)
        gcdDitherValue.set(p.gcdDither)
        switchDelayValue.set(p.switchDelay)
        rangeValue.set(p.range)
        fovValue.set(p.fov)
        silentValue.set(p.silent)
        moveFixValue.set(p.moveFix)
        throughWallsValue.set(p.throughWalls)
        antiBotValue.set(p.antiBot)

        if (debug && mc.player != null) {
            chat("§7[" + group.name + "] §fpreset ${preset.tag} applied")
        }
    }

    init {
        // Switching preset rewrites the sliders; CUSTOM leaves them alone. Nothing is applied while
        // the config is loading, otherwise a restored Preset would overwrite the sliders that were
        // restored right next to it.
        presetValue.onChanged { if (!AutoConfig.loadingNow) applyPreset(it) }
    }
}

/**
 * The engine itself: all of the state and all of the maths behind [AimSettings], for one module.
 *
 * A module only registers its three event handlers, forwards them here ([tick], [onMouseRotation],
 * [onRender]) and calls [onEnable]/[onDisable] from its lifecycle. Everything else — target
 * selection, the reaction and idle gates, the model or the curve, the mouse lattice, the silent
 * rotation through `RotationManager`, the walk-back when it lets go — lives here, once.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class AimassistCore(
    private val owner: ClientModule,
    private val values: AimSettings,
) : MinecraftShortcuts {

    // --- the values, read as if they were fields of the engine -------------------------------

    private val range get() = values.range
    private val fov get() = values.fov
    private val priority get() = values.priority
    private val throughWalls get() = values.throughWalls
    private val antiBot get() = values.antiBot
    private val minHealth get() = values.minHealth
    private val lockTarget get() = values.lockTarget
    private val switchDelay get() = values.switchDelay
    private val presetName get() = values.presetName
    private val mode get() = values.mode
    private val strength get() = values.strength
    private val maxSpeed get() = values.maxSpeed
    private val smoothing get() = values.smoothing
    private val engine get() = values.engine
    private val aiModel get() = values.aiModel
    private val aiYawMultiplier get() = values.aiYawMultiplier
    private val aiPitchMultiplier get() = values.aiPitchMultiplier
    private val aiCorrection get() = values.aiCorrection
    private val pitchAssist get() = values.pitchAssist
    private val aimPoint get() = values.aimPoint
    private val predict get() = values.predict
    private val predictSize get() = values.predictSize
    private val onlyOnCrosshair get() = values.onlyOnCrosshair
    private val turnCheck get() = values.turnCheck
    private val friction get() = values.friction
    private val deadZone get() = values.deadZone
    private val reactionDelay get() = values.reactionDelay
    private val reactionRandom get() = values.reactionRandom
    private val fadeIn get() = values.fadeIn
    private val idleChance get() = values.idleChance
    private val jitterYaw get() = values.jitterYaw
    private val jitterPitch get() = values.jitterPitch
    private val gcdFix get() = values.gcdFix
    private val gcdDither get() = values.gcdDither
    private val silent get() = values.silent
    private val moveFix get() = values.moveFix
    private val frameInterpolation get() = values.frameInterpolation
    private val pauseOnInventory get() = values.pauseOnInventory
    private val pauseOnUse get() = values.pauseOnUse
    private val chatLog get() = values.chatLog
    private val debug get() = values.debug

    // --- state ------------------------------------------------------------------------------

    /** The target the assist is helping with. Kept between ticks: the mouse handler needs it. */
    private var currentTarget: LivingEntity? = null
    private var lastSwitch = 0L
    private var reactionUntil = 0L

    /** The rotation the module handed out on the previous tick (silent: what the server got). */
    private var lastSent: Rotation? = null

    /**
     * The aim of the tick before [lastSent]. The model needs it as its "previous vector": the
     * difference between the two is how fast the aim is already turning, which is an input of
     * every [CombatSample] LiquidBounce was trained with.
     */
    private var previousAim: Rotation? = null

    /**
     * The camera as it was at the end of the previous tick, and what the module itself added to it
     * on that tick. Their difference is the player's own turn: the only input the assist must never
     * fight, and the only one friction may damp.
     */
    private var lastCameraYaw = 0f
    private var lastCameraPitch = 0f
    private var lastAssistYaw = 0f
    private var lastAssistPitch = 0f
    private var lastOwnYaw = 0f

    /**
     * The step the current tick granted to the camera, and how much of it the frames have already
     * drawn. The tick computes and quantizes the step (so the packets are exactly what they were),
     * the frames only decide *when* inside the tick the camera shows it.
     */
    private var tickStepYaw = 0f
    private var tickStepPitch = 0f
    private var drawnYaw = 0f
    private var drawnPitch = 0f

    /** [onlyOnCrosshair] keeps accepting a target until this time after the ray left the box. */
    private var crosshairGraceUntil = 0L

    /** Ticks the aim spent inside [deadZone]; used to keep a silent request alive. */
    private var stillTicks = 0
    private var nudgeSign = 1f

    /** Per-target response multiplier: two engagements never share the same speed profile. */
    private var responseJitter = 1f

    /** Rounding residual carried between ticks, so the aim never stalls on a sub-count difference. */
    private var yawCarry = 0.0
    private var pitchCarry = 0.0

    /** Why the model did not answer on the last tick that asked it, `null` while it does. */
    private var aiUnavailableReason: String? = null

    /** Last thing the model (or its fallback) did: the "Debug" line shows it as it is. */
    private var lastAiNote = "ai idle"

    /** Throttle for the chat notice about a missing model/engine. */
    private var aiNoticeAt = 0L

    private var idleLeft = 0
    private var assisted = 0
    private var debugTick = 0
    private var scanCount = 0
    private var validCount = 0
    private var lastGate = "start"
    private var lastDebugMessage = ""

    /** The entity this module is currently helping with, or `null`. Read by the target circle. */
    val target: LivingEntity?
        get() = currentTarget

    /** Short module tag: the first characters of the target name. */
    val targetTag: String
        get() = currentTarget?.name?.string?.take(8) ?: ""

    fun onEnable() {
        currentTarget = null
        lastSent = null
        previousAim = null
        lastSwitch = 0L
        reactionUntil = 0L
        idleLeft = 0
        assisted = 0
        stillTicks = 0
        nudgeSign = if (Random.nextBoolean()) 1f else -1f
        responseJitter = 1f
        yawCarry = 0.0
        pitchCarry = 0.0
        tickStepYaw = 0f
        tickStepPitch = 0f
        drawnYaw = 0f
        drawnPitch = 0f
        crosshairGraceUntil = 0L
        aiUnavailableReason = null
        lastAiNote = "ai idle"
        aiNoticeAt = 0L
        debugTick = 0
        lastGate = "start"
        lastDebugMessage = ""
        rememberCamera()
    }

    fun onDisable() {
        // Nothing to put back by hand: not silent the camera already is the aim (the module only
        // ever moves it a fraction of the way), and silent the manager drops every request whose
        // provider is not running anymore.
        currentTarget = null
        lastSent = null
        previousAim = null
        lastAssistYaw = 0f
        lastAssistPitch = 0f
        tickStepYaw = 0f
        tickStepPitch = 0f
        drawnYaw = 0f
        drawnPitch = 0f
    }

    private fun rememberCamera() {
        val me = mc.player ?: return
        lastCameraYaw = me.yRot
        lastCameraPitch = me.xRot
        lastAssistYaw = 0f
        lastAssistPitch = 0f
    }

    /* ---------------------------------------------------------------------------------------
     * Friction: the cursor is damped while it glides over a hitbox.
     *
     * This is the vanilla input path: `MouseHandler.turnPlayer` calls `LocalPlayer.turn` with the
     * accumulated cursor delta, and LiquidBounce fires [MouseRotationEvent] exactly there and calls
     * the original with the deltas it finds on the event. Scaling them is therefore the same thing
     * a slower pull of the mouse does, one line before the camera moves: no rotation of ours is
     * involved, nothing can fight the player, and the movement packet that follows is vanilla.
     *
     * Silent the camera must stay still, so there the same share is subtracted from the rotation
     * the server gets instead (see [tick]).
     * --------------------------------------------------------------------------------------- */
    fun onMouseRotation(event: MouseRotationEvent) {
        if (silent || mode == Mode.ASSIST || friction <= 0f) return

        val target = currentTarget ?: return
        if (!target.isAlive) return
        val now = System.currentTimeMillis()
        if (now < reactionUntil) return

        val me = mc.player ?: return
        if (onlyOnCrosshair && !onCrosshair(me, target)) return

        val weight = bubble(me, target)
        if (weight <= 0f) return

        // Never below zero: friction may slow the cursor down, not reverse it. The same fade as
        // the assist, so the damping does not slam on at full strength after a reaction pause.
        val scale = 1.0 - (friction / 100.0) * weight * fadeProgress(now)
        event.cursorDeltaX *= scale
        event.cursorDeltaY *= scale
    }

    /**
     * The whole pipeline of the tick: target, gates, step, quantize, hand the rotation out.
     */
    fun tick() {
        val me = mc.player
        if (me == null || mc.gameMode == null) {
            currentTarget = null
            lastSent = null
            return
        }

        // Whatever part of the previous tick's step the frames did not get to draw (low fps, a
        // paused timer, a frame landing exactly on the tick) is added now — before the player's
        // own turn is measured and before the packet layer reads the rotation. Per tick the camera
        // therefore still advances by exactly the quantized step the tick decided on, and the
        // server never sees the interpolation, only its result.
        flushPendingCamera(me)

        // The player's own turn since the last tick. Read before anything is written this tick, and
        // with our own previous step taken out, so the module never mistakes its own pull for the
        // mouse and never locks itself out with [turnCheck].
        val ownYaw = Mth.wrapDegrees((me.yRot - lastCameraYaw) - lastAssistYaw)
        val ownPitch = (me.xRot - lastCameraPitch) - lastAssistPitch
        lastOwnYaw = ownYaw
        lastCameraYaw = me.yRot
        lastCameraPitch = me.xRot
        lastAssistYaw = 0f
        lastAssistPitch = 0f

        val blocked = (pauseOnInventory && mc.gui.screen() != null) ||
                (pauseOnUse && me.isUsingItem)

        val target = if (blocked) null else findTarget(me)

        if (target == null) {
            // No target: the assist lets go. Silent that means walking the sent rotation back to
            // the camera instead of dropping the request (see [release]).
            currentTarget = null
            release(me)
            debugLine(
                "idle (blocked=$blocked scan=$scanCount valid=$validCount, last gate=$lastGate)"
            )
            return
        }

        val now = System.currentTimeMillis()
        if (target !== currentTarget) {
            // Still deciding whether to commit to this target: keep the current aim alive instead
            // of dropping the request (which would hand the camera back in a single packet).
            if (currentTarget != null && now - lastSwitch < switchDelay) {
                hold()
                return
            }
            currentTarget = target
            lastSwitch = now
            reactionUntil = now + reactionDelay + Random.nextInt(reactionRandom + 1)
            responseJitter = 0.85f + Random.nextFloat() * 0.3f
            stillTicks = 0
        }

        if (now < reactionUntil) {
            hold()
            return
        }

        // A hand does not move the mouse on every tick. Silent keeps the old behaviour: the update
        // is skipped and the manager just re-sends the same rotation. Not silent a skipped tick
        // would freeze the camera for 50 ms and the next one would catch up with a step grown
        // bigger — a visible two-beat stutter — so the tick keeps gliding at a fraction of the
        // strength instead of stopping.
        var glide = 1f
        if (idleLeft > 0) {
            idleLeft--
            if (silent) {
                hold()
                return
            }
            glide = IDLE_GLIDE
        } else if (idleChance > 0 && Random.nextInt(100) < idleChance) {
            idleLeft = Random.nextInt(1, 3)
            if (silent) {
                hold()
                return
            }
            glide = IDLE_GLIDE
        }

        // A short grace on the crosshair gate: without it the gate flips on/off on every pixel of
        // the hitbox edge, and each flip shows on the camera as a start/stop beat.
        if (onCrosshair(me, target)) {
            crosshairGraceUntil = now + CROSSHAIR_GRACE_MS
        }
        if (onlyOnCrosshair && now > crosshairGraceUntil) {
            hold()
            debugLine(
                "crosshair off the hitbox (%.2f° from the centre)".format(crosshairOffset(target))
            )
            return
        }

        val aim = currentAim(me)
        val weight = bubble(me, target)
        val fade = fadeProgress(now)
        var stepYaw = 0f
        var stepPitch = 0f
        var correcting = false

        if (mode != Mode.FRICTION) {
            val goal = goalRotation(me, target, aim)
            val deltaYaw = Mth.wrapDegrees(goal.yaw - aim.yaw)
            val deltaPitch = goal.pitch - aim.pitch

            // Never help a turn the player did not want. The gate blends instead of cutting: a
            // gentle drift away weakens the pull, a real push silences it, and nowhere along the
            // way is there a hard on/off step for the camera or the packets to stutter on.
            var gate = 1f
            if (turnCheck && deltaYaw != 0f && abs(ownYaw) >= STILL && ownYaw * deltaYaw < 0f) {
                gate = (1f - abs(ownYaw) / TURN_AWAY).coerceIn(0f, 1f)
                if (gate <= 0f) {
                    hold()
                    debugLine(
                        "player turning away (own=%.2f°, target=%.2f°)".format(ownYaw, deltaYaw)
                    )
                    return
                }
            }

            if (abs(deltaYaw) < deadZone && abs(deltaPitch) < deadZone * 0.7f) {
                // Already there. Not silent this tick writes nothing at all, which is exactly what
                // a vanilla client does when the hand rests.
                stillTick(me, aim, target)
                return
            }

            // Everything the assist hands out is gated the same way for both engines: [strength]
            // says how much help is granted, the bubble/turn/fade/idle gates say whether it is
            // granted at all.
            val allowance = weight * responseJitter * gate * glide * fade
            val ai = if (engine == Engine.AI) aiStep(me, aim, goal, target) else null

            if (ai != null) {
                // The model answers with the movement of a whole human tick, not with a share of a
                // control error, so here [strength] means "how much of that human move the assist
                // copies". On top of it a fraction of what is left ([aiCorrection]) keeps the aim
                // converging even when the model under-shoots, exactly like LiquidBounce's own AI
                // smooth does with its correction step.
                val trust = (strength / 100f) * allowance
                val correction = (aiCorrection / 100f) * allowance
                var aiYaw = ai.first * trust + deltaYaw * correction
                var aiPitch = ai.second * trust * (pitchAssist / 100f) +
                        deltaPitch * correction * (pitchAssist / 100f)

                // An assist never walks away from the goal and never walks past it: whatever the
                // model suggested, the step stays inside the correction the maths engine would
                // have made.
                if (deltaYaw != 0f && aiYaw * deltaYaw < 0f) aiYaw = 0f
                if (deltaPitch != 0f && aiPitch * deltaPitch < 0f) aiPitch = 0f
                stepYaw = if (abs(aiYaw) > abs(deltaYaw)) deltaYaw else aiYaw
                stepPitch = if (abs(aiPitch) > abs(deltaPitch)) deltaPitch else aiPitch
            } else {
                // [Engine.MATH], or the AI falling back to it: the share of the remaining angle.
                val share = (strength / 100f) * shape(deltaYaw) * allowance
                stepYaw = deltaYaw * share
                stepPitch = deltaPitch * share * (pitchAssist / 100f)
            }
            correcting = true
        }

        // Silent friction: the camera is not ours to slow down, so the same share of the player's
        // own turn is taken out of the rotation the server receives. On the server the hitbox
        // becomes sticky while the screen keeps turning at full speed, which is the whole point.
        if (silent && mode != Mode.ASSIST && friction > 0f) {
            val damp = (friction / 100f) * weight * fade
            stepYaw -= ownYaw * damp
            stepPitch -= ownPitch * damp * 0.5f
            correcting = true
        }

        if (!correcting) {
            // Friction only, and the camera is the player's: [onMouseRotation] already did the work.
            hold()
            return
        }

        // A hand is never a control loop: a trace of noise on the step, then the mouse lattice.
        stepYaw += (Random.nextFloat() - 0.5f) * jitterYaw
        stepPitch += (Random.nextFloat() - 0.5f) * jitterPitch

        applyAim(me, aim, softLimit(stepYaw, maxSpeed), softLimit(stepPitch, maxSpeed * 0.7f), target)
    }

    /* ---------------------------------------------------------------------------------------
     * Frame interpolation: the tick computes the step (and quantizes it, so per tick exactly the
     * same rotation reaches the packets as before), the frames apply it slice by slice.
     * `GameRenderer.render` runs after the tick *and* after `MouseHandler.turnPlayer`, so the
     * slice lands on top of the player's own frame movement, in the same render pass that shows
     * it. At 120 fps a 3° step is six ~0.5° slices instead of one 3° jump every 50 ms — that jump
     * was the whole "client is not as smooth as the server" problem.
     * --------------------------------------------------------------------------------------- */
    fun onRender() {
        if (silent || !frameInterpolation) return
        val me = mc.player ?: return

        val leftYaw = tickStepYaw - drawnYaw
        val leftPitch = tickStepPitch - drawnPitch
        if (leftYaw == 0f && leftPitch == 0f) return

        // How far into the current tick this frame is, 0..1. Drawing `budget * progress` minus
        // what was already drawn spreads the step evenly over however many frames this tick has:
        // high fps means more, smaller slices; at 20 fps a frame gets the whole step and the
        // behaviour degrades gracefully to the old one.
        val progress = mc.deltaTracker.getGameTimeDeltaPartialTick(true).coerceIn(0f, 1f)
        val stepYaw = tickStepYaw * progress - drawnYaw
        val stepPitch = tickStepPitch * progress - drawnPitch
        drawnYaw += stepYaw
        drawnPitch += stepPitch
        writeCamera(me, Rotation(me.yRot + stepYaw, me.xRot + stepPitch))
    }

    /**
     * Adds whatever the frames did not draw of the previous tick's step, so the camera — and with
     * it the packet layer — is exactly at the rotation the tick decided on before a new tick is
     * measured and new packets are built.
     */
    private fun flushPendingCamera(me: LocalPlayer) {
        val leftYaw = tickStepYaw - drawnYaw
        val leftPitch = tickStepPitch - drawnPitch
        tickStepYaw = 0f
        tickStepPitch = 0f
        drawnYaw = 0f
        drawnPitch = 0f
        if (leftYaw == 0f && leftPitch == 0f) return
        writeCamera(me, Rotation(me.yRot + leftYaw, me.xRot + leftPitch))
    }

    /**
     * How far the help has faded back in after the last reaction pause or target switch, 0..1.
     * A smoothstep: it leaves from a standstill and arrives without a kick, so the first ticks of
     * an engagement do not jump to full strength in a single frame.
     */
    private fun fadeProgress(now: Long): Float {
        if (fadeIn <= 0) return 1f
        val t = ((now - reactionUntil).toFloat() / fadeIn).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The aim is already inside [deadZone]. Not silent nothing is written: the camera simply does
     * not move, which is what a resting hand does. Silent the request would go stale
     * (`ticksUntilReset`) and the manager would snap the rotation back to the camera, so it is
     * re-sent unchanged, and every other tick nudged by one mouse count, because a movement packet
     * whose rotation is identical to the previous one is a flag.
     */
    private fun stillTick(me: LocalPlayer, aim: Rotation, target: LivingEntity) {
        if (!silent) {
            hold()
            return
        }
        if (++stillTicks < 2) {
            hold()
            return
        }
        stillTicks = 0
        if (Random.nextInt(100) < 20) nudgeSign = -nudgeSign
        applyAim(me, aim, nudgeSign * RotationUtil.gcd.toFloat(), 0f, target)
    }

    /** Quantizes, hands the rotation to the camera (or to [RotationManager]) and records it. */
    private fun applyAim(
        me: LocalPlayer,
        from: Rotation,
        stepYaw: Float,
        stepPitch: Float,
        target: LivingEntity
    ) {
        val desired = Rotation(from.yaw + stepYaw, (from.pitch + stepPitch).coerceIn(-89.5f, 89.5f))

        val deltaYaw: Float
        val deltaPitch: Float
        if (gcdFix) {
            val quantized = quantizeDelta(from, desired)
            deltaYaw = quantized.first
            deltaPitch = quantized.second
        } else {
            deltaYaw = Mth.wrapDegrees(desired.yaw - from.yaw)
            deltaPitch = desired.pitch - from.pitch
        }
        val next = Rotation(
            from.yaw + deltaYaw,
            (from.pitch + deltaPitch).coerceIn(-89.9f, 89.9f),
            isNormalized = gcdFix
        )

        // The model of the next tick reads this as "where the aim came from", i.e. how fast it is
        // already turning. Recorded before the write, so the value is the aim the step started at.
        previousAim = from

        if (silent) {
            RotationManager.setRotationTarget(
                plan(next, target),
                // Below KillAura/Aimbot: an aim assist that overrode a real aura would be a bug.
                RotationPriority.IMPORTANT_FOR_USAGE_1,
                owner
            )
        } else if (frameInterpolation) {
            // The tick only grants the budget; [onRender] draws it slice by slice over the
            // frames of this tick, and [flushPendingCamera] guarantees the full step has landed
            // before the next tick is measured.
            tickStepYaw = deltaYaw
            tickStepPitch = deltaPitch
            drawnYaw = 0f
            drawnPitch = 0f
        } else {
            writeCamera(me, next)
        }

        lastSent = next
        report(me, next, target)
    }

    /**
     * Writes [rotation] to the player without ever making the yaw "jump by a full turn".
     *
     * The vanilla client never sets an absolute angle on the player: `Entity.turn` *adds* the mouse
     * delta and `yRot` keeps accumulating (it can read 361.4, -720.8, ...); the server wraps it when
     * it applies the packet. Assigning a wrapped absolute value (179.9 followed by -179.9) makes the
     * field, and therefore the packet, jump by ~-360 degrees in a single tick, which is exactly what
     * Grim reports as "AimModulo360". So we always add the shortest delta, and leave `yRotO` alone
     * so the renderer interpolates the step instead of snapping the camera.
     *
     * The assist amounts ACCUMULATE ([lastAssistYaw] `+=`, not `=`): with [frameInterpolation] the
     * tick's step arrives in several slices per tick, and the tick handler strips the whole sum
     * out of the player's own turn on the next tick.
     */
    private fun writeCamera(me: LocalPlayer, rotation: Rotation) {
        val deltaYaw = Mth.wrapDegrees(rotation.yaw - me.yRot)
        val deltaPitch = rotation.pitch - me.xRot
        val beforePitch = me.xRot

        me.yRot += deltaYaw
        me.xRot = (beforePitch + deltaPitch).coerceIn(-89.9f, 89.9f)
        lastAssistYaw += deltaYaw
        lastAssistPitch += me.xRot - beforePitch
    }

    /** Re-sends the current aim unchanged: keeps the request alive without moving the crosshair. */
    private fun hold() {
        if (!silent) return
        val sent = lastSent ?: return
        // The aim did not move this tick: the model must see that as a zero velocity, not as the
        // step of the last tick stretched over the pause.
        previousAim = sent
        RotationManager.setRotationTarget(
            plan(sent, currentTarget),
            RotationPriority.IMPORTANT_FOR_USAGE_1,
            owner
        )
    }

    /**
     * Walks the aim back to the camera with the same bounded step, and only lets the request go
     * when the view is almost back. Without this the manager would hand the rotation back to the
     * camera in a single packet as soon as we stop asking for it — a jump of as many degrees as the
     * assist was away from the view, which is the one thing every rotation check reports.
     */
    private fun release(me: LocalPlayer) {
        if (!silent) {
            lastSent = null
            return
        }

        // Nothing was ever sent (or the way back already finished): nothing to release.
        val sent = lastSent ?: return
        val camera = Rotation(me.yRot, me.xRot)
        val gap = abs(Mth.wrapDegrees(camera.yaw - sent.yaw)) + abs(camera.pitch - sent.pitch)
        if (gap < max(deadZone, 0.25f)) {
            lastSent = null
            return
        }

        val stepYaw = (Mth.wrapDegrees(camera.yaw - sent.yaw) * 0.35f).coerceIn(-maxSpeed, maxSpeed)
        val stepPitch = ((camera.pitch - sent.pitch) * 0.35f).coerceIn(-maxSpeed, maxSpeed)
        val next = Rotation(sent.yaw + stepYaw, (sent.pitch + stepPitch).coerceIn(-89.5f, 89.5f))
        lastSent = next
        RotationManager.setRotationTarget(plan(next, null), RotationPriority.NOT_IMPORTANT, owner)
    }

    /**
     * The plan handed to LiquidBounce's [RotationManager]: it carries the rotation this module
     * already computed, so the manager only has to send it without touching the camera.
     *
     * The processors are intentionally empty — this module shapes and quantizes its own rotation
     * every tick (with [shape] or with the model, see [aiStep]), and letting the manager run an
     * angle smooth a second time would slow the assist down and move it off the mouse lattice.
     */
    private fun plan(rotation: Rotation, entity: LivingEntity?) = RotationTarget(
        rotation = rotation,
        entity = entity,
        ticksUntilReset = 5,
        resetThreshold = 1f,
        considerInventory = pauseOnInventory,
        movementCorrection = when (moveFix) {
            MoveFix.OFF -> MovementCorrection.OFF
            MoveFix.STRICT -> MovementCorrection.STRICT
            MoveFix.SILENT -> MovementCorrection.SILENT
        },
    )

    // --- target selection -------------------------------------------------------------------

    private fun findTarget(me: LocalPlayer): LivingEntity? {
        // Sticky: the target already being helped stays, as long as it is still valid and inside
        // the cone. Otherwise every small movement of the cursor would re-pick somebody else and
        // the aim would wobble between two enemies.
        val locked = currentTarget
        if (lockTarget && locked != null && isValidTarget(me, locked) &&
            inCone(me, locked) && locked.squaredBoxedDistanceTo(me) <= range * range
        ) {
            return locked
        }

        val level = mc.level ?: return null
        val aabb = me.boundingBox.inflate(range.toDouble())
        val candidates: List<LivingEntity> = try {
            level.getEntitiesOfClass(LivingEntity::class.java, aabb) { true }
        } catch (_: Exception) {
            // fallback: getEntities generic
            level.getEntities(null, aabb).filterIsInstance<LivingEntity>()
        }

        val view = Rotation(me.yRot, me.xRot)
        var best: LivingEntity? = null
        var bestScore = Float.MAX_VALUE
        scanCount = candidates.size
        validCount = 0

        for (e in candidates) {
            if (e === me) continue
            if (!isValidTarget(me, e)) continue
            if (e.squaredBoxedDistanceTo(me) > range * range) {
                lastGate = "out of range"
                continue
            }
            if (!throughWalls && !me.hasLineOfSight(e)) {
                lastGate = "no line of sight"
                continue
            }
            val offset = view.directionAngleTo(directionTo(me, e))
            if (offset > fov / 2f) {
                lastGate = "outside FOV (%.1f° > %.1f°)".format(offset, fov / 2f)
                continue
            }
            validCount++
            val score = when (priority) {
                Priority.FOV -> offset
                Priority.DISTANCE -> me.distanceTo(e)
                Priority.HEALTH -> e.health
                Priority.ARMOR -> -e.armorValue.toFloat()
            }
            if (score < bestScore) {
                bestScore = score
                best = e
            }
        }
        return best
    }

    /** True while [e] is inside the assist cone around where the player points. */
    private fun inCone(me: LocalPlayer, e: LivingEntity): Boolean =
        Rotation(me.yRot, me.xRot).directionAngleTo(directionTo(me, e)) <= fov / 2f

    private fun isValidTarget(me: LocalPlayer, e: LivingEntity): Boolean {
        if (e === me) return false
        if (!e.isAlive) return reject("dead")
        if (e.health <= minHealth) return reject("health <= MinHealth")
        if (e.isDeadOrDying) return reject("dying")
        if (antiBot && isBot(e)) return reject("anti-bot")
        if (!e.shouldBeAttacked()) return reject("shouldBeAttacked() = false")
        return true
    }

    /** Records why an entity was filtered out, so the "Debug" setting can show it. */
    private fun reject(reason: String): Boolean {
        lastGate = "rejected: $reason"
        return false
    }

    /** LiquidBounce's AntiBot module, plus the coloured-name NPCs. */
    private fun isBot(e: LivingEntity): Boolean =
        ModuleAntiBot.isBot(e) || e.name.string.contains("§")

    // --- rotation math ----------------------------------------------------------------------

    /** Where the crosshair would be if the assist were perfect. A goal, never a value sent as-is. */
    private fun goalRotation(me: LocalPlayer, target: LivingEntity, aim: Rotation): Rotation {
        var point = pointOn(me, target, aim)
        if (predict > 0f && predictSize > 0) {
            // The target's velocity of the last tick, not its whole movement: a small lead that
            // helps against strafing and cannot run ahead of a target that has stopped.
            val velocity = target.position().subtract(target.lastPos)
            point = point.add(velocity.scale((predict * predictSize).toDouble()))
        }
        return Rotation.lookingAt(point, me.eyePosition)
    }

    /** Straight at the middle of the hitbox: used for the cone, never for the assist itself. */
    private fun directionTo(me: LocalPlayer, target: LivingEntity): Rotation =
        Rotation.lookingAt(target.boundingBox.center, me.eyePosition)

    private fun pointOn(me: LocalPlayer, target: LivingEntity, aim: Rotation): Vec3 {
        return when (aimPoint) {
            AimPoint.HEAD -> target.eyePosition
            AimPoint.CHEST -> target.position().add(0.0, (target.bbHeight * 0.6).toDouble(), 0.0)
            AimPoint.CLOSEST -> closestPoint(me, target, aim)
        }
    }

    /**
     * The point of the hitbox the current aim is already pointing at, clamped inside the box. Aiming
     * at the part of the enemy you were already looking at is what makes the assist feel like it is
     * helping *your* aim; pulling towards the geometric centre instead makes the crosshair slide to
     * the middle of the hitbox, which every rotation check reads as a magnet.
     */
    private fun closestPoint(me: LocalPlayer, target: LivingEntity, aim: Rotation): Vec3 {
        val eyes = me.eyePosition
        val box = target.boundingBox
        val distance = eyes.distanceTo(box.center)

        // Point blank: a box that surrounds the camera would make the aim swing tens of degrees for
        // a few centimetres of movement. The centre moves smoothly with the entity.
        if (distance < 1.5) return box.center

        val hit = eyes.add(aim.directionVector.scale(distance))
        return Vec3(
            clampAxis(hit.x, box.minX + 0.12, box.maxX - 0.12),
            clampAxis(hit.y, box.minY + 0.10, box.maxY - 0.12),
            clampAxis(hit.z, box.minZ + 0.12, box.maxZ - 0.12)
        )
    }

    /** coerceIn that also survives a box thinner than the margin. */
    private fun clampAxis(value: Double, min: Double, max: Double): Double =
        if (min > max) (min + max) * 0.5 else value.coerceIn(min, max)

    /**
     * True while the crosshair ray crosses the hitbox (or the box contains the eyes). This is the
     * exact test, not an angle: it is what decides whether the assist may touch the aim at all when
     * [onlyOnCrosshair] is on.
     */
    private fun onCrosshair(me: LocalPlayer, target: LivingEntity): Boolean {
        val eyes = me.eyePosition
        val box = target.boundingBox
        if (box.contains(eyes)) return true

        // At least as far as the module may assist, so a target further away than the vanilla
        // interaction range is still "under the crosshair" for [Range] up to 6 blocks.
        val reachLength = max(me.entityInteractionRange(), range.toDouble()) + 0.5
        val reach = eyes.add(Rotation(me.yRot, me.xRot).directionVector.scale(reachLength))
        return box.clip(eyes, reach).isPresent
    }

    /**
     * The weight of the assist, 0..1.
     *
     * 1.0 while the crosshair is on the hitbox ([onCrosshair]), then a straight falloff to 0 at the
     * edge of the cone ([hitboxRadius] is where the box ends). This is the "aim assist bubble": the
     * pull is strongest exactly where the player cannot see it — they are already aiming at the
     * target — and vanishes at the distance at which it would become a snap.
     */
    private fun bubble(me: LocalPlayer, target: LivingEntity): Float {
        // The aimbot picked the target itself, so there is no "the crosshair is already on it"
        // to reward and no distance from the aim to fade over: the pull is full, always.
        if (values.autoAim) return 1f
        if (onCrosshair(me, target)) return 1f

        val offset = crosshairOffset(target)
        val radius = hitboxRadius(me, target)
        if (offset <= radius) return 1f
        val edge = fov / 2f
        if (edge <= radius) return 0f
        return (1f - (offset - radius) / (edge - radius)).coerceIn(0f, 1f)
    }

    /** Angle between the crosshair and the direction towards [target]'s hitbox, in degrees. */
    private fun crosshairOffset(target: LivingEntity): Float =
        RotationUtil.crosshairAngleToEntity(target)

    /** How wide the hitbox is, seen from the eyes, in degrees. */
    private fun hitboxRadius(me: LocalPlayer, target: LivingEntity): Float {
        val box = target.boundingBox
        val distance = me.eyePosition.distanceTo(box.center).coerceAtLeast(0.1)
        val half = max(box.maxX - box.minX, box.maxY - box.minY) * 0.5
        return Math.toDegrees(atan2(half, distance)).toFloat()
    }

    /**
     * Bends the response curve, it can never push the share above 1.
     *
     * A big error is corrected at (almost) the full [strength], a small one gently: that is Fitts'
     * law, and it is the difference between an assist and a spring that keeps oscillating around
     * the target. Only [Engine.MATH] uses it; the model in [Engine.AI] brings its own shape.
     */
    private fun shape(deltaYaw: Float): Float {
        val t = (abs(deltaYaw) / 12f).coerceIn(0f, 1f)
        return when (smoothing) {
            Smoothing.LINEAR -> 1f
            Smoothing.BEZIER -> 0.55f + 0.45f * (t * t * (3f - 2f * t))
            Smoothing.SIGMOID -> 0.45f + 0.55f / (1f + exp(-6f * (t - 0.45f)))
        }
    }

    /**
     * Saturates [value] softly at [limit]: proportional below the knee, asymptotically bounded
     * above it, and never exactly equal to the limit. It behaves like `coerceIn` for small values,
     * so the assist feels the same, but the step magnitude can never get stuck — a step pinned to
     * the same ceiling on every tick is what rotation checks count as "constant".
     */
    private fun softLimit(value: Float, limit: Float): Float =
        if (limit <= 0f) 0f else limit * tanh(value / limit)

    /** The rotation the aim is currently at: the camera, or in silent mode what the server got. */
    private fun currentAim(me: LocalPlayer): Rotation =
        if (silent) {
            lastSent ?: RotationManager.currentRotation ?: Rotation(me.yRot, me.xRot)
        } else {
            Rotation(me.yRot, me.xRot)
        }

    // --- the trained model ------------------------------------------------------------------

    /**
     * The next mouse movement of a human in this exact situation, as (yaw, pitch) in degrees, or
     * `null` when the model cannot answer (engine still downloading, model missing, bad output) —
     * in which case the tick falls back to the maths engine, it does not stop.
     *
     * The sample is built exactly like LiquidBounce's own `AiAngleSmooth` builds it, because these
     * models were trained on that layout:
     *
     *  - [CombatSample.currentVector] — where the aim is now ([aim]): the camera, or in silent mode
     *    the rotation the server last got;
     *  - [CombatSample.previousVector] — where it was the tick before ([previousAim]): the
     *    difference between the two is how fast the aim is already turning;
     *  - [CombatSample.targetVector] — the goal ([goalRotation], with the same [aimPoint] and
     *    [predict] the maths engine uses), so the model knows what the crosshair is reaching for;
     *  - the speed of both entities, the distance and [LivingEntity.hurtTime] — the context the
     *    model was trained with.
     *
     * The answer is *not* used as-is: it is a whole human tick, so [tick] scales it by the
     * assist's own gates ([strength], [bubble], [turnCheck], [fadeIn], idle), adds the
     * [aiCorrection] of what is left, and never lets it walk away from the goal or past it.
     */
    private fun aiStep(
        me: LocalPlayer,
        aim: Rotation,
        goal: Rotation,
        target: LivingEntity
    ): Pair<Float, Float>? {
        if (!DeepLearningEngine.isInitialized) {
            return aiUnavailable("deep learning not initialised")
        }
        val model = activeModel() ?: return aiUnavailable("no model '${aiModel.tag}' loaded")
        val previous = previousAim ?: aim

        // The predict can throw (closed model, translator failure) and must never reach the tick:
        // the assist keeps the maths engine, and the debug line says which model failed.
        val prediction = runCatching {
            model.predict(
                CombatSample(
                    currentVector = aim.directionVector,
                    previousVector = previous.directionVector,
                    targetVector = goal.directionVector,
                    velocityDelta = previous.rotationDeltaTo(aim).toVec2f(),
                    playerDiff = me.position().subtract(me.lastPos),
                    targetDiff = target.position().subtract(target.lastPos),
                    distance = me.squaredBoxedDistanceTo(target).toFloat(),
                    hurtTime = target.hurtTime,
                    age = 0
                ).asInput
            )
        }
        val output = prediction.getOrNull()
            ?: return aiUnavailable(prediction.exceptionOrNull()?.javaClass?.simpleName ?: "model error")

        if (output.size < 2) return aiUnavailable("model returned ${output.size} values")
        val yaw = output[0] * aiYawMultiplier
        val pitch = output[1] * aiPitchMultiplier
        if (!yaw.isFinite() || !pitch.isFinite()) return aiUnavailable("model returned NaN")

        aiUnavailableReason = null
        lastAiNote = "ai ${aiModel.tag} %.2f°/%.2f°".format(yaw, pitch)
        return yaw to pitch
    }

    /**
     * The selected bundled model. LiquidBounce merges the models shipped in its JAR with the ones
     * in its `deeplearning/models` folder (a user copy overrides the bundled one), so the name is
     * looked up instead of being instantiated here; the client's active model is the fallback.
     */
    private fun activeModel(): TwoDimensionalRegressionModel? = runCatching {
        val group = ModelManager.models
        group.modes.firstOrNull { model -> model.tag.equals(aiModel.tag, ignoreCase = true) }
            ?: group.activeMode
    }.getOrNull()

    /**
     * The model did not answer this tick. The assist keeps working with [smoothing] instead: a
     * model that is still being downloaded (LiquidBounce fetches the engine on start-up) or a user
     * model that was deleted must not leave the module dead. The reason goes to chat, throttled,
     * because a silently degraded engine is worse than a slow one.
     */
    private fun aiUnavailable(reason: String): Pair<Float, Float>? {
        if (aiUnavailableReason != reason) lastAiNote = "fallback: $reason"
        aiUnavailableReason = reason

        val now = System.currentTimeMillis()
        if (now - aiNoticeAt >= AI_NOTICE_MS) {
            aiNoticeAt = now
            chat("§7[" + owner.name + "] §cAI engine: $reason §7- using ${smoothing.tag}")
        }
        return null
    }

    // --- mouse lattice ----------------------------------------------------------------------

    /**
     * Turns the delta from [from] to [desired] into one a real mouse could have produced.
     *
     * The client moves the player by whole mouse counts: `step = (float)(f³ * 8) * 0.15f` degrees
     * per count, with `f = sensitivity * 0.6 + 0.2`. That value is exactly [RotationUtil.gcd], so:
     *
     *  - we round the DELTA from the last aimed rotation, never the absolute angle (rounding
     *    absolute values to a global grid is what produces "impossible sensitivity" flags);
     *  - the caller marks the result [Rotation.isNormalized] so the manager does not round it again
     *    from a different base, which would move it off the lattice;
     *  - with [gcdDither] the residual is carried to the next tick and the rounding boundary
     *    wobbles, because a real hand does not always land on the nearest count. A repeating delta
     *    sequence ("+3 counts, +3 counts, ...") is a classic GCD/aimbot pattern.
     *
     * The quantized ticks are what the packets get; with [frameInterpolation] the camera only
     * decides *when inside the tick* it shows the step, never a different step.
     */
    private fun quantizeDelta(from: Rotation, desired: Rotation): Pair<Float, Float> {
        val deltaYaw = RotationUtil.angleDifference(desired.yaw, from.yaw).toDouble()
        val deltaPitch = (desired.pitch - from.pitch).toDouble()

        val step = RotationUtil.gcd
        if (step < 1.0e-6) return deltaYaw.toFloat() to deltaPitch.toFloat()

        val rawYaw = deltaYaw / step + (if (gcdDither) yawCarry else 0.0)
        val rawPitch = deltaPitch / step + (if (gcdDither) pitchCarry else 0.0)

        val countsYaw: Int
        val countsPitch: Int

        if (gcdDither) {
            countsYaw = handRound(rawYaw)
            countsPitch = handRound(rawPitch)
            yawCarry = (rawYaw - countsYaw).coerceIn(-0.95, 0.95)
            pitchCarry = (rawPitch - countsPitch).coerceIn(-0.95, 0.95)
        } else {
            countsYaw = round(rawYaw).toInt()
            countsPitch = round(rawPitch).toInt()
        }

        // pitch is clamped by the caller on the absolute value, so the +-90 boundary that servers
        // treat as an untouched pitch is never crossed here either
        return (countsYaw * step).toFloat() to (countsPitch * step).toFloat()
    }

    /** Nearest whole mouse count, with the decision boundary wobbling like a human hand. */
    private fun handRound(counts: Double): Int {
        val wobble = Random.nextDouble(-DITHER_WOBBLE, DITHER_WOBBLE)
        return kotlin.math.floor(counts + 0.5 + wobble).toInt()
    }

    // --- debug ------------------------------------------------------------------------------

    private fun report(me: LocalPlayer, aimed: Rotation, target: LivingEntity) {
        assisted++
        if (chatLog && assisted % 100 == 0) {
            chat("§7[" + owner.name + "] §f$assisted corrections" +
                    (if (presetName.isEmpty()) "" else " (preset $presetName)"))
        }
        if (!debug) return

        debugLine(
            "assisting %s dist=%.2f offset=%.2f° bubble=%.2f → yaw=%.1f pitch=%.1f (silent=%s, preset=%s) ".format(
                target.name.string, target.distanceTo(me), crosshairOffset(target),
                bubble(me, target), aimed.yaw, aimed.pitch, silent,
                if (presetName.isEmpty()) "-" else presetName
            ) + "§7strength=%.0f%% max=%.1f° own=%.2f°".format(
                strength, maxSpeed, lastOwnYaw
            ) + (if (engine == Engine.AI) " §7$lastAiNote" else "") + warnings()
        )
    }

    /** Prints the internal state once per second when the "Debug" setting is on. */
    private fun debugLine(message: String) {
        if (!debug) return
        if (++debugTick % 20 != 0) return
        if (message == lastDebugMessage) return
        lastDebugMessage = message
        chat("§7[" + owner.name + "] §f$message")
    }

    /**
     * Settings that defeat the "assist, not aimbot" behaviour, reported next to the debug line so a
     * wrong configuration is visible instead of being guessed from the anticheat log.
     */
    private fun warnings(): String = buildString {
        if (strength > 80f) append(" §cStrength>80 (this is an aimbot)")
        if (maxSpeed > 15f) append(" §cMaxSpeed>15 (packets can carry a snap)")
        if (!onlyOnCrosshair && fov > 45f) append(" §cOnlyOnCrosshair off with Fov>45 (wide magnet)")
        if (!gcdFix) append(" §cGcdFix off (deltas off the mouse lattice)")
        if (engine == Engine.AI && aiUnavailableReason != null) {
            append(" §cAI fallback (${aiUnavailableReason})")
        }
    }
}
