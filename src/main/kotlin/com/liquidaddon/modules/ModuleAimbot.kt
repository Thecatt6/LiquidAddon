package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine
import net.ccbluex.liquidbounce.deeplearn.ModelManager
import net.ccbluex.liquidbounce.deeplearn.data.CombatSample
import net.ccbluex.liquidbounce.deeplearn.models.TwoDimensionalRegressionModel
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PlayerMovementTickEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.lastPos
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.kotlin.Priority as RotationPriority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ModuleAimbot — silent aimbot with human-like rotations.
 *
 * Pipeline every tick: [findTarget] -> [computeRotation] -> [humanize] -> [quantizeToMouseCounts]
 * -> `RotationManager.setRotationTarget`. The module never clicks; it only aims.
 *
 * Presets are full profiles, not just a speed: picking one writes every slider below, so the
 * ClickGUI always shows what the module is really doing. `Custom` leaves the sliders alone.
 * `Undetectable` is the profile tuned for Vulcan "Aim (Rotation)" / packet "perfectrotation".
 */
object ModuleAimbot : ClientModule("Aimbot", Categories.LIQUIDADDON) {

    /** How far (in mouse counts) the rounding boundary may wobble when [gcdDither] is on. */
    private const val DITHER_WOBBLE = 0.18

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

