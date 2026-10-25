package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.autoconfig.AutoConfig
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
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
 * Aimassist — an aim **assist**, not an aimbot.
 *
 * The difference is the whole module: it never clicks, it never picks the target for you and it
 * never takes the view away from your hand. It only helps while the crosshair is already near an
 * enemy, and it only ever covers a *share* of what is left to correct, so the aim converges like a
 * hand that is being helped, not like a hand that has been replaced. [ModuleAimbotAI] is the
 * opposite tool: that one turns, this one nudges.
 *
 * Pipeline every tick: [findTarget] -> [bubble] -> [shape] -> [quantizeToMouseCounts] -> camera
 * write, or `RotationManager.setRotationTarget` when [silent].
 *
 *  - [findTarget] only looks inside a narrow cone around where you are already pointing ([fov]), so
 *    a target behind you is never "found" and cannot be pulled in from nowhere;
 *  - [bubble] is the weight of the assist: 1.0 while the crosshair is **on the hitbox**, falling to
 *    0 at the edge of the cone. That is the "aim assist bubble" of a controller: the magnetism is
 *    strongest exactly where it is invisible, and gone where it would become a snap;
 *  - [shape] bends the response (Linear/Bezier/Sigmoid), [strength] decides how much of the
 *    remaining angle is covered per tick and [maxSpeed] hard-caps a single tick. With
 *    `Strength = 35`, 10 degrees left become 3.5 this tick, 2.3 the next, and so on: never a flick;
 *  - [quantizeToMouseCounts] snaps every step to whole mouse counts (the real sensitivity lattice)
 *    and carries the rounding residual, so the deltas are the ones a real mouse would produce.
 *
 * Two ways to help, and both can be on at once ([Mode]):
 *  - **Assist** — the pull described above;
 *  - **Friction** — the cursor is damped while it glides over a hitbox. Not silent this is done on
 *    the mouse input itself ([mouseHandler] scales the cursor deltas of [MouseRotationEvent],
 *    exactly where vanilla `MouseHandler.turnPlayer` feeds `LocalPlayer.turn`), so the view
 *    physically slows down on the enemy. Silent the input cannot be touched (the camera is the
 *    player's), so the same share of *their* turn is subtracted from the rotation the server gets:
 *    the hitbox reads as sticky on the server while the screen keeps turning at full speed.
 *
 * What keeps it looking like a hand:
 *  - [turnCheck] — if you push the mouse *away* from the target, the pull does not fight you, it
 *    waits. Helping a turn you did not want is the one thing that feels like a cheat;
 *  - [onlyOnCrosshair] — nothing happens until the crosshair ray really crosses the hitbox, not
 *    merely "nearby";
 *  - [reactionDelay] + [reactionRandom] after a new target, [idleChance] of the ticks skipped
 *    entirely, [deadZone] inside which nothing is corrected: a hand is not a control loop;
 *  - [gcdFix] + [gcdDither] and a trace of [jitterYaw] / [jitterPitch], so no two corrections are
 *    identical and none of them lands off the mouse lattice;
 *  - [findTarget] picks the enemy closest to the crosshair and keeps it ([lockTarget]), so the aim
 *    never wobbles between two targets, and friends/bots follow LiquidBounce's combat settings.
 *
 * [silent] `false` is the legit configuration: the camera itself is helped and the movement follows
 * it. `true` goes through LiquidBounce's [RotationManager] with [moveFix], so only the server sees
 * the assist — it requests `IMPORTANT_FOR_USAGE_1`, i.e. below KillAura/Aimbot, which always keep
 * the rotation to themselves.
 *
 * Presets are full profiles, not just a speed: picking one writes every slider below, so the
 * ClickGUI always shows what the module is really doing. [Preset.CUSTOM] leaves the sliders alone.
 */
object ModuleAimassist : ClientModule("Aimassist", Categories.LIQUIDADDON) {

    /** A turn smaller than this many degrees counts as "the player is not moving the mouse". */
    private const val STILL = 0.02f

    /** How far (in mouse counts) the rounding boundary may wobble when [gcdDither] is on. */
    private const val DITHER_WOBBLE = 0.18

    enum class Mode(override val tag: String) : Tagged {
        ASSIST("Assist"), FRICTION("Friction"), BOTH("Both")
    }

    enum class Priority(override val tag: String) : Tagged {
        FOV("Fov"), DISTANCE("Distance"), HEALTH("Health"), ARMOR("Armor")
    }

    enum class Smoothing(override val tag: String) : Tagged {
        LINEAR("Linear"), BEZIER("Bezier"), SIGMOID("Sigmoid")
    }

    enum class AimPoint(override val tag: String) : Tagged {
        CLOSEST("Closest"), HEAD("Head"), CHEST("Chest")
    }

    enum class MoveFix(override val tag: String) : Tagged {
        OFF("Off"), STRICT("Strict"), SILENT("Silent")
    }

    enum class Preset(override val tag: String) : Tagged {
        CUSTOM("Custom"), BLATANT("Blatant"), LEGIT("Legit"), GHOST("Ghost"), CLOSET("Closet")
    }

    /**
     * A preset is a full set of settings, not just a strength. Picking one writes the values below
     * into the module's own values, so what you see in the ClickGUI is what the module does.
     * [Preset.CUSTOM] changes nothing and leaves your sliders alone.
     */
    @Suppress("LongParameterList")
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
        val antiBot: Boolean
    )

    // --- target selection -------------------------------------------------------------------

    private val rangeValue = float("Range", 3.4f, 1f..6f, "blocks")
    private val range by rangeValue

    /** Radius of the assist cone, measured from where the player is already pointing. */
    private val fovValue = float("Fov", 24f, 5f..120f, "°")
    private val fov by fovValue

    private val priority by enumChoice("Priority", Priority.FOV)
    private val throughWallsValue = boolean("ThroughWalls", false)
    private val throughWalls by throughWallsValue
    private val antiBotValue = boolean("AntiBot", true)
    private val antiBot by antiBotValue
    private val minHealth by int("MinHealth", 0, 0..20, "HP")
    private val lockTarget by boolean("LockTarget", true)
    private val switchDelayValue = int("SwitchDelay", 350, 0..2000, "ms")
    private val switchDelay by switchDelayValue

    // --- how much the aim is helped ---------------------------------------------------------

    private val presetValue = enumChoice("Preset", Preset.LEGIT)
    private val preset by presetValue

    private val modeValue = enumChoice("Mode", Mode.BOTH)
    private val mode by modeValue

    /** Share of the remaining angle covered per tick. 100 = the aim lands on the target at once. */
    private val strengthValue = float("Strength", 35f, 1f..100f, "%")
    private val strength by strengthValue

    /** Hard cap on a single tick, in degrees. It is what keeps a correction a nudge, never a flick. */
    private val maxSpeedValue = float("MaxSpeed", 7f, 0.5f..40f, "°")
    private val maxSpeed by maxSpeedValue

    private val smoothingValue = enumChoice("Smoothing", Smoothing.BEZIER)
    private val smoothing by smoothingValue

    /** Share of the vertical correction, relative to the horizontal one (humans track yaw better). */
    private val pitchAssistValue = float("PitchAssist", 55f, 0f..100f, "%")
    private val pitchAssist by pitchAssistValue

    private val aimPointValue = enumChoice("AimPoint", AimPoint.CLOSEST)
    private val aimPoint by aimPointValue
    private val predictValue = float("Predict", 0.1f, 0f..1f)
    private val predict by predictValue
    private val predictSizeValue = int("PredictSize", 2, 0..10, "ticks")
    private val predictSize by predictSizeValue

    /** Assist only while the crosshair ray crosses the hitbox ([onCrosshair]). */
    private val onlyOnCrosshairValue = boolean("OnlyOnCrosshair", false)
    private val onlyOnCrosshair by onlyOnCrosshairValue

    /** Do not fight the player: while the mouse is pushed away from the target, the pull waits. */
    private val turnCheckValue = boolean("TurnCheck", true)
    private val turnCheck by turnCheckValue

    /** How much of the cursor movement is eaten while it glides over a hitbox ([Mode.FRICTION]). */
    private val frictionValue = float("Friction", 20f, 0f..90f, "%")
    private val friction by frictionValue

    /** Inside this angle nothing is corrected: the aim is already where it should be. */
    private val deadZoneValue = float("DeadZone", 0.12f, 0f..3f, "°")
    private val deadZone by deadZoneValue

    private val reactionDelayValue = int("ReactionDelay", 140, 0..500, "ms")
    private val reactionDelay by reactionDelayValue
    private val reactionRandomValue = int("ReactionRandom", 70, 0..300, "ms")
    private val reactionRandom by reactionRandomValue
    private val idleChanceValue = int("IdleChance", 8, 0..40, "%")
    private val idleChance by idleChanceValue

    private val jitterYawValue = float("JitterYaw", 0.12f, 0f..2f, "°")
    private val jitterYaw by jitterYawValue
    private val jitterPitchValue = float("JitterPitch", 0.08f, 0f..2f, "°")
    private val jitterPitch by jitterPitchValue

    private val gcdFixValue = boolean("GcdFix", true)
    private val gcdFix by gcdFixValue
    private val gcdDitherValue = boolean("GcdDither", true)
    private val gcdDither by gcdDitherValue

    // --- how the rotation reaches the server ------------------------------------------------

    /** Only the server sees the assist; the camera stays exactly where the player points it. */
    private val silentValue = boolean("Silent", false)
    private val silent by silentValue
    private val moveFixValue = enumChoice("MoveFix", MoveFix.SILENT)
    private val moveFix by moveFixValue

    private val pauseOnInventory by boolean("PauseOnInventory", true)
    private val pauseOnUse by boolean("PauseOnUse", false)

    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

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
            reactionDelay = 40, reactionRandom = 20, idleChance = 0, jitterYaw = 0.05f,
            jitterPitch = 0.05f, gcdFix = true, gcdDither = false, switchDelay = 100,
            range = 4.2f, fov = 90f, silent = true, moveFix = MoveFix.SILENT,
            throughWalls = false, antiBot = true
        )

        // The default: the camera is helped, the mouse is damped on the hitbox, nothing is taken
        // away from the hand.
        Preset.LEGIT -> Profile(
            mode = Mode.BOTH, strength = 35f, maxSpeed = 7f, smoothing = Smoothing.BEZIER,
            pitchAssist = 55f, aimPoint = AimPoint.CLOSEST, predict = 0.1f, predictSize = 2,
            onlyOnCrosshair = false, turnCheck = true, friction = 20f, deadZone = 0.12f,
            reactionDelay = 140, reactionRandom = 70, idleChance = 8, jitterYaw = 0.12f,
            jitterPitch = 0.08f, gcdFix = true, gcdDither = true, switchDelay = 350,
            range = 3.4f, fov = 24f, silent = false, moveFix = MoveFix.SILENT,
            throughWalls = false, antiBot = true
        )

        // Small, slow and rare: for strict rotation statistics. You will barely feel it, except
        // when a target slides past the crosshair.
        Preset.GHOST -> Profile(
            mode = Mode.BOTH, strength = 18f, maxSpeed = 3.5f, smoothing = Smoothing.SIGMOID,
            pitchAssist = 35f, aimPoint = AimPoint.CLOSEST, predict = 0.05f, predictSize = 2,
            onlyOnCrosshair = true, turnCheck = true, friction = 35f, deadZone = 0.3f,
            reactionDelay = 220, reactionRandom = 120, idleChance = 18, jitterYaw = 0.1f,
            jitterPitch = 0.06f, gcdFix = true, gcdDither = true, switchDelay = 600,
            range = 3.1f, fov = 18f, silent = false, moveFix = MoveFix.SILENT,
            throughWalls = false, antiBot = true
        )

        // Silent: the camera is yours, only the rotation the server receives is helped.
        Preset.CLOSET -> Profile(
            mode = Mode.ASSIST, strength = 28f, maxSpeed = 5f, smoothing = Smoothing.BEZIER,
            pitchAssist = 45f, aimPoint = AimPoint.CHEST, predict = 0.1f, predictSize = 2,
            onlyOnCrosshair = true, turnCheck = true, friction = 0f, deadZone = 0.2f,
            reactionDelay = 180, reactionRandom = 90, idleChance = 12, jitterYaw = 0.1f,
            jitterPitch = 0.07f, gcdFix = true, gcdDither = true, switchDelay = 450,
            range = 3.2f, fov = 24f, silent = true, moveFix = MoveFix.SILENT,
            throughWalls = false, antiBot = true
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
            chat("§7[Aimassist] §fpreset ${preset.tag} applied")
        }
    }

    init {
        // Switching preset rewrites the sliders; CUSTOM leaves them alone. Nothing is applied while
        // the config is loading, otherwise a restored Preset would overwrite the sliders that were
        // restored right next to it.
        presetValue.onChanged { if (!AutoConfig.loadingNow) applyPreset(it) }
    }

    // --- state ------------------------------------------------------------------------------

    /** The target the assist is helping with. Kept between ticks: the mouse handler needs it. */
    private var currentTarget: LivingEntity? = null
    private var lastSwitch = 0L
    private var reactionUntil = 0L

    /** The rotation the module handed out on the previous tick (silent: what the server got). */
    private var lastSent: Rotation? = null

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

    /** Ticks the aim spent inside [deadZone]; used to keep a silent request alive. */
    private var stillTicks = 0
    private var nudgeSign = 1f

    /** Per-target response multiplier: two engagements never share the same speed profile. */
    private var responseJitter = 1f

    /** Rounding residual carried between ticks, so the aim never stalls on a sub-count difference. */
    private var yawCarry = 0.0
    private var pitchCarry = 0.0

    private var idleLeft = 0
    private var assisted = 0
    private var debugTick = 0
    private var scanCount = 0
    private var validCount = 0
    private var lastGate = "start"
    private var lastDebugMessage = ""

    override val tag: String
        get() = currentTarget?.name?.string?.take(8) ?: ""

    override fun onEnabled() {
        currentTarget = null
        lastSent = null
        lastSwitch = 0L
        reactionUntil = 0L
        idleLeft = 0
        assisted = 0
        stillTicks = 0
        nudgeSign = if (Random.nextBoolean()) 1f else -1f
        responseJitter = 1f
        yawCarry = 0.0
        pitchCarry = 0.0
        debugTick = 0
        lastGate = "start"
        lastDebugMessage = ""
        rememberCamera()
        super.onEnabled()
    }

    override fun onDisabled() {
        // Nothing to put back by hand: not silent the camera already is the aim (the module only
        // ever moves it a fraction of the way), and silent the manager drops every request whose
        // provider is not running anymore.
        currentTarget = null
        lastSent = null
        lastAssistYaw = 0f
        lastAssistPitch = 0f
        super.onDisabled()
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
     * the server gets instead (see [tickHandler]).
     * --------------------------------------------------------------------------------------- */
    @Suppress("unused")
    private val mouseHandler = handler<MouseRotationEvent> { event ->
        if (silent || mode == Mode.ASSIST || friction <= 0f) return@handler

        val target = currentTarget ?: return@handler
        if (!target.isAlive) return@handler
        if (System.currentTimeMillis() < reactionUntil) return@handler

        val me = mc.player ?: return@handler
        if (onlyOnCrosshair && !onCrosshair(me, target)) return@handler

        val weight = bubble(me, target)
        if (weight <= 0f) return@handler

        // Never below zero: friction may slow the cursor down, not reverse it.
        val scale = 1.0 - (friction / 100.0) * weight
        event.cursorDeltaX *= scale
        event.cursorDeltaY *= scale
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player
        if (me == null || mc.gameMode == null) {
            currentTarget = null
            lastSent = null
            return@handler
        }

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
            return@handler
        }

        val now = System.currentTimeMillis()
        if (target !== currentTarget) {
            // Still deciding whether to commit to this target: keep the current aim alive instead
            // of dropping the request (which would hand the camera back in a single packet).
            if (currentTarget != null && now - lastSwitch < switchDelay) {
                hold()
                return@handler
            }
            currentTarget = target
            lastSwitch = now
            reactionUntil = now + reactionDelay + Random.nextInt(reactionRandom + 1)
            responseJitter = 0.85f + Random.nextFloat() * 0.3f
            stillTicks = 0
        }

        if (now < reactionUntil) {
            hold()
            return@handler
        }

        // A hand does not move the mouse on every tick: holding the same rotation is what that
        // looks like. Skipping the update instead makes the angle grow and the next step burst.
        if (idleLeft > 0) {
            idleLeft--
            hold()
            return@handler
        }
        if (idleChance > 0 && Random.nextInt(100) < idleChance) {
            idleLeft = Random.nextInt(1, 3)
            hold()
            return@handler
        }

        if (onlyOnCrosshair && !onCrosshair(me, target)) {
            hold()
            debugLine(
                "crosshair off the hitbox (%.2f° from the centre)".format(crosshairOffset(target))
            )
            return@handler
        }

        val aim = currentAim(me)
        val weight = bubble(me, target)
        var stepYaw = 0f
        var stepPitch = 0f
        var correcting = false

        if (mode != Mode.FRICTION) {
            val goal = goalRotation(me, target, aim)
            val deltaYaw = Mth.wrapDegrees(goal.yaw - aim.yaw)
            val deltaPitch = goal.pitch - aim.pitch

            // Never help a turn the player did not want: while the mouse is pushed away from the
            // target, the assist waits. Fighting the hand is what makes an aim assist feel like a
            // cheat even at a low strength.
            if (turnCheck && abs(ownYaw) >= STILL && ownYaw * deltaYaw < 0f) {
                hold()
                debugLine(
                    "player turning away (own=%.2f°, target=%.2f°)".format(ownYaw, deltaYaw)
                )
                return@handler
            }

            if (abs(deltaYaw) < deadZone && abs(deltaPitch) < deadZone * 0.7f) {
                // Already there. Not silent this tick writes nothing at all, which is exactly what
                // a vanilla client does when the hand rests.
                stillTick(me, aim, target)
                return@handler
            }

            val share = (strength / 100f) * weight * shape(deltaYaw) * responseJitter
            stepYaw = deltaYaw * share
            stepPitch = deltaPitch * share * (pitchAssist / 100f)
            correcting = true
        }

        // Silent friction: the camera is not ours to slow down, so the same share of the player's
        // own turn is taken out of the rotation the server receives. On the server the hitbox
        // becomes sticky while the screen keeps turning at full speed, which is the whole point.
        if (silent && mode != Mode.ASSIST && friction > 0f) {
            val damp = (friction / 100f) * weight
            stepYaw -= ownYaw * damp
            stepPitch -= ownPitch * damp * 0.5f
            correcting = true
        }

        if (!correcting) {
            // Friction only, and the camera is the player's: [mouseHandler] already did the work.
            hold()
            return@handler
        }

        // A hand is never a control loop: a trace of noise on the step, then the mouse lattice.
        stepYaw += (Random.nextFloat() - 0.5f) * jitterYaw
        stepPitch += (Random.nextFloat() - 0.5f) * jitterPitch

        applyAim(me, aim, softLimit(stepYaw, maxSpeed), softLimit(stepPitch, maxSpeed * 0.7f), target)
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
        var next = Rotation(from.yaw + stepYaw, (from.pitch + stepPitch).coerceIn(-89.5f, 89.5f))
        if (gcdFix) next = quantizeToMouseCounts(from, next)

        if (silent) {
            RotationManager.setRotationTarget(
                plan(next, target),
                // Below KillAura/Aimbot: an aim assist that overrode a real aura would be a bug.
                RotationPriority.IMPORTANT_FOR_USAGE_1,
                this
            )
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
     */
    private fun writeCamera(me: LocalPlayer, rotation: Rotation) {
        val deltaYaw = Mth.wrapDegrees(rotation.yaw - me.yRot)
        val deltaPitch = rotation.pitch - me.xRot
        val beforePitch = me.xRot

        me.yRot += deltaYaw
        me.xRot = (beforePitch + deltaPitch).coerceIn(-89.9f, 89.9f)
        lastAssistYaw = deltaYaw
        lastAssistPitch = me.xRot - beforePitch
    }

    /** Re-sends the current aim unchanged: keeps the request alive without moving the crosshair. */
    private fun hold() {
        if (!silent) return
        val sent = lastSent ?: return
        RotationManager.setRotationTarget(
            plan(sent, currentTarget),
            RotationPriority.IMPORTANT_FOR_USAGE_1,
            this
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
        RotationManager.setRotationTarget(plan(next, null), RotationPriority.NOT_IMPORTANT, this)
    }

    /**
     * The plan handed to LiquidBounce's [RotationManager]: it carries the rotation this module
     * already computed, so the manager only has to send it without touching the camera.
     *
     * The processors are intentionally empty — this module shapes and quantizes its own rotation
     * every tick, and letting the manager run an angle smooth a second time would slow the assist
     * down and move it off the mouse lattice.
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

    /** Records why an entity was filtered out, so the "Debug" log can show it. */
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
     * the target.
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

    // --- mouse lattice ----------------------------------------------------------------------

    /**
     * Turns [desired] into a rotation a real mouse could have produced, starting from [from].
     *
     * The client moves the player by whole mouse counts: `step = (float)(f³ * 8) * 0.15f` degrees
     * per count, with `f = sensitivity * 0.6 + 0.2`. That value is exactly [RotationUtil.gcd], so:
     *
     *  - we round the DELTA from the last aimed rotation, never the absolute angle (rounding
     *    absolute values to a global grid is what produces "impossible sensitivity" flags);
     *  - we return the result marked [Rotation.isNormalized] so the manager does not round it again
     *    from a different base, which would move it off the lattice;
     *  - with [gcdDither] the residual is carried to the next tick and the rounding boundary
     *    wobbles, because a real hand does not always land on the nearest count. A repeating delta
     *    sequence ("+3 counts, +3 counts, ...") is a classic GCD/aimbot pattern.
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
            countsYaw = round(rawYaw).toInt()
            countsPitch = round(rawPitch).toInt()
        }

        val yaw = from.yaw + (countsYaw * step).toFloat()
        // never touch the exact +-90 boundary, servers treat it as an untouched pitch
        val pitch = (from.pitch + (countsPitch * step).toFloat()).coerceIn(-89.9f, 89.9f)
        // continuous yaw, like the vanilla client accumulates it (see [writeCamera])
        return Rotation(yaw, pitch, isNormalized = true)
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
            chat("§7[Aimassist] §f$assisted corrections (preset ${preset.tag})")
        }
        if (!debug) return

        debugLine(
            "assisting %s dist=%.2f offset=%.2f° bubble=%.2f → yaw=%.1f pitch=%.1f (silent=%s, preset=%s) ".format(
                target.name.string, target.distanceTo(me), crosshairOffset(target),
                bubble(me, target), aimed.yaw, aimed.pitch, silent, preset.tag
            ) + "§7strength=%.0f%% max=%.1f° own=%.2f°".format(
                strength, maxSpeed, lastOwnYaw
            ) + warnings()
        )
    }

    /** Prints the internal state once per second when the "Debug" setting is on. */
    private fun debugLine(message: String) {
        if (!debug) return
        if (++debugTick % 20 != 0) return
        if (message == lastDebugMessage) return
        lastDebugMessage = message
        chat("§7[Aimassist] §f$message")
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
    }
}
