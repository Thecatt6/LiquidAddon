package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine
import net.ccbluex.liquidbounce.deeplearn.ModelManager
import net.ccbluex.liquidbounce.deeplearn.data.CombatSample
import net.ccbluex.liquidbounce.deeplearn.models.TwoDimensionalRegressionModel
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
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
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ModuleAimbotAI — silent aimbot with human-like rotations.
 *
 * Pipeline every tick: [findTarget] -> [goalRotation] -> [stepTowards] -> [quantizeToMouseCounts]
 * -> `RotationManager.setRotationTarget`. Every step is bounded by [maxStep] degrees, so the aim
 * can never jump inside a single packet (Vulcan's "packet diff" flag) and the movement stays smooth.
 * -> `RotationManager.setRotationTarget`. The module never clicks; it only aims.
 *
 * Presets are full profiles, not just a speed: picking one writes every slider below, so the
 * ClickGUI always shows what the module is really doing. `Custom` leaves the sliders alone.
 * `Undetectable` is the profile tuned for Vulcan "Aim (Rotation)" / packet "perfectrotation".
 */
object ModuleAimbotAI : ClientModule("Aimbot AI", Categories.LIQUIDADDON) {

    /** How far (in mouse counts) the rounding boundary may wobble when [gcdDither] is on. */
    private const val DITHER_WOBBLE = 0.18

    /** Steps below this many mouse counts (~3 degrees) are too slow for the "constant" counter. */
    private const val FAST_STEP_COUNTS = 20

    enum class Priority(override val tag: String) : Tagged {
        DISTANCE("Distance"), HEALTH("Health"), FOV("Fov"), ANGLE("Angle"), ARMOR("Armor")
    }
    enum class RotationMode(override val tag: String) : Tagged {
        SMOOTH("Smooth"), AI("AI"), SNAP("Snap")
    }
    enum class Smoothing(override val tag: String) : Tagged {
        BEZIER("Bezier"), SIGMOID("Sigmoid"), LINEAR("Linear")
    }
    enum class MoveFix(override val tag: String) : Tagged {
        OFF("Off"), STRICT("Strict"), SILENT("Silent")
    }
    enum class AimPoint(override val tag: String) : Tagged {
        HEAD("Head"), CHEST("Chest"), CLOSEST("Closest"), RANDOMIZED("Randomized")
    }
    enum class AiModel(override val tag: String) : Tagged {
        MODEL_19KC8KP("19KC8KP"), MODEL_21KC11KP("21KC11KP")
    }
    enum class Preset(override val tag: String) : Tagged {
        CUSTOM("Custom"), BLATANT("Blatant"), LEGIT("Legit"), GHOST("Ghost"), CLOSET("Closet"),

        /**
         * Tuned against servers that run Vulcan's "Aim (Rotation)" and the packet
         * "perfectrotation" check: the aim never sits on the exact hitbox centre, it drifts,
         * it ignores sub-degree corrections and it sometimes does not move at all.
         */
        UNDETECTABLE("Undetectable")
    }

    /**
     * A preset is a full set of settings, not just a speed. Picking one writes the values below
     * into the module's own values, so what you see in the ClickGUI is what the module does.
     * [Preset.CUSTOM] changes nothing and leaves your sliders alone.
     */
    @Suppress("LongParameterList")
    private class Profile(
        val rotationMode: RotationMode,
        val smoothing: Smoothing,
        val smoothSpeed: Float,
        val maxStep: Float,
        val aimPoint: AimPoint,
        val aimRandomize: Float,
        val predict: Float,
        val predictSize: Int,
        val overshoot: Float,
        val jitterYaw: Float,
        val jitterPitch: Float,
        val tremor: Float,
        val perlinScale: Float,
        val randomizeOffset: Float,
        val aimBias: Float,
        val drift: Float,
        val deadZone: Float,
        val idleChance: Int,
        val gcdFix: Boolean,
        val gcdDither: Boolean,
        val reactionDelay: Int,
        val reactionRandom: Int,
        val switchDelay: Int,
        val range: Float,
        val fov: Float,
        val silent: Boolean,
        val moveFix: MoveFix,
        val silentFov: Boolean,
        val throughWalls: Boolean,
        val checkVisible: Boolean,
        val antiBot: Boolean
    )

    private val rangeValue = float("Range", 3.3f, 1f..6f, "blocks")
    private val range by rangeValue
    private val fovValue = float("FOV", 70f, 10f..180f, "°")
    private val fov by fovValue
    private val priority by enumChoice("Priority", Priority.DISTANCE)
    private val throughWallsValue = boolean("ThroughWalls", false)
    private val throughWalls by throughWallsValue
    private val targetFriends by boolean("TargetFriends", false)
    private val targetInvisible by boolean("TargetInvisible", false)
    private val targetDead by boolean("TargetDead", false)
    private val minHealth by int("MinHealth", 0, 0..20, "HP")
    private val antiBotValue = boolean("AntiBot", true)
    private val antiBot by antiBotValue
    private val lockTarget by boolean("LockTarget", true)
    private val switchDelayValue = int("SwitchDelay", 600, 0..2000, "ms")
    private val switchDelay by switchDelayValue

    private val presetValue = enumChoice("Preset", Preset.UNDETECTABLE)
    private val preset by presetValue

    private val rotationModeValue = enumChoice("RotationMode", RotationMode.SMOOTH)
    private val rotationMode by rotationModeValue
    private val smoothingValue = enumChoice("Smoothing", Smoothing.BEZIER)
    private val smoothing by smoothingValue
    private val aimPointValue = enumChoice("AimPoint", AimPoint.CLOSEST)
    private val aimPoint by aimPointValue
    private val aimRandomizeValue = float("AimRandomize", 0.05f, 0f..1f)
    private val aimRandomize by aimRandomizeValue
    private val predictValue = float("Predict", 0.2f, 0f..1f)
    private val predict by predictValue
    private val predictSizeValue = int("PredictSize", 2, 0..10, "ticks")
    private val predictSize by predictSizeValue

    private val smoothSpeedValue = float("SmoothSpeed", 0.85f, 0.1f..3f)
    private val smoothSpeed by smoothSpeedValue

    /**
     * Hard cap on how much the aim may turn in a single tick, in degrees.
     * This is what keeps every movement packet smooth: a jump like Vulcan's "packet diff=25"
     * becomes impossible, the turn is spread over several ticks instead.
     */
    private val maxStepValue = float("MaxStep", 4.5f, 0.5f..30f, "°")
    private val maxStep by maxStepValue
    private val flickTicks by int("FlickTicks", 3, 1..10, "ticks")
    private val aiModel by enumChoice("AIModel", AiModel.MODEL_19KC8KP)
    private val aiYawMultiplier by float("AIYawMultiplier", 1.5f, 0.5f..3f)
    private val aiPitchMultiplier by float("AIPitchMultiplier", 1f, 0.5f..3f)
    private val aiCorrection by int("AICorrection", 3, 0..30, "%")
    private val aiMaxTicks by int("AIMaxTicks", 8, 1..20, "ticks")

    private val reactionDelayValue = int("ReactionDelay", 180, 0..300, "ms")
    private val reactionDelay by reactionDelayValue
    private val reactionRandomValue = int("ReactionRandom", 100, 0..200, "ms")
    private val reactionRandom by reactionRandomValue
    private val overshootValue = float("Overshoot", 0.03f, 0f..0.5f)
    private val overshoot by overshootValue
    private val jitterYawValue = float("JitterYaw", 0.3f, 0f..2f, "°")
    private val jitterYaw by jitterYawValue
    private val jitterPitchValue = float("JitterPitch", 0.2f, 0f..2f, "°")
    private val jitterPitch by jitterPitchValue
    private val tremorValue = float("Tremor", 0.05f, 0f..1f, "°")
    private val tremor by tremorValue
    private val perlinScaleValue = float("PerlinScale", 0.15f, 0f..1f)
    private val perlinScale by perlinScaleValue
    private val gcdFixValue = boolean("GCDFix", true)
    private val gcdFix by gcdFixValue
    private val gcdDitherValue = boolean("GCDDither", true)
    private val gcdDither by gcdDitherValue
    private val randomizeOffsetValue = float("RandomizeOffset", 0.03f, 0f..0.5f)
    private val randomizeOffset by randomizeOffsetValue
    // Human-looking aim: a real crosshair never sits on the exact hitbox centre and its error
    // moves slowly instead of being white noise around zero.
    private val aimBiasValue = float("AimBias", 2f, 0f..5f, "°")
    private val aimBias by aimBiasValue
    private val driftAmountValue = float("Drift", 0.8f, 0f..3f, "°")
    private val driftAmount by driftAmountValue
    private val deadZoneValue = float("DeadZone", 0.4f, 0f..3f, "°")
    private val deadZone by deadZoneValue
    private val idleChanceValue = int("IdleChance", 15, 0..40, "%")
    private val idleChance by idleChanceValue
    private val keepSprint by boolean("KeepSprint", true)

    private val silentValue = boolean("Silent", true)
    private val silent by silentValue
    private val moveFixValue = enumChoice("MoveFix", MoveFix.SILENT)
    private val moveFix by moveFixValue
    private val silentFovValue = boolean("SilentFOV", true)
    private val silentFov by silentFovValue
    private val onSneak by boolean("OnSneak", true)
    private val onSprint by boolean("OnSprint", true)
    private val checkVisibleValue = boolean("CheckVisible", true)
    private val checkVisible by checkVisibleValue

    private val autoBlock by boolean("AutoBlock", false)
    private val pauseOnInventory by boolean("PauseOnInventory", true)
    private val pauseOnUse by boolean("PauseOnUse", false)
    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

    /** The values each preset writes. Add or tweak here; the GUI follows automatically. */
    private fun profileOf(preset: Preset): Profile? = when (preset) {
        // No profile: the sliders stay as they are.
        Preset.CUSTOM -> null

        // Fast and obvious, only for testing on your own world.
        Preset.BLATANT -> Profile(
            rotationMode = RotationMode.SNAP, smoothing = Smoothing.LINEAR, smoothSpeed = 3f, maxStep = 30f,
            aimPoint = AimPoint.CLOSEST, aimRandomize = 0.25f, predict = 0.35f, predictSize = 3,
            overshoot = 0.1f, jitterYaw = 0.4f, jitterPitch = 0.3f, tremor = 0.15f, perlinScale = 0.3f,
            randomizeOffset = 0.15f, aimBias = 0.2f, drift = 0.1f, deadZone = 0.05f, idleChance = 0,
            gcdFix = true, gcdDither = false, reactionDelay = 60, reactionRandom = 40,
            switchDelay = 150, range = 4.2f, fov = 90f, silent = true, moveFix = MoveFix.SILENT,
            silentFov = false, throughWalls = false, checkVisible = true, antiBot = true
        )

        // Playable on normal servers, noticeably assisted.
        Preset.LEGIT -> Profile(
            rotationMode = RotationMode.AI, smoothing = Smoothing.BEZIER, smoothSpeed = 1.2f, maxStep = 6f,
            aimPoint = AimPoint.CLOSEST, aimRandomize = 0.1f, predict = 0.25f, predictSize = 2,
            overshoot = 0.06f, jitterYaw = 0.35f, jitterPitch = 0.25f, tremor = 0.1f, perlinScale = 0.2f,
            randomizeOffset = 0.06f, aimBias = 1.2f, drift = 0.5f, deadZone = 0.25f, idleChance = 8,
            gcdFix = true, gcdDither = true, reactionDelay = 140, reactionRandom = 80,
            switchDelay = 400, range = 3.6f, fov = 80f, silent = true, moveFix = MoveFix.SILENT,
            silentFov = true, throughWalls = false, checkVisible = true, antiBot = true
        )

        // Slow and soft: for servers with strict rotation statistics.
        Preset.GHOST -> Profile(
            rotationMode = RotationMode.SMOOTH, smoothing = Smoothing.BEZIER, smoothSpeed = 0.6f, maxStep = 3f,
            aimPoint = AimPoint.CLOSEST, aimRandomize = 0.05f, predict = 0.15f, predictSize = 2,
            overshoot = 0.02f, jitterYaw = 0.25f, jitterPitch = 0.2f, tremor = 0.05f, perlinScale = 0.15f,
            randomizeOffset = 0.03f, aimBias = 2.5f, drift = 1f, deadZone = 0.5f, idleChance = 20,
            gcdFix = true, gcdDither = true, reactionDelay = 220, reactionRandom = 120,
            switchDelay = 800, range = 3.2f, fov = 65f, silent = true, moveFix = MoveFix.SILENT,
            silentFov = true, throughWalls = false, checkVisible = true, antiBot = true
        )

        // Only the final correction is assisted, everything else is yours.
        Preset.CLOSET -> Profile(
            rotationMode = RotationMode.SMOOTH, smoothing = Smoothing.SIGMOID, smoothSpeed = 0.8f, maxStep = 4.5f,
            aimPoint = AimPoint.CLOSEST, aimRandomize = 0.05f, predict = 0.1f, predictSize = 2,
            overshoot = 0.03f, jitterYaw = 0.3f, jitterPitch = 0.2f, tremor = 0.05f, perlinScale = 0.15f,
            randomizeOffset = 0.03f, aimBias = 1.8f, drift = 0.8f, deadZone = 0.4f, idleChance = 15,
            gcdFix = true, gcdDither = true, reactionDelay = 200, reactionRandom = 110,
            switchDelay = 650, range = 3.3f, fov = 70f, silent = true, moveFix = MoveFix.SILENT,
            silentFov = true, throughWalls = false, checkVisible = true, antiBot = true
        )

        // The configuration for Vulcan "Aim (Rotation)" / packet "perfectrotation".
        Preset.UNDETECTABLE -> Profile(
            rotationMode = RotationMode.SMOOTH, smoothing = Smoothing.BEZIER, smoothSpeed = 0.85f, maxStep = 4.5f,
            aimPoint = AimPoint.CLOSEST, aimRandomize = 0.05f, predict = 0.15f, predictSize = 2,
            overshoot = 0.03f, jitterYaw = 0.3f, jitterPitch = 0.2f, tremor = 0.05f, perlinScale = 0.15f,
            randomizeOffset = 0.03f, aimBias = 2f, drift = 0.8f, deadZone = 0.4f, idleChance = 15,
            gcdFix = true, gcdDither = true, reactionDelay = 180, reactionRandom = 100,
            switchDelay = 600, range = 3.3f, fov = 70f, silent = true, moveFix = MoveFix.SILENT,
            silentFov = true, throughWalls = false, checkVisible = true, antiBot = true
        )
    }

    /**
     * Writes [Preset] into the module's values, so the GUI always shows what is actually being
     * used. Called whenever the preset changes; [Preset.CUSTOM] does nothing.
     */
    private fun applyPreset(preset: Preset) {
        val p = profileOf(preset) ?: return

        rotationModeValue.set(p.rotationMode)
        smoothingValue.set(p.smoothing)
        smoothSpeedValue.set(p.smoothSpeed)
        maxStepValue.set(p.maxStep)
        aimPointValue.set(p.aimPoint)
        aimRandomizeValue.set(p.aimRandomize)
        predictValue.set(p.predict)
        predictSizeValue.set(p.predictSize)
        overshootValue.set(p.overshoot)
        jitterYawValue.set(p.jitterYaw)
        jitterPitchValue.set(p.jitterPitch)
        tremorValue.set(p.tremor)
        perlinScaleValue.set(p.perlinScale)
        randomizeOffsetValue.set(p.randomizeOffset)
        aimBiasValue.set(p.aimBias)
        driftAmountValue.set(p.drift)
        deadZoneValue.set(p.deadZone)
        idleChanceValue.set(p.idleChance)
        gcdFixValue.set(p.gcdFix)
        gcdDitherValue.set(p.gcdDither)
        reactionDelayValue.set(p.reactionDelay)
        reactionRandomValue.set(p.reactionRandom)
        switchDelayValue.set(p.switchDelay)
        rangeValue.set(p.range)
        fovValue.set(p.fov)
        silentValue.set(p.silent)
        moveFixValue.set(p.moveFix)
        silentFovValue.set(p.silentFov)
        throughWallsValue.set(p.throughWalls)
        checkVisibleValue.set(p.checkVisible)
        antiBotValue.set(p.antiBot)

        if (debug && mc.player != null) {
            chat("§7[Aimbot] §fpreset ${preset.tag} applied")
        }
    }

    init {
        // Switching preset rewrites the sliders; CUSTOM leaves them alone. Nothing is applied here:
        // the declared defaults below already are the UNDETECTABLE profile, and firing value events
        // while the module object is still being constructed is not safe.
        presetValue.onChanged { applyPreset(it) }
    }

    /**
     * The rotation that was actually handed to the server on the previous tick. Every step is
     * measured from here (not from the camera and not from the manager), so the sequence of
     * sent rotations is continuous even in silent mode.
     */
    private var lastSent: Rotation? = null

    // Angular velocity of the aim in degrees per tick. The stepper accelerates towards the wanted
    // speed instead of jumping to it, which gives the turn an ease-in / ease-out profile.
    private var velYaw = 0f
    private var velPitch = 0f

    // Per-target response multiplier: two engagements should never share the same speed profile.
    private var responseJitter = 1f

    // Phase of the "breathing" motion used while the aim sits on the target.
    private var swayPhase = 0f

    // Consecutive ticks in which an axis did not move at all. Matrix scores the ratio between
    // the yaw and pitch jiff values: a pitch that stays exactly still for a whole window makes
    // that ratio infinite on every sample (AimStatisticsCheck "Aim IQR").
    private var stillYawTicks = 0
    private var stillPitchTicks = 0

    // Direction of the "keep moving" nudge. A hand that rests does not alternate +1/-1: it drifts
    // in one direction and reverses now and then. Alternating would itself be a pattern
    // (Matrix AimBasicCheck "pattern(random)" counts small increases and decreases).
    private var nudgeYawSign = 1f
    private var nudgePitchSign = 1f

    // Magnitude of the last sent step, in mouse counts, and the "gust" that modulates the speed.
    // The RNN scores the sequence of speeds (sqrt(yaw^2 + pitch^2)) and counts how often a fast
    // movement keeps almost the same speed as the tick before (< 0.02 degrees while turning more
    // than 3): that count is one of its "robot" counters.
    private var lastStepCounts = 0
    private var speedGust = 1f
    private var repeatedSpeed = 0

    // How much of the step limit is currently unlocked (0 = corrective, 1 = full flick).
    private var flickAmount = 0f

    // Chaotic source for the speed modulation (logistic map) and the current "flinch" countdown.
    // Its job is that no two steps have the same size, so the sequence of magnitudes never
    // repeats a pattern.
    private var chaos = 0.37f
    private var flinchLeft = 0

    // Last yaw deltas that were actually sent, used to reproduce Matrix's rank metric locally.
    private val recentYawDeltas = ArrayDeque<Float>()

    private var currentTarget: LivingEntity? = null
    private var lastTargetSwitch = 0L
    private var reactionUntil = 0L
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var beforeYaw = 0f
    private var beforePitch = 0f
    private var perlinTick = 0
    private var hitCount = 0

    // --- debug bookkeeping (only used when the "Debug" setting is on) ---
    // rounding residual carried between ticks so the aim never stalls on a sub-count difference
    private var yawCarry = 0.0
    private var pitchCarry = 0.0

    // Per-target aim bias (re-rolled on every target switch) and the slow hand drift.
    private var biasFor: LivingEntity? = null
    private var biasYaw = 0f
    private var biasPitch = 0f
    private var driftYaw = 0f
    private var driftPitch = 0f
    // "micro-pauses": ticks in which a human simply does not move the mouse
    private var idleLeft = 0

    private var debugTick = 0
    private var scanCount = 0
    private var validCount = 0
    private var lastGate = "start"
    private var lastDebugMessage = ""

    override val tag: String
        get() = currentTarget?.let { "${it.name.string.take(8)} ${String.format("%.1f", it.health)}" } ?: ""

    override fun onEnabled() {
        currentTarget = null
        lastTargetSwitch = 0L
        reactionUntil = 0L
        hitCount = 0
        perlinTick = 0
        debugTick = 0
        lastGate = "start"
        yawCarry = 0.0
        pitchCarry = 0.0
        lastSent = null
        velYaw = 0f
        velPitch = 0f
        swayPhase = Random.nextFloat() * 6.283f
        stillYawTicks = 0
        stillPitchTicks = 0
        nudgeYawSign = if (Random.nextBoolean()) 1f else -1f
        nudgePitchSign = if (Random.nextBoolean()) 1f else -1f
        lastStepCounts = 0
        speedGust = 1f
        repeatedSpeed = 0
        flickAmount = 0f
        chaos = 0.2f + Random.nextFloat() * 0.6f
        flinchLeft = 0
        recentYawDeltas.clear()
        biasFor = null
        biasYaw = 0f
        biasPitch = 0f
        driftYaw = 0f
        driftPitch = 0f
        idleLeft = 0
        resetRotationMemory()
    }

    override fun onDisabled() {
        // Nothing to restore by hand: the module never writes the camera, and RotationManager's
        // RequestHandler drops every request whose provider is not running anymore.
        currentTarget = null
        lastSent = null
        velYaw = 0f
        velPitch = 0f
    }

    private fun resetRotationMemory() {
        val me = mc.player ?: return
        lastYaw = me.yRot
        lastPitch = me.xRot
        beforeYaw = me.yRotO
        beforePitch = me.xRotO
    }

    /*
     * The module used to fake the silent rotation by hand (beginSpoof/endSpoof on the movement
     * events, plus its own input remapping). That work is already done by RotationManager through
     * `MovementCorrection`, so doing it twice was writing the camera on every movement packet —
     * one more source of visible stutter. `MoveFix` is now only mapped onto MovementCorrection in
     * [rotationPlan].
     */

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player ?: return@handler
        if (mc.gameMode == null) {
            currentTarget = null
            lastSent = null
            return@handler
        }

        // Settings that pause the aim. They must NOT make the rotation request disappear: the
        // manager would drop it and the next packet would carry the camera angle again — a jump of
        // as many degrees as the aim was away from the view, which is exactly what Vulcan reports
        // as "Aim (Rotation) ... diff=25". The aim is walked back instead (see [releaseAim]).
        val blocked = (pauseOnInventory && isInventoryOpen()) ||
            (pauseOnUse && me.isUsingItem) ||
            (!onSneak && me.isShiftKeyDown) ||
            (!onSprint && me.isSprinting)

        val target = if (blocked) null else findTarget(me)

        if (target == null) {
            if (!lockTarget) currentTarget = null
            releaseAim(me)
            debugState("no target (blocked=$blocked scan=$scanCount valid=$validCount, last gate=$lastGate)")
            return@handler
        }

        val now = System.currentTimeMillis()
        if (currentTarget == null || !isValidTarget(me, target)) {
            if (now - lastTargetSwitch < switchDelay) {
                // Still deciding whether to commit to this target: keep the current aim alive.
                holdAim(me, target)
                return@handler
            }
            lastTargetSwitch = now
            reactionUntil = now + reactionDelay + Random.nextInt(reactionRandom + 1)
        }
        if (target !== biasFor) {
            biasFor = target
            rollAimBias(target)
        }
        currentTarget = target

        if (now < reactionUntil) {
            holdAim(me, target)
            return@handler
        }

        // A hand does not move the mouse on every tick: holding the same rotation is what that
        // looks like. Skipping the update instead makes the angle grow and the next step burst.
        if (idleLeft > 0) {
            idleLeft--
            holdAim(me, target)
            return@handler
        }
        if (idleChance > 0 && Random.nextInt(100) < idleChance) {
            idleLeft = Random.nextInt(1, 3)
            holdAim(me, target)
            return@handler
        }

        val sent = lastSent ?: currentAim(me)
        val goal = goalRotation(me, target)
        if (silentFov && !isInFov(me, goal)) {
            holdAim(me, target)
            return@handler
        }

        // One bounded step towards the goal: the only place the aim is allowed to move.
        sendAim(me, stepTowards(sent, goal), target, sent, goal, report = true)
    }

    /** True when a GUI is open, so the aim has to pause like a player's would. */
    private fun isInventoryOpen(): Boolean {
        // `screen` is the field name in 26.3; the older name is kept as a fallback.
        return try {
            val field = Minecraft::class.java.getDeclaredField("screen").apply { isAccessible = true }
            field.get(Minecraft.getInstance()) != null
        } catch (_: Exception) {
            try {
                val field = Minecraft::class.java.getDeclaredField("currentScreen").apply { isAccessible = true }
                field.get(Minecraft.getInstance()) != null
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Re-sends the current aim unchanged: keeps the request alive without moving the crosshair. */
    private fun holdAim(me: LocalPlayer, target: LivingEntity) {
        val sent = lastSent ?: return
        sendAim(me, sent, target, sent, null, report = false)
    }

    /**
     * Walks the aim back to the camera with the same bounded step, and only lets the request go
     * when the view is almost back. Without this the manager would hand the rotation back to the
     * camera in a single packet as soon as we stop asking for it.
     */
    private fun releaseAim(me: LocalPlayer) {
        val sent = lastSent ?: return
        val camera = Rotation(me.yRot, me.xRot)
        val gap = abs(Mth.wrapDegrees(camera.yaw - sent.yaw)) + abs(camera.pitch - sent.pitch)

        if (gap < deadZone) {
            lastSent = null
            velYaw = 0f
            velPitch = 0f
            return
        }

        sendAim(me, stepTowards(sent, camera), null, sent, camera, report = false)
    }

    /**
     * Quantizes, hands the rotation to [RotationManager] (or to the camera when not silent) and
     * records it as the new [lastSent].
     */
    private fun sendAim(
        me: LocalPlayer,
        next: Rotation,
        target: LivingEntity?,
        from: Rotation,
        goal: Rotation?,
        report: Boolean
    ) {
        var quantized = if (gcdFix) quantizeToMouseCounts(from, next) else next

        // Grim's AimDuplicateLook flags a movement packet whose rotation is identical to the
        // previous one ("Sent a duplicate rotation update without changing look direction").
        // A vanilla client never sends that: when the mouse does not move it sends a packet
        // *without* rotation, and the server keeps the last one. In silent mode the manager keeps
        // applying our rotation every tick, so a "still" aim would repeat the same angle forever.
        // One mouse count is enough to be a different rotation, and it is invisible.
        if (quantized.yaw == from.yaw) stillYawTicks++ else stillYawTicks = 0
        if (quantized.pitch == from.pitch) stillPitchTicks++ else stillPitchTicks = 0

        // Matrix scores the yaw/pitch jiff ratio over 25 deltas: if the pitch never moves, every
        // ratio is infinite and AimStatisticsCheck flags "Aim IQR". An axis may rest, never freeze.
        if (stillYawTicks > 2) {
            if (Random.nextInt(100) < 25) nudgeYawSign = -nudgeYawSign
            quantized = Rotation(quantized.yaw + nudge(nudgeYawSign), quantized.pitch)
            stillYawTicks = 0
        }
        if (stillPitchTicks > 2) {
            if (Random.nextInt(100) < 25) nudgePitchSign = -nudgePitchSign
            quantized = Rotation(
                quantized.yaw,
                (quantized.pitch + nudge(nudgePitchSign)).coerceIn(-89.5f, 89.5f)
            )
            stillPitchTicks = 0
        }

        if (quantized.yaw == from.yaw && quantized.pitch == from.pitch) {
            if (Random.nextInt(100) < 25) nudgeYawSign = -nudgeYawSign
            quantized = Rotation(quantized.yaw + nudge(nudgeYawSign), quantized.pitch)
        }

        // --- the ML model's "constant rotation" counter -------------------------------------
        // The RNN counts, per window, how many times the crosshair moves fast but with a speed
        // almost identical to the previous tick: a difference below 0.02 degrees while turning
        // more than 3 degrees counts as a robot. A saturated step limit produces exactly that,
        // because the quantizer keeps rounding the same magnitude to the same mouse count.
        // So: while the step is fast, its magnitude is never allowed to repeat.
        if (stepCountsOf(from, quantized) >= FAST_STEP_COUNTS &&
            stepCountsOf(from, quantized) == lastStepCounts
        ) {
            repeatedSpeed++
            // one count (0.15 degrees at the default sensitivity) is far above the 0.02 threshold
            val direction = if (Mth.wrapDegrees(quantized.yaw - from.yaw) >= 0f) 1 else -1
            quantized = Rotation(
                quantized.yaw + direction * RotationUtil.gcd.toFloat(),
                quantized.pitch
            )
        }
        lastStepCounts = stepCountsOf(from, quantized)
        recordYawDelta(Mth.wrapDegrees(quantized.yaw - from.yaw))

        if (silent) {
            RotationManager.setRotationTarget(
                rotationPlan(quantized, target),
                RotationPriority.IMPORTANT_FOR_USAGE_2,
                this
            )
        } else {
            writeRotation(me, quantized)
        }

        val step = kotlin.math.hypot(
            Mth.wrapDegrees(quantized.yaw - from.yaw).toDouble(),
            (quantized.pitch - from.pitch).toDouble()
        )
        lastSent = quantized
        remember(me, quantized)

        if (!report) return
        if (debugTick % 20 == 0) repeatedSpeed = 0

        // `raw` is the angle actually written to the player: it must grow/shrink continuously and
        // must NEVER jump by ~360 between two lines (that would be the AimModulo360 signature).
        debugState(
            "aiming %s dist=%.2f step=%.2f° remaining=%.1f° -> yaw=%.1f raw=%.1f pitch=%.1f (silent=%s, preset=%s)".format(
                target?.name?.string, target?.distanceTo(me) ?: 0f, step,
                if (goal == null) 0.0 else kotlin.math.hypot(
                    Mth.wrapDegrees(goal.yaw - quantized.yaw).toDouble(),
                    (goal.pitch - quantized.pitch).toDouble()
                ),
                Mth.wrapDegrees(quantized.yaw), me.yRot, quantized.pitch, silent, preset.tag
            ) + " §7(stepCounts=${stepCountsOf(from, quantized)} " +
                "lim=${"%.1f".format(maxStep * (1f + 2.2f * flickAmount))} " +
                "rank=${"%.2f".format(analysisRank())} repeatedSpeed=$repeatedSpeed)" + warnings()
        )
        if (chatLog && hitCount++ % 40 == 0) {
            chat("§7[Aimbot] target=${target?.name?.string} yaw=%.1f pitch=%.1f mode=%s".format(quantized.yaw, quantized.pitch, preset.tag))
        }
    }

    /**
     * Writes the rotation to the player without ever making the yaw "jump by a full turn".
     *
     * The vanilla client never sets an absolute angle on the player: `Entity.turn` *adds* the mouse
     * delta and `yRot` keeps accumulating (it can read 361.4, -720.8, ...). The server wraps it when
     * it applies the packet. Assigning a wrapped absolute value (`me.yRot = 179.9` followed by
     * `-179.9`) makes the field, and therefore the packet, jump by ~-360 degrees in a single tick,
     * which is precisely what Grim reports as "AimModulo360" (and Vulcan as "Aim (Rotation)").
     *
     * So we always add the shortest delta: the aim still lands on the wanted angle, but modulo 360
     * aligned with the current value.
     */
    private fun writeRotation(me: LocalPlayer, rotation: Rotation) {
        me.yRot += Mth.wrapDegrees(rotation.yaw - me.yRot)
        me.xRot = rotation.pitch.coerceIn(-89.9f, 89.9f)
    }

    /**
     * Settings that defeat the human-like behaviour, reported next to the debug line so a wrong
     * configuration is visible instead of being guessed from the anticheat log.
     */
    private fun warnings(): String = buildString {
        if (aimBias < 0.5f) append(" §cAimBias<0.5 (aim on the hitbox centre)")
        if (idleChance == 0) append(" §cIdleChance=0 (updates every tick)")
        if (maxStep > 8f) append(" §cMaxStep>8 (packets can carry a snap)")
        if (!gcdFix) append(" §cGCDFix off (deltas off the mouse lattice)")
    }

    /**
     * Prints the internal state once per second when the "Debug" setting is on: how many entities
     * were scanned, which filter threw them away, and the rotation that was requested.
     */
    private fun debugState(message: String) {
        if (!debug) return
        if (++debugTick % 20 != 0) return

        // The "no target" line used to be printed every second, forever, while walking around.
        if (message.startsWith("no target") && message == lastDebugMessage) return
        lastDebugMessage = message

        chat("§7[Aimbot] §f$message")
    }

    private fun findTarget(me: LocalPlayer): LivingEntity? {
        if (lockTarget && currentTarget != null && isValidTarget(me, currentTarget!!) && isInFov(me, rotationTo(me, currentTarget!!))) {
            return currentTarget
        }
        val level = mc.level ?: return null
        val aabb = me.boundingBox.inflate(range.toDouble())
        // use Level#getEntitiesOfClass with AABB - compatible with 26.3
        val candidates: List<LivingEntity> = try {
            level.getEntitiesOfClass(LivingEntity::class.java, aabb) { true }
        } catch (_: Exception) {
            // fallback: getEntities generic
            level.getEntities(null, aabb).filterIsInstance<LivingEntity>()
        }
        var best: LivingEntity? = null
        var bestScore = Float.MAX_VALUE
        scanCount = candidates.size
        validCount = 0
        for (e in candidates) {
            if (e == me) continue
            if (!isValidTarget(me, e)) continue
            val rot = rotationTo(me, e)
            if (!isInFov(me, rot)) {
                lastGate = "outside FOV (${"%.0f".format(abs(Mth.wrapDegrees(rot.yaw - me.yRot)))}° > ${"%.0f".format(fov / 2f)}°)"
                continue
            }
            if (checkVisible && !me.hasLineOfSight(e) && !throughWalls) {
                lastGate = "no line of sight"
                continue
            }
            if (e.squaredBoxedDistanceTo(me) > range * range) {
                lastGate = "out of range"
                continue
            }
            validCount++
            val score = when (priority) {
                Priority.DISTANCE -> me.distanceTo(e)
                Priority.HEALTH -> e.health
                Priority.FOV -> abs(Mth.wrapDegrees(rot.yaw - me.yRot))
                Priority.ANGLE -> abs(angleDiff(me.yRot, rot.yaw))
                Priority.ARMOR -> -getArmorValue(e)
            }
            if (score < bestScore) {
                bestScore = score
                best = e
            }
        }
        return best
    }

    private fun isValidTarget(me: Player, e: LivingEntity): Boolean {
        if (!e.isAlive) return reject("dead")
        if (e.health <= minHealth) return reject("health <= MinHealth")
        if (!targetDead && e.isDeadOrDying) return reject("dying")
        if (!targetInvisible && e.isInvisible) return reject("invisible")
        if (!targetFriends) {
            val isFriend = try {
                val fm = Class.forName("net.ccbluex.liquidbounce.utils.client.FriendManager")
                val m = fm.methods.firstOrNull { it.name == "isFriend" && it.parameterCount == 1 }
                m?.invoke(null, e) as? Boolean ?: false
            } catch (_: Exception) { false }
            if (isFriend) return reject("friend")
        }
        if (antiBot && isBot(e)) return reject("anti-bot")
        if (!e.shouldBeAttacked()) return reject("shouldBeAttacked() = false")
        return true
    }

    /** Records why an entity was filtered out, so the "Debug" log can show it. */
    private fun reject(reason: String): Boolean {
        lastGate = "rejected: $reason"
        return false
    }

    private fun isBot(e: LivingEntity): Boolean {
        return e.name.string.contains("§") || e.hurtTime == 0 && e.tickCount < 80
    }

    private fun getArmorValue(e: LivingEntity): Float {
        return try { e.armorValue.toFloat() } catch (_: Exception) { 0f }
    }

    private fun isInFov(me: LocalPlayer, rot: Rotation): Boolean {
        return abs(Mth.wrapDegrees(rot.yaw - me.yRot)) <= fov / 2f
    }

    private fun angleDiff(a: Float, b: Float) = Mth.wrapDegrees(a - b)

    private fun rotationTo(me: LocalPlayer, target: LivingEntity): Rotation {
        val eye = me.eyePosition
        val basePos = when (aimPoint) {
            AimPoint.HEAD -> target.eyePosition
            AimPoint.CHEST -> target.position().add(0.0, (target.bbHeight.toDouble() * 0.6), 0.0)
            AimPoint.CLOSEST -> closestPoint(me, target)
            AimPoint.RANDOMIZED -> randomizedPoint(target)
        }
        var pos = basePos
        if (predict > 0f && predictSize > 0) {
            val vel = target.position().subtract(target.lastPos)
            pos = pos.add(vel.scale((predict * predictSize).toDouble()))
        }
        val dx = pos.x - eye.x
        val dy = pos.y - eye.y
        val dz = pos.z - eye.z
        val yaw = Mth.wrapDegrees((Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat())
        val pitch = Mth.wrapDegrees((-Math.toDegrees(atan2(dy, sqrt(dx*dx + dz*dz)))).toFloat())
        return Rotation(yaw, pitch)
    }

    private fun closestPoint(me: LocalPlayer, target: LivingEntity): net.minecraft.world.phys.Vec3 {
        val eye = me.eyePosition
        val box = target.boundingBox
        val dist = eye.distanceTo(box.center)

        // Point blank: clamping a ray inside a box that surrounds the camera makes the aim swing
        // tens of degrees for a few centimetres of movement (this is where "dist=0.9, diff=25"
        // came from). Aim at the body, which moves smoothly with the entity.
        if (dist < 1.5) return box.center

        val dir = (lastSent ?: Rotation(me.yRot, me.xRot)).directionVector
        val p = eye.add(dir.scale(dist))
        return net.minecraft.world.phys.Vec3(
            clampAxis(p.x, box.minX + 0.15, box.maxX - 0.15),
            clampAxis(p.y, box.minY + 0.1, box.maxY - 0.15),
            clampAxis(p.z, box.minZ + 0.15, box.maxZ - 0.15)
        )
    }

    /** coerceIn that also survives a box thinner than the margin. */
    private fun clampAxis(value: Double, min: Double, max: Double): Double =
        if (min > max) (min + max) * 0.5 else value.coerceIn(min, max)

    private fun randomizedPoint(target: LivingEntity): net.minecraft.world.phys.Vec3 {
        val box = target.boundingBox
        val rx = box.minX + Random.nextFloat() * (box.maxX - box.minX)
        val ry = box.minY + Random.nextFloat() * (box.maxY - box.minY) * 0.85f + 0.1
        val rz = box.minZ + Random.nextFloat() * (box.maxZ - box.minZ)
        val rnd = (Random.nextFloat() - 0.5f) * aimRandomize
        return net.minecraft.world.phys.Vec3(rx + rnd, ry + rnd, rz + rnd)
    }

    /**
     * The rotation the aim is currently at, used only when there is no [lastSent] yet (module just
     * enabled, target just acquired). In silent mode this is what the manager is aiming with, not
     * the camera: starting from the camera would make every step re-derive the same value and the
     * aim would sit perfectly still on the target.
     */
    private fun currentAim(me: LocalPlayer): Rotation =
        if (silent) RotationManager.currentRotation ?: Rotation(me.yRot, me.xRot) else Rotation(me.yRot, me.xRot)

    /**
     * Where the crosshair would be if the aim were perfect, plus the permanent human error
     * (bias + slow drift). This is a **goal**, never a value that goes into a packet as-is:
     * [stepTowards] decides how much of the remaining angle is covered this tick.
     *
     * With [RotationMode.AI] the goal is whatever the model suggests, and it is still bounded by
     * [stepTowards] — the model can output large deltas and that is precisely what produced the
     * single-packet jumps before.
     */
    private fun goalRotation(me: LocalPlayer, target: LivingEntity): Rotation {
        val base = rotationTo(me, target)
        val aimed = if (rotationMode == RotationMode.AI) {
            aiStep(me, lastSent ?: base, base, target) ?: base
        } else {
            base
        }
        return applyHumanError(me, aimed)
    }

    /**
     * Turns [from] towards [to], covering a fixed share of the remaining angle (driven by
     * [smoothSpeed]) but never more than [maxStep] degrees per tick. This is the only place where the aim may move, and it is what makes the
     * rotation smooth:
     *
     *  - proportional to the remaining angle → it decelerates instead of overshooting;
     *  - hard capped by [maxStep] → no packet can carry a jump;
     *  - a difference below [deadZone] is not chased at all.
     */
    private fun stepTowards(from: Rotation, to: Rotation): Rotation {
        var dYaw = Mth.wrapDegrees(to.yaw - from.yaw)
        var dPitch = to.pitch - from.pitch

        if (abs(dYaw) + abs(dPitch) < deadZone) {
            // The aim is on target. Freezing here is the worst thing we can do: a rotation that is
            // completely still and exactly on the hitbox centre is the clearest "perfect rotation"
            // signature there is (Vulcan "Aim (Rotation)", Matrix "Aim", packet "perfectrotation").
            // A human keeps micro-correcting, so we keep the aim breathing inside the dead zone.
            swayPhase += 0.16f + Random.nextFloat() * 0.14f
            val amplitude = (0.35f + tremor * 1.5f + driftAmount * 0.2f).coerceIn(0.25f, 1f)
            dYaw = kotlin.math.sin(swayPhase) * amplitude
            dPitch = kotlin.math.sin(swayPhase * 0.63f + 1.1f) * amplitude * 0.5f
        }

        // Momentum must never fight the new direction: if the target moved across our aim, the
        // carried speed points the wrong way and keeping it makes the crosshair overshoot by
        // several degrees in a single tick (that is a visible snap, and a rotation check).
        if (velYaw * dYaw < 0f) velYaw = 0f
        if (velPitch * dPitch < 0f) velPitch = 0f

        // The smoothing enum only bends the response curve; it can never push it above 1.
        val shape = when (smoothing) {
            Smoothing.BEZIER -> 0.75f + 0.5f * bezierFactor(abs(dYaw) / 180f)
            Smoothing.SIGMOID -> 0.75f + 0.5f * sigmoidFactor(abs(dYaw) / 180f)
            Smoothing.LINEAR -> 1f
        }
        // Human aim has two phases (Fitts' law): a fast ballistic movement for a big error and a
        // slow corrective one when the crosshair is already close. Without the ballistic phase the
        // aim lags behind a moving target for seconds and the error keeps growing, which looks far
        // worse (a rotation that follows a target at constant speed) than a quick flick.
        val ballistic = (abs(dYaw) / 20f).coerceIn(0f, 1f)
        val rate = (0.3f * smoothSpeed * shape * responseJitter * (1f + 1.5f * ballistic))
            .coerceIn(0.02f, 1f)

        // The cap must GROW with the error, otherwise the aim can never catch a target that
        // circles around the player: it creeps at one constant speed for seconds while the error
        // keeps growing (the log showed remaining 13 -> 22 -> 44 degrees at step 3). That constant
        // creep is also the flattest possible speed sequence, which is what the ML model scores.
        // A human flick to a far target is fast: maxStep near the crosshair, up to 3.2x for a flick.
        val error = kotlin.math.max(abs(dYaw), abs(dPitch) * 1.4f)
        val flick = ((error - 4f) / 12f).coerceIn(0f, 1f)
        flickAmount = flick
        val limitFactor = (1f + 2.2f * flick) * (0.9f + 0.2f * chaosAbs())
        val limitYaw = maxStep * limitFactor
        val limitPitch = maxStep * 0.7f * limitFactor

        // Speed we would like to have this tick. A HARD clamp is what makes an aimbot detectable:
        // while the cap is saturated every step has exactly the same magnitude, and Matrix's
        // AimBasicCheck counts exactly that ("robotized" = |yawChange - yawChangeFirst|: more than
        // 3 of 10 samples with the same magnitude is "heuristic(constant)"), while AimSmoothCheck
        // flags three consecutive identical jiff values. A soft knee plus a per-tick variation
        // keeps the magnitude continuous and never pinned.
        // Independent per-axis modulation: yaw and pitch moving by the same factor every tick is
        // another regularity (and it flattens the speed sequence the ML model reads).
        val wantYaw = softLimit(dYaw * rate, limitYaw) * tickVariation()
        val wantPitch = softLimit(dPitch * rate, limitPitch) * tickVariation()

        // ...but the speed may only change by `maxAccel` per tick. Starting every turn at full
        // speed (or stopping it dead) is a signature every rotation check looks for, and ML models
        // detect it straight away; ramping the speed gives the turn an ease-in / ease-out profile.
        val maxAccel = (maxStep * (0.45f + 0.9f * ballistic) * (1f + 1.5f * flick)).coerceAtLeast(0.2f)
        velYaw = approach(velYaw, wantYaw, maxAccel)
        velPitch = approach(velPitch, wantPitch, maxAccel * 0.8f)

        // The carried speed must not overshoot the goal.
        val stepYaw = if (abs(velYaw) > abs(dYaw)) dYaw else velYaw
        val stepPitch = if (abs(velPitch) > abs(dPitch)) dPitch else velPitch

        // The yaw is kept CONTINUOUS on purpose: see [writeRotation] for why wrapping an absolute
        // angle here ends up in a ~360 degrees jump in the movement packet.
        return Rotation(
            from.yaw + stepYaw,
            (from.pitch + stepPitch).coerceIn(-89.5f, 89.5f)
        )
    }

    /**
     * Reproduces Matrix's `AimAnalysisCheck` metric locally, so the value it would compute is
     * visible in the debug line instead of being guessed from an anticheat message.
     *
     * The check keeps the yaw deltas of the "big" rotations (|yaw| > 1.35), cuts them in windows of
     * 10, computes the 4th order jiff of each window and counts the distinct values. 6 of 6 for a
     * window means rank 1.0; the batch is flagged when the average over 10 windows is below 0.95.
     *
     * @return the per-window ratio, or -1 while there are not enough samples yet.
     */
    private fun analysisRank(): Float {
        if (recentYawDeltas.size < 10) return -1f

        var values = recentYawDeltas.toList()
        repeat(4) {
            if (values.size < 2) return@repeat
            val next = ArrayList<Float>(values.size - 1)
            for (i in 1 until values.size) {
                next.add(kotlin.math.abs(kotlin.math.abs(values[i]) - kotlin.math.abs(values[i - 1])))
            }
            values = next
        }
        if (values.isEmpty()) return -1f
        return values.distinct().size.toFloat() / values.size
    }

    /** Records a sent yaw delta for [analysisRank]; only the "big" ones are scored by Matrix. */
    private fun recordYawDelta(delta: Float) {
        if (kotlin.math.abs(delta) <= 1.35f) return
        recentYawDeltas.addLast(delta)
        while (recentYawDeltas.size > 10) recentYawDeltas.removeFirst()
    }

    /** Length of a step in whole mouse counts: what the ML model measures as "speed". */
    private fun stepCountsOf(from: Rotation, to: Rotation): Int = kotlin.math.round(
        kotlin.math.hypot(
            Mth.wrapDegrees(to.yaw - from.yaw).toDouble(),
            (to.pitch - from.pitch).toDouble()
        ) / RotationUtil.gcd
    ).toInt()

    /** Moves [current] towards [target] by at most [maxDelta]. */
    private fun approach(current: Float, target: Float, maxDelta: Float): Float =
        current + (target - current).coerceIn(-maxDelta, maxDelta)

    /**
     * Saturates [value] softly at [limit]: proportional below the knee, asymptotically bounded
     * above it, and never exactly equal to the limit. Behaving exactly like `coerceIn` for small
     * values, so the smoothing feels the same, but the step magnitude can never get stuck.
     */
    private fun softLimit(value: Float, limit: Float): Float =
        if (limit <= 0f) 0f else limit * kotlin.math.tanh(value / limit)

    /**
     * Speed modulation for one axis of the current tick.
     *
     * Three ingredients, and the third one is what Matrix's `AimAnalysisCheck` measures: it takes
     * the last 100 "big" yaw deltas, cuts them in windows of 10, computes the 4th order jiff
     * (iterated |Δ| of |values|) and counts how many of the resulting 6 numbers are distinct.
     * Fewer than ~58 of 60 for a whole batch is flagged as "Long term analysis". Repeating
     * magnitudes — which a quantized, capped aimbot produces — is exactly what makes those 6
     * numbers repeat, so the magnitude must be driven by something that never repeats.
     *
     *  - a mean-reverting random walk (the "gust") for the overall speed;
     *  - a logistic map (chaotic, deterministic but aperiodic) for the fine structure;
     *  - an occasional "flinch": 1-3 ticks noticeably slower or faster, like a hand that slips.
     */
    private fun tickVariation(): Float {
        chaos = 3.99f * chaos * (1f - chaos)
        if (chaos <= 0.001f || chaos >= 0.999f) chaos = 0.51f
        val chaotic = (chaos - 0.5f) * 2f

        if (flinchLeft <= 0 && Random.nextInt(100) < 4) {
            flinchLeft = Random.nextInt(1, 4)
        }

        var variation = speedGust * (0.93f + Random.nextFloat() * 0.14f) * (1f + 0.18f * chaotic)

        if (flinchLeft > 0) {
            flinchLeft--
            variation *= if (Random.nextBoolean()) 0.72f else 1.22f
        }

        speedGust = (speedGust * 0.82f + (Random.nextFloat() - 0.5f) * 0.45f).coerceIn(0.62f, 1.45f)
        return variation
    }

    /** |chaos| in 0..1, used to dither the step limit so it is never the same ceiling. */
    private fun chaosAbs(): Float = kotlin.math.abs(chaos - 0.5f) * 2f

    /**
     * A tiny, quantized correction used when the aim would otherwise not move at all: one or two
     * mouse counts, keeping the direction for a while (a hand drifts, it does not oscillate).
     */
    private fun nudge(axisSign: Float): Float {
        val magnitude = if (Random.nextInt(100) < 30) 2f else 1f
        return magnitude * axisSign * RotationUtil.gcd.toFloat()
    }

    private fun bezierFactor(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return if (u > 0.35f) bezier(0.05f, 1f, 1 - u) else sigmoid(u)
    }
    private fun bezier(s: Float, e: Float, u: Float) = (1-u)*(1-u)*s + 2*(1-u)*u*1f + u*u*e
    private fun sigmoid(u: Float) = 1 / (1 + kotlin.math.exp(-0.5 * (u - 0.3))).toFloat()
    private fun sigmoidFactor(t: Float) = sigmoid(t)

    /**
     * Re-rolls the persistent aim error for a new target.
     *
     * A human crosshair is never on the exact hitbox centre: there is a constant bias (they aim at
     * the same wrong spot every time) plus a slow drift. Aiming at the geometric centre, even with
     * white noise around it, keeps the *average* error at zero — which is what Vulcan's
     * "Aim (Rotation)" and the "perfectrotation" packet check measure.
     */
    private fun rollAimBias(target: LivingEntity) {
        if (aimBias <= 0f) {
            biasYaw = 0f
            biasPitch = 0f
            driftYaw = 0f
            driftPitch = 0f
            responseJitter = 0.85f + Random.nextFloat() * 0.3f
            return
        }

        val magnitude = aimBias * (0.4f + Random.nextFloat() * 0.6f)
        val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
        biasYaw = (cos(angle.toDouble()).toFloat() * magnitude)
        // humans are worse vertically than horizontally
        biasPitch = (sin(angle.toDouble()).toFloat() * magnitude * 0.7f)

        driftYaw = 0f
        driftPitch = 0f
        responseJitter = 0.85f + Random.nextFloat() * 0.3f
    }

    /**
     * Adds the permanent human error: the per-target bias, a slow drift and a trace of noise.
     *
     * All of it is applied to the **goal**, and the goal is then approached with [stepTowards], so
     * the sent rotation stays continuous. Adding noise directly to the sent rotation (the old
     * "humanize") is what made the aim stutter: the step changed size every tick with no relation
     * to the previous one.
     */
    private fun applyHumanError(me: LocalPlayer, rot: Rotation): Rotation {
        if (driftAmount > 0f) {
            driftYaw = (driftYaw * 0.95f + (Random.nextFloat() - 0.5f) * driftAmount * 0.12f)
                .coerceIn(-driftAmount, driftAmount)
            driftPitch = (driftPitch * 0.95f + (Random.nextFloat() - 0.5f) * driftAmount * 0.08f)
                .coerceIn(-driftAmount * 0.7f, driftAmount * 0.7f)
        }

        var yaw = rot.yaw + biasYaw + driftYaw
        var pitch = rot.pitch + biasPitch + driftPitch

        // A trace of white noise, small on purpose: its only job is to break exact repeats.
        yaw += (Random.nextFloat() - 0.5f) * jitterYaw * 0.25f
        pitch += (Random.nextFloat() - 0.5f) * jitterPitch * 0.25f
        // Slow wander (a few degrees per second), not a per-tick wiggle.
        yaw += perlinNoise((perlinTick * 0.05f).toInt()) * perlinScale * 0.35f
        pitch += perlinNoise((perlinTick * 0.05f).toInt() + 40) * perlinScale * 0.2f
        perlinTick++

        if (keepSprint && me.isSprinting) yaw = Mth.wrapDegrees(yaw)
        return Rotation(yaw, pitch.coerceIn(-89.5f, 89.5f))
    }

    private fun perlinNoise(t: Int): Float {
        return (kotlin.math.sin(t * 0.31) * 0.5f + kotlin.math.sin(t * 0.07) * 0.3f).toFloat()
    }

    /**
     * Turns [desired] into a rotation a real mouse could have produced, starting from [from].
     *
     * The client moves the player by whole mouse counts: `step = (float)(f³ * 8) * 0.15f` degrees per
     * count, with `f = sensitivity * 0.6 + 0.2`. That value is exactly [RotationUtil.gcd] and it is the
     * same math LiquidBounce's [Rotation.normalize] uses, so:
     *
     *  - we round the DELTA from the last aimed rotation, never the absolute angle (rounding absolute
     *    values to a global grid is what produces "impossible sensitivity" flags);
     *  - we return the result marked [Rotation.isNormalized] so the manager does not round it again
     *    from a different base, which would move it off the lattice;
     *  - with [gcdDither] the residual is carried to the next tick and the rounding boundary wobbles,
     *    because a real hand does not always land on the nearest count. A perfectly repeating delta
     *    sequence (e.g. "+3 counts, +3 counts, ...") is a classic GCD/aimbot pattern.
     */
    private fun quantizeToMouseCounts(from: Rotation, desired: Rotation): Rotation {
        val step = RotationUtil.gcd
        if (step < 1.0e-6) return desired

        val deltaYaw = RotationUtil.angleDifference(desired.yaw, from.yaw).toDouble()
        val deltaPitch = (desired.pitch - from.pitch).toDouble()

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
            countsYaw = kotlin.math.round(rawYaw).toInt()
            countsPitch = kotlin.math.round(rawPitch).toInt()
        }

        val yaw = from.yaw + (countsYaw * step).toFloat()
        // never touch the exact +-90 boundary, servers treat it as an untouched pitch
        val pitch = (from.pitch + (countsPitch * step).toFloat()).coerceIn(-89.9f, 89.9f)
        // continuous yaw, like the vanilla client accumulates it (see [writeRotation])
        return Rotation(yaw, pitch, isNormalized = true)
    }

    /** Nearest whole mouse count, with the decision boundary wobbling like a human hand. */
    private fun handRound(counts: Double): Int {
        val wobble = Random.nextDouble(-DITHER_WOBBLE, DITHER_WOBBLE)
        return kotlin.math.floor(counts + 0.5 + wobble).toInt()
    }

    /**
     * Builds the plan handed to LiquidBounce's [RotationManager]: it carries the rotation this module already
     * computed, so the manager only has to send it to the server without touching the camera (silent rotation).
     *
     * The processors are intentionally left empty — this module smooths and quantizes its own rotation every
     * tick, and letting the manager run its angle smooth a second time would slow the aim down.
     */
    private fun rotationPlan(rotation: Rotation, entity: LivingEntity?) = RotationTarget(
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

    private fun model(): TwoDimensionalRegressionModel? = runCatching {
        if (!DeepLearningEngine.isInitialized) return@runCatching null
        val group = ModelManager.models
        group.modes.firstOrNull { it.tag.equals(aiModel.tag, ignoreCase = true) } ?: group.activeMode
    }.getOrNull()

    private fun aiStep(me: LocalPlayer, from: Rotation, goal: Rotation, target: LivingEntity): Rotation? {
        val mdl = model() ?: return null
        val prev = Rotation(beforeYaw, beforePitch)
        val output = runCatching {
            val sample = CombatSample(
                from.directionVector, prev.directionVector, goal.directionVector,
                prev.rotationDeltaTo(from).toVec2f(),
                me.position().subtract(me.lastPos),
                target.position().subtract(target.lastPos),
                me.squaredBoxedDistanceTo(target).toFloat(),
                target.hurtTime, 0
            )
            mdl.predict(sample.asInput)
        }.getOrNull() ?: return null
        if (output.size < 2 || !output[0].isFinite() || !output[1].isFinite()) return null
        var yaw = from.yaw + output[0] * aiYawMultiplier
        var pitch = from.pitch + output[1] * aiPitchMultiplier
        yaw += Mth.wrapDegrees(goal.yaw - yaw) * (aiCorrection / 100f)
        pitch += (goal.pitch - pitch) * (aiCorrection / 100f)
        return Rotation(Mth.wrapDegrees(yaw), pitch.coerceIn(-90f, 90f))
    }

    private fun remember(me: LocalPlayer, rot: Rotation) {
        beforeYaw = lastYaw
        beforePitch = lastPitch
        lastYaw = rot.yaw
        lastPitch = rot.pitch
    }
}