    private val smoothSpeedValue = float("SmoothSpeed", 0.65f, 0.1f..3f)
    private val smoothSpeed by smoothSpeedValue
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
            rotationMode = RotationMode.SNAP, smoothing = Smoothing.LINEAR, smoothSpeed = 3f,
            aimPoint = AimPoint.CLOSEST, aimRandomize = 0.25f, predict = 0.35f, predictSize = 3,
            overshoot = 0.1f, jitterYaw = 0.4f, jitterPitch = 0.3f, tremor = 0.15f, perlinScale = 0.3f,
            randomizeOffset = 0.15f, aimBias = 0.2f, drift = 0.1f, deadZone = 0.05f, idleChance = 0,
            gcdFix = true, gcdDither = false, reactionDelay = 60, reactionRandom = 40,
            switchDelay = 150, range = 4.2f, fov = 90f, silent = true, moveFix = MoveFix.SILENT,
            silentFov = false, throughWalls = false, checkVisible = true, antiBot = true
        )

        // Playable on normal servers, noticeably assisted.
        Preset.LEGIT -> Profile(
            rotationMode = RotationMode.AI, smoothing = Smoothing.BEZIER, smoothSpeed = 1f,
            aimPoint = AimPoint.CLOSEST, aimRandomize = 0.1f, predict = 0.25f, predictSize = 2,
            overshoot = 0.06f, jitterYaw = 0.35f, jitterPitch = 0.25f, tremor = 0.1f, perlinScale = 0.2f,
            randomizeOffset = 0.06f, aimBias = 1.2f, drift = 0.5f, deadZone = 0.25f, idleChance = 8,
            gcdFix = true, gcdDither = true, reactionDelay = 140, reactionRandom = 80,
            switchDelay = 400, range = 3.6f, fov = 80f, silent = true, moveFix = MoveFix.SILENT,
            silentFov = true, throughWalls = false, checkVisible = true, antiBot = true
        )

        // Slow and soft: for servers with strict rotation statistics.
        Preset.GHOST -> Profile(
            rotationMode = RotationMode.SMOOTH, smoothing = Smoothing.BEZIER, smoothSpeed = 0.45f,
            aimPoint = AimPoint.CLOSEST, aimRandomize = 0.05f, predict = 0.15f, predictSize = 2,
            overshoot = 0.02f, jitterYaw = 0.25f, jitterPitch = 0.2f, tremor = 0.05f, perlinScale = 0.15f,
            randomizeOffset = 0.03f, aimBias = 2.5f, drift = 1f, deadZone = 0.5f, idleChance = 20,
            gcdFix = true, gcdDither = true, reactionDelay = 220, reactionRandom = 120,
            switchDelay = 800, range = 3.2f, fov = 65f, silent = true, moveFix = MoveFix.SILENT,
            silentFov = true, throughWalls = false, checkVisible = true, antiBot = true
        )

        // Only the final correction is assisted, everything else is yours.
        Preset.CLOSET -> Profile(
            rotationMode = RotationMode.SMOOTH, smoothing = Smoothing.SIGMOID, smoothSpeed = 0.65f,
            aimPoint = AimPoint.CLOSEST, aimRandomize = 0.05f, predict = 0.1f, predictSize = 2,
            overshoot = 0.03f, jitterYaw = 0.3f, jitterPitch = 0.2f, tremor = 0.05f, perlinScale = 0.15f,
            randomizeOffset = 0.03f, aimBias = 1.8f, drift = 0.8f, deadZone = 0.4f, idleChance = 15,
            gcdFix = true, gcdDither = true, reactionDelay = 200, reactionRandom = 110,
            switchDelay = 650, range = 3.3f, fov = 70f, silent = true, moveFix = MoveFix.SILENT,
            silentFov = true, throughWalls = false, checkVisible = true, antiBot = true
        )

        // The configuration for Vulcan "Aim (Rotation)" / packet "perfectrotation".
        Preset.UNDETECTABLE -> Profile(
            rotationMode = RotationMode.SMOOTH, smoothing = Smoothing.BEZIER, smoothSpeed = 0.65f,
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

    private var spoofing = false
    private var spoofedMovement = false
    private var realYaw = 0f
    private var realPitch = 0f
    private var realYawO = 0f
    private var realPitchO = 0f
    private var realHeadYawO = 0f

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
        biasFor = null
        biasYaw = 0f
        biasPitch = 0f
        driftYaw = 0f
        driftPitch = 0f
        idleLeft = 0
        resetRotationMemory()
    }

    override fun onDisabled() {
        val me = mc.player
        if (me != null) endSpoof(me)
        // Nothing to clear by hand: RotationManager's RequestHandler drops every request whose provider
        // is not running anymore, so disabling this module already stops the rotation.
        currentTarget = null
    }

    private fun resetRotationMemory() {
        val me = mc.player ?: return
        lastYaw = me.yRot
        lastPitch = me.xRot
        beforeYaw = me.yRotO
        beforePitch = me.xRotO
    }

    @Suppress("unused")
    private val movementHandler = handler<PlayerMovementTickEvent> {
        val me = mc.player ?: return@handler
        if (silent && moveFix != MoveFix.OFF) beginSpoof(me, true)
    }

    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> { event ->
        if (spoofing && moveFix == MoveFix.SILENT) {
            val me = mc.player ?: return@handler
            event.directionalInput = silentInput(event.directionalInput, realYaw, me.yRot)
        }
    }

    @Suppress("unused")
    private val networkHandler = handler<PlayerNetworkMovementTickEvent> { event ->
        val me = mc.player ?: return@handler
        if (event.state == EventState.PRE) {
            if (silent) beginSpoof(me, false)
        } else {
            endSpoof(me)
        }
    }

    private fun beginSpoof(me: LocalPlayer, movement: Boolean) {
        if (spoofing) return
        spoofing = true
        spoofedMovement = movement
        realYaw = me.yRot
        realPitch = me.xRot
        realYawO = me.yRotO
        realPitchO = me.xRotO
        realHeadYawO = me.yHeadRotO
    }

    private fun endSpoof(me: LocalPlayer) {
        if (!spoofing) return
        spoofing = false
        me.yRot = realYaw
        me.xRot = realPitch
        me.yRotO = realYawO
        me.xRotO = realPitchO
        if (spoofedMovement) {
            me.yHeadRot = realYaw
            me.yHeadRotO = realHeadYawO
            me.yBob = me.yBobO + (realYaw - me.yBobO) * 0.5f
            me.xBob = me.xBobO + (realPitch - me.xBobO) * 0.5f
        }
    }

    private fun silentInput(input: DirectionalInput, realYaw: Float, sentYaw: Float): DirectionalInput {
        val forward = (if (input.forwards) 1 else 0) - (if (input.backwards) 1 else 0)
        val strafe = (if (input.left) 1 else 0) - (if (input.right) 1 else 0)
        if (forward == 0 && strafe == 0) return input
        val meant = realYaw + Math.toDegrees(atan2(-strafe.toDouble(), forward.toDouble()))
        val relative = Math.toRadians(Math.round(Mth.wrapDegrees(meant - sentYaw) / 45.0) * 45.0)
        val newForward = kotlin.math.round(kotlin.math.cos(relative)).toInt()
        val newStrafe = kotlin.math.round(-kotlin.math.sin(relative)).toInt()
        return DirectionalInput(newForward > 0, newForward < 0, newStrafe > 0, newStrafe < 0)
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player ?: return@handler
        if (mc.gameMode == null) {
            currentTarget = null
            return@handler
        }
        // inventory check - API changed in 26.3 (screen -> currentScreen), fallback disabled
        // if needed, rely on mc.screen check via reflection
        if (pauseOnInventory) {
            try {
                val f = Minecraft::class.java.getDeclaredField("screen").apply { isAccessible = true }
                val scr = f.get(Minecraft.getInstance())
                if (scr != null) return@handler
            } catch (_: Exception) {
                try {
                    val f2 = Minecraft::class.java.getDeclaredField("currentScreen").apply { isAccessible = true }
                    if (f2.get(Minecraft.getInstance()) != null) return@handler
                } catch (_: Exception) {}
            }
        }
        if (pauseOnUse && me.isUsingItem) return@handler
        if (!onSneak && me.isShiftKeyDown) return@handler
        if (!onSprint && me.isSprinting) return@handler

        endSpoof(me)

        val target = findTarget(me) ?: run {
            if (!lockTarget) currentTarget = null
            // Stop aiming: we just do not request a rotation anymore, the request sent in the previous
            // tick expires on its own after `ticksUntilReset` ticks.
            debugState("no target (scan=$scanCount valid=$validCount, last gate=$lastGate)")
            return@handler
        }

        if (currentTarget == null || !isValidTarget(me, target)) {
            val now = System.currentTimeMillis()
            if (now - lastTargetSwitch < switchDelay) return@handler
            lastTargetSwitch = now
            reactionUntil = now + reactionDelay + Random.nextInt(reactionRandom + 1)
        }
        if (target !== biasFor) {
            biasFor = target
            rollAimBias(target)
        }
        currentTarget = target

        if (System.currentTimeMillis() < reactionUntil) return@handler

        // A hand does not update the aim on every single tick: short holds are what makes a
        // rotation log look human. The request below lives `ticksUntilReset` ticks, so it is safe.
        if (idleLeft > 0) {
            idleLeft--
            return@handler
        }
        if (idleChance > 0 && Random.nextInt(100) < idleChance) {
            idleLeft = Random.nextInt(1, 3)
            return@handler
        }

        val rotation = computeRotation(me, target) ?: return@handler
        if (silentFov && !isInFov(me, rotation)) return@handler

        // Do not re-issue a request for a sub-degree correction: a human leaves those alone and
        // a log full of perfect micro-corrections is exactly what "perfect rotation" checks count.
        val current = currentAim(me)
        val missing = abs(Mth.wrapDegrees(rotation.yaw - current.yaw)) + abs(rotation.pitch - current.pitch)
        if (missing < deadZone) return@handler

        val final = humanize(me, rotation)
        val quantized = if (gcdFix) {
            // Snap the DELTA from the rotation the client last aimed with, exactly like the
            // RotationManager would do; never snap the absolute angle.
            quantizeToMouseCounts(currentAim(me), final)
        } else {
            final
        }

        if (silent) {
            RotationManager.setRotationTarget(
                rotationPlan(quantized, target),
                RotationPriority.IMPORTANT_FOR_USAGE_2,
                this
            )
        } else {
            me.yRot = quantized.yaw
            me.xRot = quantized.pitch.coerceIn(-89.9f, 89.9f)
        }

        remember(me, quantized)
        debugState(
            "aiming %s dist=%.2f -> yaw=%.1f pitch=%.1f (silent=%s, mode=%s, cam=%.1f/%.1f)".format(
                target.name.string, target.distanceTo(me), quantized.yaw, quantized.pitch,
                silent, preset.tag, me.yRot, me.xRot
            )
        )
        if (chatLog && hitCount++ % 40 == 0) {
            chat("§7[Aimbot] target=${target.name.string} yaw=%.1f pitch=%.1f mode=%s".format(quantized.yaw, quantized.pitch, preset.tag))
        }
    }

    /**
     * Prints the internal state once per second when the "Debug" setting is on: how many entities
     * were scanned, which filter threw them away, and the rotation that was requested.
     */
    private fun debugState(message: String) {
        if (!debug) return
        if (++debugTick % 20 != 0) return
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
        return try { e.getArmorValue().toFloat() } catch (_: Exception) { 0f }
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
        val dir = Rotation(me.yRot, me.xRot).directionVector
        val dist = eye.distanceTo(target.position())
        val along = kotlin.math.max(0.5, dist)
        val p = eye.add(dir.scale(along))
        return net.minecraft.world.phys.Vec3(
            p.x.coerceIn(box.minX + 0.15, box.maxX - 0.15),
            p.y.coerceIn(box.minY + 0.1, box.maxY - 0.15),
            p.z.coerceIn(box.minZ + 0.15, box.maxZ - 0.15)
        )
    }

    private fun randomizedPoint(target: LivingEntity): net.minecraft.world.phys.Vec3 {
        val box = target.boundingBox
        val rx = box.minX + Random.nextFloat() * (box.maxX - box.minX)
        val ry = box.minY + Random.nextFloat() * (box.maxY - box.minY) * 0.85f + 0.1
        val rz = box.minZ + Random.nextFloat() * (box.maxZ - box.minZ)
        val rnd = (Random.nextFloat() - 0.5f) * aimRandomize
        return net.minecraft.world.phys.Vec3(rx + rnd, ry + rnd, rz + rnd)
    }

    /**
     * The rotation the aim is currently at.
     *
     * In silent mode this must be what the manager is aiming with ("currentRotation"), NOT the
     * camera: starting every step from the camera makes the smoothed result a constant value the
     * module re-sends unchanged tick after tick — a perfectly still, perfectly on-target aim,
     * which is exactly the pattern rotation checks look for.
     */
    private fun currentAim(me: LocalPlayer): Rotation =
        if (silent) RotationManager.currentRotation ?: Rotation(me.yRot, me.xRot) else Rotation(me.yRot, me.xRot)

    /** The preset already wrote the sliders, so the rotation only follows the sliders. */
    private fun computeRotation(me: LocalPlayer, target: LivingEntity): Rotation? {
        val base = rotationTo(me, target)
        return when (rotationMode) {
            RotationMode.SNAP -> base
            RotationMode.SMOOTH -> smoothStep(me, base, smoothSpeed)
            RotationMode.AI -> aiStep(me, base, target) ?: smoothStep(me, base, smoothSpeed)
        }
    }

    private fun smoothStep(me: LocalPlayer, target: Rotation, speed: Float): Rotation {
        val cur = currentAim(me)
        val diffYaw = Mth.wrapDegrees(target.yaw - cur.yaw)
        val diffPitch = target.pitch - cur.pitch
        val factor = when (smoothing) {
            Smoothing.BEZIER -> bezierFactor(abs(diffYaw) / 180f) * speed * 0.15f
            Smoothing.SIGMOID -> sigmoidFactor(abs(diffYaw) / 180f) * speed * 0.15f
            Smoothing.LINEAR -> speed * 0.08f
        }
        val over = 1f + Random.nextFloat() * overshoot * (if (Random.nextBoolean()) 1 else -1)
        var yaw = cur.yaw + diffYaw * factor * over
        var pitch = cur.pitch + diffPitch * factor * over
        yaw += (Random.nextFloat() - 0.5f) * jitterYaw
        pitch += (Random.nextFloat() - 0.5f) * jitterPitch
        yaw += perlinNoise(perlinTick) * tremor
        perlinTick++
        return Rotation(yaw, pitch)
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
            return
        }

        val magnitude = aimBias * (0.4f + Random.nextFloat() * 0.6f)
        val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
        biasYaw = (Math.cos(angle.toDouble()).toFloat() * magnitude)
        // humans are worse vertically than horizontally
        biasPitch = (Math.sin(angle.toDouble()).toFloat() * magnitude * 0.7f)

        driftYaw = 0f
        driftPitch = 0f
    }

    /**
     * Applies bias + drift + a little noise. The drift is a mean-reverting random walk (not white
     * noise), so the aim "breathes" like a hand instead of vibrating around the target.
     */
    private fun humanize(me: LocalPlayer, rot: Rotation): Rotation {
        if (driftAmount > 0f) {
            driftYaw = (driftYaw * 0.9f + (Random.nextFloat() - 0.5f) * driftAmount * 0.5f)
                .coerceIn(-driftAmount, driftAmount)
            driftPitch = (driftPitch * 0.9f + (Random.nextFloat() - 0.5f) * driftAmount * 0.35f)
                .coerceIn(-driftAmount * 0.7f, driftAmount * 0.7f)
        }

        var yaw = rot.yaw + biasYaw + driftYaw
        var pitch = rot.pitch + biasPitch + driftPitch

        // remaining white noise, kept small: its job is only to break exact repeats
        yaw += (Random.nextFloat() - 0.5f) * jitterYaw * 0.35f
        pitch += (Random.nextFloat() - 0.5f) * jitterPitch * 0.35f
        yaw += perlinNoise(perlinTick + 100) * perlinScale * 0.5f
        pitch += perlinNoise(perlinTick + 200) * perlinScale * 0.3f

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
        return Rotation(Mth.wrapDegrees(yaw), pitch, isNormalized = true)
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

    private fun mouseFactor(): Double {
        val d = Minecraft.getInstance().options.sensitivity().get() * 0.6f.toDouble() + 0.2f.toDouble()
        return d * d * d * 8.0
    }

    private fun model(): TwoDimensionalRegressionModel? = runCatching {
        if (!DeepLearningEngine.isInitialized) return@runCatching null
        val group = ModelManager.models
        group.modes.firstOrNull { it.tag.equals(aiModel.tag, ignoreCase = true) } ?: group.activeMode
    }.getOrNull()

    private fun aiStep(me: LocalPlayer, goal: Rotation, target: LivingEntity): Rotation? {
        val mdl = model() ?: return null
        val cur = currentAim(me)
        val prev = Rotation(beforeYaw, beforePitch)
        val output = runCatching {
            val sample = CombatSample(
                cur.directionVector, prev.directionVector, goal.directionVector,
                prev.rotationDeltaTo(cur).toVec2f(),
                me.position().subtract(me.lastPos),
                target.position().subtract(target.lastPos),
                me.squaredBoxedDistanceTo(target).toFloat(),
                target.hurtTime, 0
            )
            mdl.predict(sample.asInput)
        }.getOrNull() ?: return null
        if (output.size < 2 || !output[0].isFinite() || !output[1].isFinite()) return null
        var yaw = cur.yaw + output[0] * aiYawMultiplier
        var pitch = cur.pitch + output[1] * aiPitchMultiplier
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