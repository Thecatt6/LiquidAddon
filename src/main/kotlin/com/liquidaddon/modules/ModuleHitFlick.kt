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
import net.minecraft.core.BlockPos
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * HitFlick "mode 2", with the normal movement packets.
 * Includes "Smart Aim": calculates knockback trajectory to push enemies into the void/off bridges.
 *
 * Settings:
 *   Preset           ready configs (Custom = the sliders below)
 *   OnlyExtraKnockback  flick only hits with extra knockback (sprint hit or Knockback weapon)
 *   SmartAim         decide the flick side based on where the target would fall
 *   SmartOnly        if true, skip the flick when there is no drop advantage (normal hit)
 *   SmartDepth       how many air blocks below count as a useful drop
 *   SmartDistance    how far ahead (blocks) the knockback landing is estimated
 *   Silent           the camera stays still, only the server sees the flick
 *   MoveFix          Silent only: Off, Strict or Silent
 *   Rotation         Smooth or AI
 *   Return           Instant or AI
 *   FlickAngle       degrees turned away
 *   FlickTicks       Smooth: ticks to turn away
 *   AIModel / AI*    AI aim parameters
 *   WaitTicks        Silent: wait for crosshair back on target
 *   RandomizeOffset  random range around FlickAngle
 *   ReturnError      max error of the instant way back
 *   PitchNoise       pitch movement during the flick
 *   Chance           chance (%) that a valid hit becomes a flick
 *   FlickDelay       minimum ms between two flicks
 *   Alternate        alternate right and left (overridden by SmartAim when useful)
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

    // --- SMART AIM ---
    private val smartAim by boolean("SmartAim", true)
    private val smartOnly by boolean("SmartOnly", true)
    private val smartDepth by int("SmartDepth", 3, 1..10, "blocks")
    private val smartDistance by float("SmartDistance", 1.5f, 1f..3f, "blocks")

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

    private var hitYawCounts = 0
    private var hitPitchCounts = 0
    private var spoofing = false
    private var spoofedMovement = false
    private var realYaw = 0f
    private var realPitch = 0f
    private var realYawO = 0f
    private var realPitchO = 0f
    private var realHeadYawO = 0f

    private val clickCount: Field by lazy {
        KeyMapping::class.java.getDeclaredField("clickCount").apply { isAccessible = true }
    }

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
                turn(me, -flick.yawDone, -flick.pitchDone)
            }
        }
        pending = null
        hitYawCounts = 0
        hitPitchCounts = 0
        super.onDisabled()
    }

    @Suppress("unused")
    private val movementHandler = handler<PlayerMovementTickEvent> {
        val me = mc.player ?: return@handler
        if (silent && moveFix != MoveFix.OFF) {
            beginSpoof(me, true)
        }
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
            if (silent) {
                beginSpoof(me, false)
            }
        } else {
            endSpoof(me)
        }
    }

    private fun spoofCounts(): Pair<Int, Int> {
        val flick = pending
        return if (flick != null) flick.yawDone to flick.pitchDone else hitYawCounts to hitPitchCounts
    }

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
        realYawO = me.yRotO
        realPitchO = me.xRotO
        realHeadYawO = me.yHeadRotO
        me.yRot = realYaw + stepOf(yawCounts)
        me.xRot = (realPitch + stepOf(pitchCounts)).coerceIn(-90f, 90f)
    }

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

    private fun silentInput(input: DirectionalInput, realYaw: Float, sentYaw: Float): DirectionalInput {
        val forward = (if (input.forwards) 1 else 0) - (if (input.backwards) 1 else 0)
        val strafe = (if (input.left) 1 else 0) - (if (input.right) 1 else 0)
        if (forward == 0 && strafe == 0) {
            return input
        }
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
        endSpoof(me)
        hitYawCounts = 0
        hitPitchCounts = 0

        val flick = pending
        if (flick != null) {
            dropClicks()
            if (flick.waited > 0) {
                finishInstant(me, flick, flick.totalTicks + flick.waited)
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

        val target = mc.crosshairPickEntity as? LivingEntity ?: return@handler
        if (!target.isAlive) {
            return@handler
        }

        val reason = extraKnockback(me)
        if (onlyExtraKnockback && reason == null) {
            return@handler
        }

        val now = System.currentTimeMillis()
        if (now - lastFlickAt < flickDelay || Random.nextInt(100) >= chance) {
            return@handler
        }

        if (!mc.options.keyAttack.consumeClick()) {
            return@handler
        }
        dropClicks()

        // --- SMART AIM: sceglie dove buttare l'avversario ---
        val angle = max(1.0, flickAngle + uniform(randomizeOffset))
        var chosenSide = side
        var shouldFlick = true

        if (smartAim) {
            val rightYaw = me.yRot + angle.toFloat()
            val leftYaw = me.yRot - angle.toFloat()

            val dropRight = getDropDepth(target, rightYaw)
            val dropLeft = getDropDepth(target, leftYaw)

            when {
                dropRight >= smartDepth && dropLeft < smartDepth -> chosenSide = 1   // forza destra
                dropLeft >= smartDepth && dropRight < smartDepth -> chosenSide = -1  // forza sinistra
                dropRight >= smartDepth && dropLeft >= smartDepth -> chosenSide = side // entrambi vuoti: alterna
                else -> {
                    // nessun vantaggio (pavimento piatto)
                    if (smartOnly) {
                        shouldFlick = false
                    } else {
                        chosenSide = side
                    }
                }
            }
        }

        if (!shouldFlick) {
            // hit normale, senza flick
            queueClick()
            return@handler
        }

        lastFlickAt = now
        val signedAngle = chosenSide * angle

        if (alternate) {
            if (!smartAim || chosenSide == side) {
                side = -side
            } else {
                // sincronizza l'alternanza col lato forzato da SmartAim
                side = -chosenSide
            }
        }

        val model = if (rotationMode == RotationMode.AI || returnMode == ReturnMode.AI) model() else null
        if (model == null && (rotationMode == RotationMode.AI || returnMode == ReturnMode.AI) && !aiWarned) {
            aiWarned = true
            me.sendSystemMessage(Component.literal(
                "§c[HitFlick] AI non disponibile (il motore deep learning di LiquidBounce non e' avviato o il modello manca): uso Smooth e ritorno Instant"
            ))
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
            tickAi(me, newFlick)
            return@handler
        }

        var total = countsFor(signedAngle)
        if (total == 0) {
            total = chosenSide
        }

        val newFlick = Flick(
            target,
            smoothSteps(total, flickTicks),
            smoothSteps(countsFor(uniform(pitchNoise)), flickTicks),
            reason ?: "nessun extra"
        )
        newFlick.ai = model != null && returnMode == ReturnMode.AI
        newFlick.lastYaw = me.yRot
        newFlick.lastPitch = me.xRot
        newFlick.beforeYaw = me.yRotO
        newFlick.beforePitch = me.xRotO
        pending = newFlick
        apply(me, newFlick, newFlick.yawSteps[0], newFlick.pitchSteps[0])
        newFlick.next = 1
        newFlick.totalTicks = 1
        newFlick.returning = newFlick.yawSteps.size == 1
        remember(me, newFlick)
    }

    /**
     * SmartAim: quanti blocchi di vuoto ci sono sotto il punto in cui il target verrebbe spinto
     * lungo [yaw], a distanza SmartDistance.
     */
    private fun getDropDepth(target: LivingEntity, yaw: Float): Int {
        val level = mc.level ?: return 0
        val dist = smartDistance.toDouble()
        val rad = Math.toRadians(yaw.toDouble())

        // Minecraft: +Z Sud, +X Est; direzione look = (-sin(yaw), cos(yaw))
        val dx = -sin(rad) * dist
        val dz = cos(rad) * dist

        val checkX = target.x + dx
        val checkZ = target.z + dz
        val startY = floor(target.y).toInt()

        var depth = 0
        for (i in 0..smartDepth) {
            val pos = BlockPos.containing(checkX, (startY - i).toDouble(), checkZ)
            val state = level.getBlockState(pos)
            if (state.isAir || state.getCollisionShape(level, pos).isEmpty) {
                depth++
            } else {
                break
            }
        }
        return depth
    }

    private fun viewYaw(me: LocalPlayer, flick: Flick): Float =
        if (silent) me.yRot else me.yRot - stepOf(flick.yawDone)

    private fun viewPitch(me: LocalPlayer, flick: Flick): Float =
        if (silent) me.xRot else me.xRot - stepOf(flick.pitchDone)

    private fun sentYaw(me: LocalPlayer, flick: Flick): Float =
        viewYaw(me, flick) + stepOf(flick.yawDone)

    private fun sentPitch(me: LocalPlayer, flick: Flick): Float =
        (viewPitch(me, flick) + stepOf(flick.pitchDone)).coerceIn(-90f, 90f)

    private fun tickAi(me: LocalPlayer, flick: Flick) {
        val baseYaw = viewYaw(me, flick)
        val basePitch = viewPitch(me, flick)
        flick.totalTicks++
        if (!flick.returning) {
            if (flick.next < flick.yawSteps.size) {
                apply(me, flick, flick.yawSteps[flick.next], flick.pitchSteps[flick.next])
                flick.next++
                flick.returning = flick.next >= flick.yawSteps.size
                remember(me, flick)
                return
            }
            val goalYaw = baseYaw + flick.yawOff
            val goalPitch = (basePitch + flick.pitchOff).coerceIn(-90f, 90f)
            val step = aiStep(me, flick, goalYaw, goalPitch) ?: run {
                finishInstant(me, flick, flick.totalTicks)
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
        val turned = abs(stepOf(flick.yawDone))
        apply(me, flick, step.first, step.second)
        flick.aiTicks++
        remember(me, flick)
        if (backOnTarget(me, flick)) {
            pending = null
            hit(me, flick, flick.totalTicks, turned)
        }
    }

    private fun backOnTarget(me: LocalPlayer, flick: Flick): Boolean {
        if (!silent) {
            pickMethod?.let { method -> runCatching { method.invoke(mc, 1.0f) } }
            return mc.crosshairPickEntity === flick.target
        }
        val eyes = me.eyePosition
        val box = flick.target.boundingBox
        val reach = eyes.add(
            Rotation(sentYaw(me, flick), sentPitch(me, flick)).directionVector
                .scale(me.entityInteractionRange())
        )
        return box.contains(eyes) || box.clip(eyes, reach).isPresent
    }

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
            hitYawCounts = errorYaw
            hitPitchCounts = errorPitch
        } else {
            turn(me, -flick.yawDone + errorYaw, -flick.pitchDone + errorPitch)
        }
        hit(me, flick, ticks, turned)
    }

    private fun crosshairOn(flick: Flick): Boolean {
        pickMethod?.let { method -> runCatching { method.invoke(mc, 1.0f) } }
        return mc.crosshairPickEntity === flick.target
    }

    private fun hit(me: LocalPlayer, flick: Flick, ticks: Int, turned: Float) {
        if (!flick.target.isAlive) {
            return
        }
        queueClick()
        count++
        if (chatLog) {
            val away = if (flick.yawSteps.isEmpty()) "AI" else "smooth"
            val back = if (flick.ai && returnMode == ReturnMode.AI && model() != null) "AI" else "instant"
            val view = (if (silent) ", silent" else "") +
                    (if (flick.waited > 0) ", atteso ${flick.waited} tick" else "") +
                    ", preset ${preset.tag}"
            me.sendSystemMessage(Component.literal(
                "§7[HitFlick] colpo $count (${flick.reason}), yaw girato di " +
                        "%.2f gradi in %d tick (andata %s, ritorno %s%s)".format(turned, ticks, away, back, view)
            ))
        }
    }

    private fun remember(me: LocalPlayer, flick: Flick) {
        flick.beforeYaw = flick.lastYaw
        flick.beforePitch = flick.lastPitch
        flick.lastYaw = sentYaw(me, flick)
        flick.lastPitch = sentPitch(me, flick)
    }

    private fun model(): TwoDimensionalRegressionModel? = runCatching {
        if (!DeepLearningEngine.isInitialized) {
            return@runCatching null
        }
        val group = ModelManager.models
        group.modes.firstOrNull { it.tag.equals(aiModel.tag, ignoreCase = true) } ?: group.activeMode
    }.getOrNull()

    private fun aiStep(me: LocalPlayer, flick: Flick, goalYaw: Float, goalPitch: Float): Pair<Int, Int>? {
        val model = model() ?: return null
        val current = Rotation(sentYaw(me, flick), sentPitch(me, flick))
        val previous = Rotation(flick.beforeYaw, flick.beforePitch)
        val goal = Rotation(goalYaw, goalPitch)
        val target = flick.target
        val output = runCatching {
            val sample = CombatSample(
                current.directionVector, previous.directionVector, goal.directionVector,
                previous.rotationDeltaTo(current).toVec2f(), me.position().subtract(me.lastPos),
                target.position().subtract(target.lastPos), me.squaredBoxedDistanceTo(target).toFloat(),
                target.hurtTime, 0
            )
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
        return countsFor(Mth.wrapDegrees(yaw - current.yaw).toDouble()) to
                countsFor((pitch - current.pitch).toDouble())
    }

    private fun extraKnockback(me: LocalPlayer): String? {
        if (me.isSprinting && me.getAttackStrengthScale(0.5f) > 0.9f) {
            return "sprint"
        }
        if (EnchantmentHelper.has(me.mainHandItem, EnchantmentEffectComponents.KNOCKBACK)
            || me.getAttributeValue(Attributes.ATTACK_KNOCKBACK) > 0.0
        ) {
            return "contraccolpo"
        }
        return null
    }

    private fun dropClicks() {
        while (mc.options.keyAttack.consumeClick()) {
            // eaten
        }
    }

    private fun queueClick() {
        val key = mc.options.keyAttack
        clickCount.setInt(key, clickCount.getInt(key) + 1)
    }

    private fun apply(me: LocalPlayer, flick: Flick, yawCounts: Int, pitchCounts: Int) {
        if (!silent) {
            turn(me, yawCounts, pitchCounts)
        }
        flick.yawDone += yawCounts
        flick.pitchDone += pitchCounts
    }

    private fun mouseFactor(): Double {
        val d = mc.options.sensitivity().get() * 0.6f.toDouble() + 0.2f.toDouble()
        return d * d * d * 8.0
    }

    private fun countsFor(degrees: Double): Int =
        (degrees / (mouseFactor() * 0.15f.toDouble())).roundToInt()

    private fun stepOf(counts: Int): Float =
        (counts * mouseFactor()).toFloat() * 0.15f

    private fun turn(me: LocalPlayer, yawCounts: Int, pitchCounts: Int) {
        if (yawCounts != 0) {
            me.yRot = me.yRot + stepOf(yawCounts)
        }
        if (pitchCounts != 0) {
            me.xRot = (me.xRot + stepOf(pitchCounts)).coerceIn(-90f, 90f)
        }
    }

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