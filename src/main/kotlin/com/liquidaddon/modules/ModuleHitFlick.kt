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
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.entity.lastPos
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents
import net.minecraft.world.item.enchantment.EnchantmentHelper
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * HitFlick "mode 2", with the normal movement packets:
 *  - tick k: a click with a living entity under the crosshair is taken BEFORE Minecraft reads it (GameTickEvent is
 *    fired at the HEAD of Minecraft.tick, before handleKeybinds): no hit and no swing this tick. The view starts
 *    turning away from the target;
 *  - Rotation Smooth: the view turns FlickAngle degrees away in FlickTicks ticks (1 = a single flick, 2-5 = a smooth
 *    turn that accelerates and brakes like a hand);
 *    Rotation AI: the view turns with LiquidBounce's own AI aim (the AngleSmooth "AI" of KillAura: the same model
 *    and the same inputs, CombatSample) towards a point FlickAngle degrees off the player's view, until it arrives
 *    (2 degrees) or AIMaxTicks;
 *  - the hit tick: Return Instant = at the start of the tick the view comes back onto the target in ONE tick and the
 *    click is given back to Minecraft. On 26.3 Minecraft.tick runs pick() after this event and before handleKeybinds,
 *    so the crosshair already sees the restored view and startAttack does the vanilla hit: range check,
 *    ServerboundAttackPacket, swing, ServerboundPunchPacket, all before the movement packet. The server processes the
 *    hit with the yaw of the tick before (turned) -> the knockback is turned. The hit tick's rotation is on the
 *    target: hitbox accepts the hit.
 *    Return AI = the AI brings the view back too, and the click is given back in the first tick with the target
 *    under the crosshair (Minecraft.pick, private, called a moment before the tick's own pick): the knockback turns
 *    only by the AI's last step.
 *
 * Every rotation is a whole number of mouse counts (MouseHandler.turnPlayer + Entity.turn: sensitivity * 0.6f + 0.2f,
 * cubed, * 8, then float * 0.15f), so the quantization checks see a real mouse; the AI's steps too (like the GCD fix
 * LiquidBounce applies to its own rotations).
 *
 * Silent (default): the camera never moves, only the server sees the flick, like LiquidBounce's silent rotations.
 * The flick is an offset over the player's own view, put on the real rotation only while the player ticks and sends
 * its movement packet (PlayerMovementTickEvent at the HEAD of aiStep, or PlayerNetworkMovementTickEvent PRE at the
 * HEAD of sendPosition) and taken off right after (PlayerNetworkMovementTickEvent POST): the frames are drawn with
 * the real rotation. The hit is unchanged: the crosshair (the real view) is on the target, and the hit tick's
 * movement packet carries the real rotation. MoveFix decides what the movement does meanwhile (LiquidBounce's
 * MovementCorrection): Off = it follows the real view (the server sees a movement that does not match the yaw it
 * got), Strict = it follows the sent yaw (the keys push along the turned view), Silent = like Strict, with the keys
 * changed to the direction (of the 8) closest to where the player was going.
 *
 * Only the EXTRA knockback turns: Minecraft pushes the target 0.4 along the positions (attacker -> target) and adds a
 * push along the attacker's yaw only for a sprint hit (sprinting, attack strength above 0.9) or with Knockback /
 * attack_knockback (Player.causeExtraKnockback). OnlyExtraKnockback flicks only those hits, like the real modules.
 *
 * Settings:
 *   Preset           ready configs (Custom = the sliders below): Blatant (one-tick flick), AIQuick (AI, short),
 *                    AILegit (AI, smaller and not on every hit), Ghost (AI, small, rare). The presets set Rotation,
 *                    Return, FlickAngle, FlickTicks, the AI sliders, RandomizeOffset, ReturnError, Chance, FlickDelay
 *   OnlyExtraKnockback  flick only hits with extra knockback (sprint hit or Knockback weapon); the others stay normal
 *   Silent           the camera stays still, only the server sees the flick (off: the view turns on screen too)
 *   MoveFix          Silent only: Off, Strict or Silent (see above)
 *   Rotation         Smooth (FlickTicks, smoothstep) or AI (LiquidBounce's AI aim model)
 *   Return           Instant (one tick, the hit tick) or AI (the AI comes back, the hit when the crosshair is on)
 *   FlickAngle       how many degrees it turns away; the extra knockback turns by as much
 *   FlickTicks       Smooth: in how many ticks it turns away (1 = a single flick)
 *   AIModel          AI: the model (19KC8KP or 21KC11KP, the two LiquidBounce ships)
 *   AIYawMultiplier  AI: yaw output multiplier (LiquidBounce's OutputMultiplier, default 1.5)
 *   AICorrection     AI: share (%) of what is left that is added after the model's step (LiquidBounce corrects the
 *                    AI with a second AngleSmooth, Interpolation 2-5% in the usual configs)
 *   AIMaxTicks       AI: longest turn away (and longest way back), in ticks
 *   AIHitAt          AI: the hit leaves as soon as the turn away reached this share (%) of the angle: the knockback
 *                    uses the yaw of the tick before, so the turn need not arrive, and a short turn keeps the
 *                    target in reach (knockback displacement: flick right before the hit)
 *   WaitTicks        Silent: if at the hit the crosshair is not on the target (it moved), the flick is held up to
 *                    this many ticks waiting for it, then the click leaves anyway
 *   RandomizeOffset  random range around FlickAngle: 10 = FlickAngle plus or minus 5 degrees at each flick
 *   ReturnError      maximum error of the instant way back, in degrees (4 = up to 2 degrees either side)
 *   PitchNoise       how much the pitch moves during the flick, in degrees (0 = still); the knockback ignores it
 *   Chance           chance (%) that a valid hit becomes a flick; the others are normal hits
 *   FlickDelay       minimum time (ms) between two flicks; meanwhile the hits are normal
 *   Alternate        alternate right and left at each flick (off: always right)
 *   ChatLog          write every turned hit in chat
 */
object ModuleHitFlick : ClientModule("HitFlick", Categories.LIQUIDADDON) {

    enum class RotationMode(override val tag: String) : Tagged {
        SMOOTH("Smooth"),
        AI("AI")
    }

    enum class ReturnMode(override val tag: String) : Tagged {
        INSTANT("Instant"),
        AI("AI")
    }

    enum class MoveFix(override val tag: String) : Tagged {
        OFF("Off"),
        STRICT("Strict"),
        SILENT("Silent")
    }

    /** What a preset sets (Custom: nothing, the sliders count). */
    class Params(
        val rotation: RotationMode, val ret: ReturnMode, val angle: Int, val ticks: Int, val yawMultiplier: Float,
        val correction: Int, val maxTicks: Int, val hitAt: Int, val randomize: Int, val returnError: Int,
        val chance: Int, val delay: Int
    )

    enum class Preset(override val tag: String, val params: Params?) : Tagged {
        CUSTOM("Custom", null),
        BLATANT("Blatant", Params(RotationMode.SMOOTH, ReturnMode.INSTANT, 60, 1, 1.5f, 3, 3, 70, 0, 0, 100, 0)),
        AI_QUICK("AIQuick", Params(RotationMode.AI, ReturnMode.INSTANT, 45, 1, 2.0f, 5, 3, 60, 10, 0, 100, 0)),
        AI_LEGIT("AILegit", Params(RotationMode.AI, ReturnMode.INSTANT, 35, 1, 1.5f, 3, 4, 70, 15, 2, 60, 400)),
        GHOST("Ghost", Params(RotationMode.AI, ReturnMode.INSTANT, 25, 1, 1.5f, 3, 3, 70, 10, 2, 20, 1500))
    }

    enum class AiModel(override val tag: String) : Tagged {
        MODEL_19KC8KP("19KC8KP"),
        MODEL_21KC11KP("21KC11KP")
    }

    private val preset by enumChoice("Preset", Preset.AI_QUICK)
    private val onlyExtraKnockback by boolean("OnlyExtraKnockback", true)
    private val silent by boolean("Silent", true)
    private val moveFix by enumChoice("MoveFix", MoveFix.SILENT)
    private val rotationValue by enumChoice("Rotation", RotationMode.AI)
    private val returnValue by enumChoice("Return", ReturnMode.INSTANT)
    private val flickAngleValue by int("FlickAngle", 45, 10..180, "deg")
    private val flickTicksValue by int("FlickTicks", 1, 1..5, "ticks")
    private val aiModel by enumChoice("AIModel", AiModel.MODEL_19KC8KP)
    private val aiYawMultiplierValue by float("AIYawMultiplier", 2.0f, 0.5f..3f)
    private val aiCorrectionValue by int("AICorrection", 5, 0..30, "%")
    private val aiMaxTicksValue by int("AIMaxTicks", 3, 1..20, "ticks")
    private val aiHitAtValue by int("AIHitAt", 60, 20..100, "%")
    private val waitTicks by int("WaitTicks", 4, 0..10, "ticks")
    private val randomizeOffsetValue by int("RandomizeOffset", 10, 0..90, "deg")
    private val returnErrorValue by int("ReturnError", 0, 0..10, "deg")
    private val pitchNoise by int("PitchNoise", 0, 0..20, "deg")
    private val chanceValue by int("Chance", 100, 1..100, "%")
    private val flickDelayValue by int("FlickDelay", 0, 0..10000, "ms")

    // the values in use: the preset's, or the sliders' with Custom
    private val rotationMode: RotationMode get() = preset.params?.rotation ?: rotationValue
    private val returnMode: ReturnMode get() = preset.params?.ret ?: returnValue
    private val flickAngle: Int get() = preset.params?.angle ?: flickAngleValue
    private val flickTicks: Int get() = preset.params?.ticks ?: flickTicksValue
    private val aiYawMultiplier: Float get() = preset.params?.yawMultiplier ?: aiYawMultiplierValue
    private val aiCorrection: Int get() = preset.params?.correction ?: aiCorrectionValue
    private val aiMaxTicks: Int get() = preset.params?.maxTicks ?: aiMaxTicksValue
    private val aiHitAt: Int get() = preset.params?.hitAt ?: aiHitAtValue
    private val randomizeOffset: Int get() = preset.params?.randomize ?: randomizeOffsetValue
    private val returnError: Int get() = preset.params?.returnError ?: returnErrorValue
    private val chance: Int get() = preset.params?.chance ?: chanceValue
    private val flickDelay: Int get() = preset.params?.delay ?: flickDelayValue
    private val alternate by boolean("Alternate", true)
    private val chatLog by boolean("ChatLog", true)

    /**
     * The flick in progress: the target, the steps still to apply (mouse counts per tick, Smooth only), the counts
     * applied. AI: the point to reach (degrees off the player's own view), the phase and the two last rotations.
     */
    private class Flick(val target: LivingEntity, val yawSteps: IntArray, val pitchSteps: IntArray, val reason: String) {
        var next = 0
        var yawDone = 0
        var pitchDone = 0
        var ai = false
        var yawOff = 0f
        var pitchOff = 0f
        var returning = false
        var aiTicks = 0
        var totalTicks = 0
        var waited = 0
        var lastYaw = 0f
        var lastPitch = 0f
        var beforeYaw = 0f
        var beforePitch = 0f
    }

    private var pending: Flick? = null
    private var side = 1
    private var count = 0
    private var lastFlickAt = 0L
    private var aiWarned = false

    /** Silent: the offset (mouse counts) of the instant way back's tick, used once and cleared at the next tick. */
    private var hitYawCounts = 0
    private var hitPitchCounts = 0
    /** Silent: the real rotation while the sent one is on the player (between aiStep / sendPosition and its end). */
    private var spoofing = false
    private var spoofedMovement = false
    private var realYaw = 0f
    private var realPitch = 0f
    private var realYawO = 0f
    private var realPitchO = 0f
    private var realHeadYawO = 0f

    /** KeyMapping.clickCount: the clicks queued for handleKeybinds (private; 26.x names are not obfuscated). */
    private val clickCount: Field by lazy {
        KeyMapping::class.java.getDeclaredField("clickCount").apply { isAccessible = true }
    }

    /** Minecraft.pick(float) is private; Return AI calls it to know whether the crosshair is back on the target. */
    private val pickMethod: Method? by lazy {
        runCatching {
            Minecraft::class.java.getDeclaredMethod("pick", Float::class.javaPrimitiveType).apply { isAccessible = true }
        }.getOrNull()
    }

    override val tag: String
        get() = if (count == 0) "" else count.toString()

    override fun onEnabled() {
        pending = null
        count = 0
        lastFlickAt = 0L
        aiWarned = false
        super.onEnabled()
    }

    override fun onDisabled() {
        val me = mc.player
        val flick = pending
        if (me != null) {
            endSpoof(me)
            if (flick != null && !silent) {
                turn(me, -flick.yawDone, -flick.pitchDone) // never leave the view turned
            }
        }
        pending = null
        hitYawCounts = 0
        hitPitchCounts = 0
        super.onDisabled()
    }

    /** Silent, MoveFix Strict/Silent: the sent rotation goes on the player before the movement (HEAD of aiStep). */
    @Suppress("unused")
    private val movementHandler = handler<PlayerMovementTickEvent> {
        val me = mc.player ?: return@handler
        if (silent && moveFix != MoveFix.OFF) {
            beginSpoof(me, true)
        }
    }

    /** MoveFix Silent: the keys that, along the sent yaw, push closest to where the player was going. */
    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> { event ->
        if (spoofing && moveFix == MoveFix.SILENT) {
            val me = mc.player ?: return@handler
            event.directionalInput = silentInput(event.directionalInput, realYaw, me.yRot)
        }
    }

    /** Silent: on at the HEAD of sendPosition (MoveFix Off), off at its RETURN in every mode. */
    @Suppress("unused")
    private val networkHandler = handler<PlayerNetworkMovementTickEvent> { event ->
        val me = mc.player ?: return@handler
        if (event.state == EventState.PRE) {
            if (silent) {
                beginSpoof(me, false)
            }
        } else {
            endSpoof(me)
        }
    }

    /** The offset of this tick: the flick in progress, or the instant way back's error in the hit tick. */
    private fun spoofCounts(): Pair<Int, Int> {
        val flick = pending
        return if (flick != null) flick.yawDone to flick.pitchDone else hitYawCounts to hitPitchCounts
    }

    /** [movement] = from the HEAD of aiStep (the movement, the hand bob and the head see the sent rotation too). */
    private fun beginSpoof(me: LocalPlayer, movement: Boolean) {
        if (spoofing) {
            return
        }
        val (yawCounts, pitchCounts) = spoofCounts()
        if (yawCounts == 0 && pitchCounts == 0) {
            return
        }
        spoofing = true
        spoofedMovement = movement
        realYaw = me.yRot
        realPitch = me.xRot
        // set before the tick (setOldPosAndRot): LivingEntity.tick shifts them by 360 when the sent yaw is 180+ away
        realYawO = me.yRotO
        realPitchO = me.xRotO
        realHeadYawO = me.yHeadRotO
        me.yRot = realYaw + stepOf(yawCounts)
        me.xRot = (realPitch + stepOf(pitchCounts)).coerceIn(-90f, 90f)
    }

    /**
     * The real rotation back on the player before the frames are drawn, with what followed the sent one meanwhile:
     * the old rotations, the head (Player.aiStep puts it on the yaw) and the hand bob of the first person view
     * (LocalPlayer.applyInput: bob += (rotation - bob) * 0.5, done again with the real rotation).
     */
    private fun endSpoof(me: LocalPlayer) {
        if (!spoofing) {
            return
        }
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

    /**
     * MovementCorrection Silent: [input] means a direction relative to the real yaw; along [sentYaw] the same keys
     * would push elsewhere, so they become the direction (of the 8) closest to the one meant.
     */
    private fun silentInput(input: DirectionalInput, realYaw: Float, sentYaw: Float): DirectionalInput {
        val forward = (if (input.forwards) 1 else 0) - (if (input.backwards) 1 else 0)
        val strafe = (if (input.left) 1 else 0) - (if (input.right) 1 else 0)
        if (forward == 0 && strafe == 0) {
            return input
        }
        // relative angle of the keys: 0 = forwards, -90 = left (Entity.moveRelative), +90 = right
        val meant = realYaw + Math.toDegrees(atan2(-strafe.toDouble(), forward.toDouble()))
        val relative = Math.toRadians(Math.round(Mth.wrapDegrees(meant - sentYaw) / 45.0) * 45.0)
        val newForward = Math.round(cos(relative)).toInt()
        val newStrafe = Math.round(-sin(relative)).toInt()
        return DirectionalInput(newForward > 0, newForward < 0, newStrafe > 0, newStrafe < 0)
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player
        if (me == null || mc.gameMode == null) {
            pending = null
            spoofing = false
            return@handler
        }
        endSpoof(me) // a tick without sendPosition: never leave the sent rotation on the camera
        hitYawCounts = 0
        hitPitchCounts = 0
        
        val flick = pending
        if (flick != null) {
            dropClicks() // clicks during the flick would be extra hits
            if (flick.waited > 0) {
                finishInstant(me, flick, flick.totalTicks + flick.waited) // still waiting for the crosshair
                return@handler
            }
            if (flick.ai) {
                tickAi(me, flick)
                return@handler
            }
            if (flick.next < flick.yawSteps.size) {
                apply(me, flick, flick.yawSteps[flick.next], flick.pitchSteps[flick.next])
                flick.next++
                return@handler
            }
            finishInstant(me, flick, flick.yawSteps.size)
            return@handler
        }
        if (mc.gui.screen() != null) {
            return@handler
        }
        // living entities only: boats, minecarts and crystals are left to Minecraft
        val target = mc.crosshairPickEntity as? LivingEntity ?: return@handler
        if (!target.isAlive) {
            return@handler
        }
        val reason = extraKnockback(me)
        if (onlyExtraKnockback && reason == null) {
            return@handler // no extra knockback: the flick would change nothing, the click stays a normal hit
        }
        val now = System.currentTimeMillis()
        if (now - lastFlickAt < flickDelay || Random.nextInt(100) >= chance) {
            return@handler // Flick Delay / Chance: this click stays a normal vanilla hit
        }
        if (!mc.options.keyAttack.consumeClick()) {
            return@handler // no click this tick
        }
        dropClicks()
        lastFlickAt = now
        val angle = max(1.0, flickAngle + uniform(randomizeOffset))
        val signedAngle = side * angle
        if (alternate) {
            side = -side
        }
        val model = if (rotationMode == RotationMode.AI || returnMode == ReturnMode.AI) model() else null
        if (model == null && (rotationMode == RotationMode.AI || returnMode == ReturnMode.AI) && !aiWarned) {
            aiWarned = true
            me.sendSystemMessage(Component.literal("§c[HitFlick] AI non disponibile (il motore deep learning di "
                    + "LiquidBounce non e' avviato o il modello manca): uso Smooth e ritorno Instant"))
        }
        if (model != null && rotationMode == RotationMode.AI) {
            val newFlick = Flick(target, IntArray(0), IntArray(0), reason ?: "nessun extra")
            newFlick.ai = true
            newFlick.yawOff = signedAngle.toFloat()
            newFlick.pitchOff = uniform(pitchNoise).toFloat()
            newFlick.beforeYaw = me.yRotO
            newFlick.beforePitch = me.xRotO
            newFlick.lastYaw = me.yRot
            newFlick.lastPitch = me.xRot
            pending = newFlick
            tickAi(me, newFlick) // tick k: the first step goes out with this tick's movement packet
            return@handler
        }
        var total = countsFor(signedAngle)
        if (total == 0) {
            total = side
        }
        val newFlick = Flick(target, smoothSteps(total, flickTicks), smoothSteps(countsFor(uniform(pitchNoise)), flickTicks),
            reason ?: "nessun extra")
        newFlick.ai = model != null && returnMode == ReturnMode.AI // Smooth away, AI way back
        newFlick.lastYaw = me.yRot
        newFlick.lastPitch = me.xRot
        newFlick.beforeYaw = me.yRotO
        newFlick.beforePitch = me.xRotO
        pending = newFlick
        // tick k: the first step goes out with this tick's movement packet
        apply(me, newFlick, newFlick.yawSteps[0], newFlick.pitchSteps[0])
        newFlick.next = 1
        newFlick.totalTicks = 1
        newFlick.returning = newFlick.yawSteps.size == 1
        remember(me, newFlick)
    }

    /** The player's own view: the camera, without what the flick added to it when not Silent. */
    private fun viewYaw(me: LocalPlayer, flick: Flick): Float = if (silent) me.yRot else me.yRot - stepOf(flick.yawDone)

    private fun viewPitch(me: LocalPlayer, flick: Flick): Float =
        if (silent) me.xRot else me.xRot - stepOf(flick.pitchDone)

    /** The rotation the server gets with the offset applied so far. */
    private fun sentYaw(me: LocalPlayer, flick: Flick): Float = viewYaw(me, flick) + stepOf(flick.yawDone)

    private fun sentPitch(me: LocalPlayer, flick: Flick): Float =
        (viewPitch(me, flick) + stepOf(flick.pitchDone)).coerceIn(-90f, 90f)

    /** One tick of an AI flick (or of the AI way back after a Smooth turn away). */
    private fun tickAi(me: LocalPlayer, flick: Flick) {
        val baseYaw = viewYaw(me, flick)
        val basePitch = viewPitch(me, flick)
        flick.totalTicks++
        if (!flick.returning) {
            if (flick.next < flick.yawSteps.size) {
                // Smooth turn away followed by the AI way back
                apply(me, flick, flick.yawSteps[flick.next], flick.pitchSteps[flick.next])
                flick.next++
                flick.returning = flick.next >= flick.yawSteps.size
                remember(me, flick)
                return
            }
            val goalYaw = baseYaw + flick.yawOff
            val goalPitch = (basePitch + flick.pitchOff).coerceIn(-90f, 90f)
            val step = aiStep(me, flick, goalYaw, goalPitch) ?: run {
                finishInstant(me, flick, flick.totalTicks) // the model failed: back now
                return
            }
            apply(me, flick, step.first, step.second)
            flick.aiTicks++
            remember(me, flick)
            val reached = abs(stepOf(flick.yawDone)) >= abs(flick.yawOff) * aiHitAt / 100f
            if (reached || abs(Mth.wrapDegrees(goalYaw - sentYaw(me, flick))) <= 2f || flick.aiTicks >= aiMaxTicks) {
                flick.returning = true
                flick.aiTicks = 0
            }
            return
        }
        if (returnMode == ReturnMode.INSTANT || model() == null) {
            finishInstant(me, flick, flick.totalTicks - 1)
            return
        }
        val step = aiStep(me, flick, baseYaw, basePitch.coerceIn(-90f, 90f))
        if (step == null || flick.aiTicks >= aiMaxTicks) {
            finishInstant(me, flick, flick.totalTicks - 1)
            return
        }
        val turned = abs(stepOf(flick.yawDone)) // where the yaw of the tick before the hit was
        apply(me, flick, step.first, step.second)
        flick.aiTicks++
        remember(me, flick)
        if (backOnTarget(me, flick)) {
            // the AI brought the crosshair back on the target: the hit leaves now, with this rotation
            pending = null
            hit(me, flick, flick.totalTicks, turned)
        }
    }

    /**
     * Return AI: the hit can leave. Not Silent: the crosshair (Minecraft.pick a moment before the tick's own) is on the
     * target. Silent: the crosshair always is, so the rotation the server will get must hit the target's box.
     */
    private fun backOnTarget(me: LocalPlayer, flick: Flick): Boolean {
        if (!silent) {
            pickMethod?.let { method -> runCatching { method.invoke(mc, 1.0f) } }
            return mc.crosshairPickEntity === flick.target
        }
        val eyes = me.eyePosition
        val box = flick.target.boundingBox
        val reach = eyes.add(Rotation(sentYaw(me, flick), sentPitch(me, flick)).directionVector
            .scale(me.entityInteractionRange()))
        return box.contains(eyes) || box.clip(eyes, reach).isPresent
    }

    /**
     * The instant way back: ONE tick, with the configured error, then the click goes back to Minecraft. Silent: if
     * the target moved away from the crosshair the flick is held (the server keeps getting the turned yaw) up to
     * WaitTicks, until the player aims at it again; then the click leaves anyway.
     */
    private fun finishInstant(me: LocalPlayer, flick: Flick, ticks: Int) {
        if (silent && flick.waited < waitTicks && flick.target.isAlive && !crosshairOn(flick)) {
            flick.waited++
            pending = flick
            return
        }
        pending = null
        val turned = abs(stepOf(flick.yawDone))
        val errorYaw = countsFor(uniform(returnError))
        val errorPitch = countsFor(uniform(pitchNoise))
        if (silent) {
            // the camera never moved: only this tick's packet carries the error, the next one is the real view
            hitYawCounts = errorYaw
            hitPitchCounts = errorPitch
        } else {
            turn(me, -flick.yawDone + errorYaw, -flick.pitchDone + errorPitch)
        }
        hit(me, flick, ticks, turned)
    }

    /** The real crosshair (Minecraft.pick a moment before the tick's own) is on the flick's target. */
    private fun crosshairOn(flick: Flick): Boolean {
        pickMethod?.let { method -> runCatching { method.invoke(mc, 1.0f) } }
        return mc.crosshairPickEntity === flick.target
    }

    /** The click goes back to Minecraft; [turned] = degrees the yaw of the tick before the hit was off the view. */
    private fun hit(me: LocalPlayer, flick: Flick, ticks: Int, turned: Float) {
        if (!flick.target.isAlive) {
            return
        }
        queueClick()
        count++
        if (chatLog) {
            val away = if (flick.yawSteps.isEmpty()) "AI" else "smooth"
            val back = if (flick.ai && returnMode == ReturnMode.AI && model() != null) "AI" else "instant"
            val view = (if (silent) ", silent" else "") + (if (flick.waited > 0) ", atteso ${flick.waited} tick" else "") +
                ", preset ${preset.tag}"
            me.sendSystemMessage(Component.literal("§7[HitFlick] colpo $count (${flick.reason}), yaw girato di "
                    + "%.2f gradi in %d tick (andata %s, ritorno %s%s)".format(turned, ticks, away, back, view)))
        }
    }

    /** The rotations sent in the last two ticks, for the model's velocity input. */
    private fun remember(me: LocalPlayer, flick: Flick) {
        flick.beforeYaw = flick.lastYaw
        flick.beforePitch = flick.lastPitch
        flick.lastYaw = sentYaw(me, flick)
        flick.lastPitch = sentPitch(me, flick)
    }

    /** The model LiquidBounce loaded with this name, or the active one; null if its deep learning engine is off. */
    private fun model(): TwoDimensionalRegressionModel? = runCatching {
        if (!DeepLearningEngine.isInitialized) {
            return@runCatching null
        }
        val group = ModelManager.models
        group.modes.firstOrNull { it.tag.equals(aiModel.tag, ignoreCase = true) } ?: group.activeMode
    }.getOrNull()

    /**
     * One step of LiquidBounce's AiAngleSmooth.process towards [goalYaw]/[goalPitch], in mouse counts: the same
     * CombatSample (current, previous and target direction, last tick's turn, both movements, boxed distance,
     * hurt time), the model's output times the multiplier, then a share of what is left (the correction).
     */
    private fun aiStep(me: LocalPlayer, flick: Flick, goalYaw: Float, goalPitch: Float): Pair<Int, Int>? {
        val model = model() ?: return null
        val current = Rotation(sentYaw(me, flick), sentPitch(me, flick))
        val previous = Rotation(flick.beforeYaw, flick.beforePitch)
        val goal = Rotation(goalYaw, goalPitch)
        val target = flick.target
        val output = runCatching {
            val sample = CombatSample(current.directionVector, previous.directionVector, goal.directionVector,
                previous.rotationDeltaTo(current).toVec2f(), me.position().subtract(me.lastPos),
                target.position().subtract(target.lastPos), me.squaredBoxedDistanceTo(target).toFloat(),
                target.hurtTime, 0)
            model.predict(sample.asInput)
        }.getOrNull() ?: return null
        if (output.size < 2 || !output[0].isFinite() || !output[1].isFinite()) {
            return null
        }
        var yaw = current.yaw + output[0] * aiYawMultiplier
        var pitch = current.pitch + output[1]
        val share = aiCorrection / 100f
        yaw += Mth.wrapDegrees(goalYaw - yaw) * share
        pitch += (goalPitch - pitch) * share
        pitch = pitch.coerceIn(-90f, 90f)
        return countsFor(Mth.wrapDegrees(yaw - current.yaw).toDouble()) to countsFor((pitch - current.pitch).toDouble())
    }

    /**
     * Why this hit gets the extra knockback that follows the yaw, or null: a sprint hit (sprinting and attack strength
     * above 0.9, as Player.attack checks it), or a weapon with a knockback enchantment effect / attack_knockback.
     * The hit leaves some ticks later: the attack strength only grows meanwhile, the sprint must still be held.
     */
    private fun extraKnockback(me: LocalPlayer): String? {
        if (me.isSprinting && me.getAttackStrengthScale(0.5f) > 0.9f) {
            return "sprint"
        }
        if (EnchantmentHelper.has(me.mainHandItem, EnchantmentEffectComponents.KNOCKBACK)
            || me.getAttributeValue(Attributes.ATTACK_KNOCKBACK) > 0.0) {
            return "contraccolpo"
        }
        return null
    }

    private fun dropClicks() {
        while (mc.options.keyAttack.consumeClick()) {
            // eaten: Minecraft will neither attack nor swing for these clicks
        }
    }

    /** One click for this tick's handleKeybinds, as if the player had just pressed the attack key. */
    private fun queueClick() {
        val key = mc.options.keyAttack
        clickCount.setInt(key, clickCount.getInt(key) + 1)
    }

    /** Adds to the flick's offset; not Silent, the camera turns by as much. */
    private fun apply(me: LocalPlayer, flick: Flick, yawCounts: Int, pitchCounts: Int) {
        if (!silent) {
            turn(me, yawCounts, pitchCounts)
        }
        flick.yawDone += yawCounts
        flick.pitchDone += pitchCounts
    }

    /** MouseHandler.turnPlayer: degrees per mouse count before the * 0.15 (no spyglass, no cinematic camera). */
    private fun mouseFactor(): Double {
        val d = mc.options.sensitivity().get() * 0.6f.toDouble() + 0.2f.toDouble()
        return d * d * d * 8.0
    }

    /** Whole mouse counts closest to [degrees]. */
    private fun countsFor(degrees: Double): Int = (degrees / (mouseFactor() * 0.15f.toDouble())).roundToInt()

    /** Entity.turn: the rotation of [counts] mouse counts in one frame, with the client's float rounding. */
    private fun stepOf(counts: Int): Float = (counts * mouseFactor()).toFloat() * 0.15f

    /** Turns the real view by whole mouse counts. */
    private fun turn(me: LocalPlayer, yawCounts: Int, pitchCounts: Int) {
        if (yawCounts != 0) {
            me.yRot = me.yRot + stepOf(yawCounts)
        }
        if (pitchCounts != 0) {
            me.xRot = (me.xRot + stepOf(pitchCounts)).coerceIn(-90f, 90f)
        }
    }

    /** [total] counts split over [ticks] ticks, accelerating and braking (smoothstep). */
    private fun smoothSteps(total: Int, ticks: Int): IntArray {
        val steps = IntArray(ticks)
        var done = 0
        for (i in 1..ticks) {
            val t = i.toDouble() / ticks
            val cum = (total * t * t * (3 - 2 * t)).roundToInt()
            steps[i - 1] = cum - done
            done = cum
        }
        return steps
    }

    private fun uniform(range: Int): Double = (Random.nextDouble() - 0.5) * range
}
