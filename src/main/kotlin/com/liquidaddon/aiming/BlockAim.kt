package com.liquidaddon.aiming

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.deeplearn.DeepLearningEngine
import net.ccbluex.liquidbounce.deeplearn.ModelManager
import net.ccbluex.liquidbounce.deeplearn.data.CombatSample
import net.ccbluex.liquidbounce.deeplearn.models.TwoDimensionalRegressionModel
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.random.Random

/**
 * The settings of [BlockAim], shown in the module menu as the collapsed `Aim` group.
 *
 * They are the Aimassist's aiming knobs, restricted to what a placement needs: how big a share of
 * the remaining angle is covered per tick ([strength]), the hard cap of a single tick ([maxSpeed]),
 * the engine ([engine] — the curve, or LiquidBounce's already trained model with its `AI*` gains),
 * the hand noise ([jitter]), the mouse lattice ([gcdFix]/[gcdDither]) and how the rotation reaches
 * the server ([silent] + [moveFix]).
 *
 * Because it lives in a [ValueGroup] of its own, the main menu only shows the scaffolding settings:
 * the whole aim list stays folded under `Aim` until the arrow is clicked.
 */
internal class BlockAimValues : ValueGroup("Aim") {

    /** Aim the block before placing it. Off = the old behaviour, the crosshair decides. */
    val enabled by boolean("Enabled", true)

    /**
     * `true` (default): the rotation goes only to the server through `RotationManager`, so the
     * camera stays where the player looks — you can bridge while watching ahead. `false`: the
     * camera itself turns to the block, which is visible.
     */
    val silent by boolean("Silent", true)

    val moveFix by enumChoice("MoveFix", MoveFix.SILENT)

    /** Share of the remaining angle covered per tick. */
    val strength by float("Strength", 60f, 1f..100f, "%")

    /** Hard cap of a single tick, in degrees: how fast the aim may travel to the block. */
    val maxSpeed by float("MaxSpeed", 30f, 1f..180f, "°")

    val smoothing by enumChoice("Smoothing", Smoothing.BEZIER)

    /** Snap every step to whole mouse counts (and dither the rounding) like a real mouse. */
    val gcdFix by boolean("GcdFix", true)
    val gcdDither by boolean("GcdDither", true)

    /** A trace of hand noise on the step, in degrees — the Aimassist's `Jitter*`, one slider here. */
    val jitter by float("Jitter", 0.12f, 0f..2f, "°")

    /**
     * Who computes the step: `Math` is the curve above, `AI` is the combat model LiquidBounce
     * already trained on recorded human fights — the same one the Aimassist can use. Nothing is
     * trained here, and when the engine is not available the curve takes over.
     */
    val engine by enumChoice("Engine", Engine.MATH)
    val aiModel by enumChoice("AIModel", AiModel.MODEL_19KC8KP)
    val aiYawMultiplier by float("AIYawMultiplier", 1.5f, 0.5f..2f)
    val aiPitchMultiplier by float("AIPitchMultiplier", 1f, 0.5f..2f)

    /** Share of the remaining angle added on top of the model's answer, so the aim still arrives. */
    val aiCorrection by float("AICorrection", 30f, 0f..100f, "%")
}

/** Two rotations closer than this are the same rotation: no step is worth sending. */
private const val SAME = 0.02f

/** Inside this distance from the goal the step snaps onto it, so the server sees the exact aim. */
private const val SNAP = 0.35f

/** Release: the gap under which the way back to the camera is finished. */
private const val RELEASE_DONE = 0.75f

/**
 * How many ticks the goal has to stay published before a click may go out.
 *
 * `RotationManager.update()` runs at `FIRST_PRIORITY` on `GameTickEvent`, i.e. *before* the module's
 * handler: the rotation published at the end of tick N becomes `currentRotation` on tick N+1 and is
 * what the movement packet of N+1 carries. So the server only has the goal once tick N+1's packet
 * has gone out, and the click has to wait for tick N+2 — two stable ticks — to arrive *after* it.
 * Anything less and the click is evaluated by the server against the previous rotation, which is the
 * "post rotation" pattern.
 */
private const val REACHED_TICKS = 2

/**
 * Two goals closer than this are the same goal. The point the module aims at is recomputed from the
 * eye position every tick, so it drifts by a few hundredths of a degree: without this slack the goal
 * would be "new" on every tick, [BlockAim.stable] would never grow and the click would never come.
 */
private const val GOAL_SLACK = 0.75f

/** Throttle for the "the AI engine is not available" notice. */
private const val AI_NOTICE_MS = 15000L

/**
 * A release never lasts longer than this. If the player keeps turning while the aim walks back, the
 * gap never closes; after a few ticks the request is simply dropped (the server rotation catches up
 * with the camera on the next packet) instead of holding a high-priority rotation forever and
 * starving whatever else wants to aim.
 */
private const val RELEASE_MAX_TICKS = 6

/**
 * Aiming for a **block**, with the Aimassist's maths.
 *
 * It is the same idea as [AimassistCore] with an entity swapped for a point on a block face: the
 * module asks for a rotation ([aimAt]), this class walks the aim towards it tick by tick
 * ([tick]) — a share of the remaining angle per tick, capped by `MaxSpeed`, bent by `Smoothing`,
 * snapped to whole mouse counts — and publishes every intermediate rotation. `Silent` (default)
 * means the camera is never touched: only the rotation the server receives moves, which is what
 * lets the player look straight ahead while the module places blocks behind/below them.
 *
 * Placement needs one more thing than a smooth rotation: the server has to **already** have the
 * final rotation when the click arrives, otherwise the click is judged against the old aim and is
 * the classic "post rotation" pattern. So [reached] only becomes `true` a tick *after* the exact
 * goal rotation was published: by then the movement packet carrying it has been sent, and the
 * placement that follows is geometrically consistent on the server too.
 */
internal class BlockAim(
    private val owner: ClientModule,
    private val values: BlockAimValues,
) : MinecraftShortcuts {

    /** Where the aim is going, or `null` while it walks back to the camera. */
    private var goal: Rotation? = null

    /** The last rotation handed to the server (or to the camera, when not silent). */
    private var sent: Rotation? = null

    // Mouse-count residual, carried between ticks exactly like the Aimassist does.
    private var yawCarry = 0.0
    private var pitchCarry = 0.0

    /**
     * `true` when the rotation the server has is already the goal: it has been published and kept
     * for [REACHED_TICKS], so the movement packet that carried it has gone out. The click may go.
     */
    var reached = false
        private set

    /** Ticks the goal has been the published rotation without interruption. */
    private var stable = 0

    /** Ticks spent walking back to the camera, capped by [RELEASE_MAX_TICKS]. */
    private var releaseTicks = 0

    /** Where the goal points at, so the model can be told the distance. */
    private var goalPoint: Vec3? = null

    /** The aim the previous tick started from: the model's "previous vector". */
    private var previousAim: Rotation? = null

    private var aiReason: String? = null
    private var aiNoticeAt = 0L

    /** `true` while a goal is set or the aim is still walking back. */
    val active: Boolean
        get() = goal != null || sent != null

    /**
     * Asks the aim to go to [rotation], which looks at [point].
     *
     * A goal that is already there — or within [GOAL_SLACK], which is what the per-tick drift of a
     * recomputed point looks like — is not a new goal: it must not reset the "the server has it"
     * counter, otherwise the aim would keep arriving and never be allowed to click.
     */
    fun aimAt(rotation: Rotation, point: Vec3? = null) {
        val current = goal
        if (current != null && close(current, rotation)) {
            goalPoint = point ?: goalPoint
            return
        }
        goal = rotation
        goalPoint = point
        reached = false
        stable = 0
        releaseTicks = 0
    }

    /** Stops aiming: the aim walks back to the camera over the next ticks. */
    fun clear() {
        goal = null
        goalPoint = null
        reached = false
        stable = 0
        releaseTicks = 0
    }

    /** Forgets everything, for enable/disable. */
    fun reset() {
        goal = null
        sent = null
        reached = false
        stable = 0
        releaseTicks = 0
        goalPoint = null
        previousAim = null
        yawCarry = 0.0
        pitchCarry = 0.0
    }

    /**
     * One tick of the aim. Call it once per tick, before reading [reached]: it advances the step
     * while a goal is set, and walks the rotation back to the camera when the goal is gone.
     */
    fun tick() {
        val me = mc.player ?: return

        val target = goal
        if (target == null) {
            stable = 0
            reached = false
            release(me)
            return
        }
        releaseTicks = 0

        // Still the published rotation? Count how long, and only then consider the server reached.
        stable = if (sent?.let { same(it, target) } == true) stable + 1 else 0
        reached = stable >= REACHED_TICKS
        if (reached) {
            // Refresh the request without moving: a rotation that is dropped goes stale and the
            // manager would snap the server rotation back to the camera.
            publish(target)
            return
        }

        val from = current(me)
        val deltaYaw = Mth.wrapDegrees(target.yaw - from.yaw)
        val deltaPitch = target.pitch - from.pitch
        if (abs(deltaYaw) + abs(deltaPitch) <= SNAP) {
            publish(target)
            return
        }

        var stepYaw: Float
        var stepPitch: Float

        // `Engine = AI`: the combat model LiquidBounce already trained answers with the movement of
        // a whole human tick. It is trusted as much as `Strength` says and completed with
        // `AICorrection` of what is left, exactly like the Aimassist does; when the engine is not
        // there (it is downloaded on start-up) the curve below takes over without dropping a tick.
        val ai = if (values.engine == Engine.AI) aiStep(me, from, target) else null
        if (ai != null) {
            val trust = values.strength / 100f
            val correction = values.aiCorrection / 100f
            var aiYaw = ai.first * trust + deltaYaw * correction
            var aiPitch = ai.second * trust + deltaPitch * correction

            // An aim never walks away from the goal and never walks past it: whatever the model
            // suggested, the step stays inside the correction the curve would have made.
            if (deltaYaw != 0f && aiYaw * deltaYaw < 0f) aiYaw = 0f
            if (deltaPitch != 0f && aiPitch * deltaPitch < 0f) aiPitch = 0f
            stepYaw = if (abs(aiYaw) > abs(deltaYaw)) deltaYaw else aiYaw
            stepPitch = if (abs(aiPitch) > abs(deltaPitch)) deltaPitch else aiPitch
        } else {
            val share = (values.strength / 100f) * shape(abs(deltaYaw))
            stepYaw = deltaYaw * share
            stepPitch = deltaPitch * share
        }

        stepYaw = stepYaw.coerceIn(-values.maxSpeed, values.maxSpeed)
        stepPitch = stepPitch.coerceIn(-values.maxSpeed, values.maxSpeed)

        // Never walk past the goal: a correction that overshoots is a wobble, and a wobble around a
        // block face means the click lands on a different face.
        if (abs(stepYaw) >= abs(deltaYaw)) stepYaw = deltaYaw
        if (abs(stepPitch) >= abs(deltaPitch)) stepPitch = deltaPitch

        // A hand is never a control loop: a trace of noise, but only while there is still a way to
        // go — at the goal it would only make the face the click lands on jitter.
        if (values.jitter > 0f && abs(deltaYaw) + abs(deltaPitch) > 1f) {
            stepYaw += (Random.nextFloat() - 0.5f) * values.jitter
            stepPitch += (Random.nextFloat() - 0.5f) * values.jitter
        }

        var next = Rotation(from.yaw + stepYaw, (from.pitch + stepPitch).coerceIn(-89.9f, 89.9f))
        if (values.gcdFix) {
            val quantized = quantize(from, next)
            val moved = abs(Mth.wrapDegrees(quantized.yaw - from.yaw)) + abs(quantized.pitch - from.pitch)
            // At a low sensitivity a tiny step rounds to zero mouse counts and the aim would sit
            // still one count short of the face forever: on that tick the goal itself is taken.
            next = if (moved < 1.0e-3f) target else quantized
        }
        publish(next)
    }

    /**
     * The trained model's answer for this tick, in degrees, or `null` when it cannot answer.
     *
     * The input is a [CombatSample] like the one the Aimassist feeds it, with the block taking the
     * place of the entity: where the aim is ([from]), where it came from ([previousAim]), where it
     * wants to go ([goal]), how fast the player moves, and the distance to the point being aimed at.
     * There is no entity, so the target motion and the hurt time are zero.
     */
    private fun aiStep(me: LocalPlayer, from: Rotation, goal: Rotation): Pair<Float, Float>? {
        if (!DeepLearningEngine.isInitialized) {
            return aiUnavailable("deep learning not initialised")
        }
        val model = activeModel() ?: return aiUnavailable("no model '${values.aiModel.tag}' loaded")
        val previous = previousAim ?: from
        val point = goalPoint
        val distance = point?.let { me.eyePosition.distanceToSqr(it) }?.toFloat() ?: 0f

        // A predict can throw (closed model, translator failure) and must never reach the tick.
        val prediction = runCatching {
            model.predict(
                CombatSample(
                    currentVector = from.directionVector,
                    previousVector = previous.directionVector,
                    targetVector = goal.directionVector,
                    velocityDelta = previous.rotationDeltaTo(from).toVec2f(),
                    playerDiff = me.position().subtract(me.lastPos),
                    targetDiff = Vec3.ZERO,
                    distance = distance,
                    hurtTime = 0,
                    age = 0
                ).asInput
            )
        }
        val output = prediction.getOrNull()
            ?: return aiUnavailable(prediction.exceptionOrNull()?.javaClass?.simpleName ?: "model error")

        if (output.size < 2) return aiUnavailable("model returned ${output.size} values")
        val yaw = output[0] * values.aiYawMultiplier
        val pitch = output[1] * values.aiPitchMultiplier
        if (!yaw.isFinite() || !pitch.isFinite()) return aiUnavailable("model returned NaN")

        aiReason = null
        return yaw to pitch
    }

    /** The bundled model LiquidBounce ships for [values.aiModel], or the one selected in its menu. */
    private fun activeModel(): TwoDimensionalRegressionModel? = runCatching {
        val group = ModelManager.models
        group.modes.firstOrNull { model -> model.tag.equals(values.aiModel.tag, ignoreCase = true) }
            ?: group.activeMode
    }.getOrNull()

    /**
     * The model did not answer. The aim keeps working with the curve instead, and the reason is said
     * in chat (throttled) instead of leaving a silently degraded engine.
     */
    private fun aiUnavailable(reason: String): Pair<Float, Float>? {
        aiReason = reason
        val now = System.currentTimeMillis()
        if (now - aiNoticeAt >= AI_NOTICE_MS) {
            aiNoticeAt = now
            chat("§7[" + owner.name + "] §cAI engine: $reason §7- using ${values.smoothing.tag}")
        }
        return null
    }

    /** The rotation the aim starts the step from: what the server has, or the camera. */
    private fun current(me: LocalPlayer): Rotation =
        if (values.silent) RotationManager.currentRotation ?: sent ?: Rotation(me.yRot, me.xRot)
        else Rotation(me.yRot, me.xRot)

    /**
     * Steps the rotation back towards the camera after the goal is gone. Without it the manager
     * would hand the camera back the moment the request expires: a single packet turning as many
     * degrees as the module was away from the view, which is what every rotation check reports.
     */
    private fun release(me: LocalPlayer) {
        val from = sent ?: return
        if (!values.silent) {
            // The camera is the aim itself, nothing to put back.
            sent = null
            return
        }

        val camera = Rotation(me.yRot, me.xRot)
        val deltaYaw = Mth.wrapDegrees(camera.yaw - from.yaw)
        val deltaPitch = camera.pitch - from.pitch
        if (abs(deltaYaw) + abs(deltaPitch) <= RELEASE_DONE || ++releaseTicks > RELEASE_MAX_TICKS) {
            sent = null
            releaseTicks = 0
            return
        }

        val stepYaw = (deltaYaw * 0.5f).coerceIn(-values.maxSpeed, values.maxSpeed)
        val stepPitch = (deltaPitch * 0.5f).coerceIn(-values.maxSpeed, values.maxSpeed)
        publish(
            Rotation(from.yaw + stepYaw, (from.pitch + stepPitch).coerceIn(-89.9f, 89.9f)),
            // Walking back is not about the block any more: it must not outrank combat.
            Priority.IMPORTANT_FOR_USAGE_1
        )
    }

    /** Hands [rotation] to the server (silent) or to the camera directly. */
    private fun publish(rotation: Rotation, priority: Priority = Priority.IMPORTANT_FOR_PLAYER_LIFE) {
        // The model of the next tick reads this as "where the aim came from", i.e. how fast it is
        // already turning. Recorded before the write, so it is the aim the step started at.
        previousAim = sent ?: rotation
        sent = rotation

        if (!values.silent) {
            val me = mc.player ?: return
            // Never assign a wrapped absolute yaw: it would read as a ~360° jump in one packet.
            me.yRot += Mth.wrapDegrees(rotation.yaw - me.yRot)
            me.xRot = (me.xRot + (rotation.pitch - me.xRot)).coerceIn(-89.9f, 89.9f)
            return
        }

        // Aiming outranks combat (the block under the player is what keeps them alive), the way back
        // does not.
        RotationManager.setRotationTarget(plan(rotation), priority, owner)
    }

    /**
     * The plan handed to `RotationManager`: our own rotation, no processors, so the manager sends
     * exactly the step computed here instead of smoothing it a second time.
     */
    private fun plan(rotation: Rotation) = RotationTarget(
        rotation = rotation,
        ticksUntilReset = 4,
        resetThreshold = 0.5f,
        considerInventory = true,
        movementCorrection = when (values.moveFix) {
            MoveFix.OFF -> MovementCorrection.OFF
            MoveFix.STRICT -> MovementCorrection.STRICT
            MoveFix.SILENT -> MovementCorrection.SILENT
        },
    )

    /** The same response curve the Aimassist uses: big error, almost the full share. */
    private fun shape(deltaYaw: Float): Float {
        val t = (deltaYaw / 20f).coerceIn(0f, 1f)
        return when (values.smoothing) {
            Smoothing.LINEAR -> 1f
            Smoothing.BEZIER -> 0.55f + 0.45f * (t * t * (3f - 2f * t))
            Smoothing.SIGMOID -> 0.45f + 0.55f / (1f + exp(-6f * (t - 0.45f)))
        }
    }

    /**
     * Snaps the step to whole mouse counts and carries the rounding residual, so the deltas the
     * server receives are the ones a real mouse would produce at this sensitivity.
     */
    private fun quantize(from: Rotation, desired: Rotation): Rotation {
        val step = RotationUtil.gcd
        if (step < 1.0e-6) return desired

        val deltaYaw = RotationUtil.angleDifference(desired.yaw, from.yaw).toDouble()
        val deltaPitch = (desired.pitch - from.pitch).toDouble()

        val rawYaw = deltaYaw / step + (if (values.gcdDither) yawCarry else 0.0)
        val rawPitch = deltaPitch / step + (if (values.gcdDither) pitchCarry else 0.0)

        val countsYaw: Int
        val countsPitch: Int
        if (values.gcdDither) {
            countsYaw = wobbleRound(rawYaw)
            countsPitch = wobbleRound(rawPitch)
            yawCarry = (rawYaw - countsYaw).coerceIn(-0.95, 0.95)
            pitchCarry = (rawPitch - countsPitch).coerceIn(-0.95, 0.95)
        } else {
            countsYaw = round(rawYaw).toInt()
            countsPitch = round(rawPitch).toInt()
        }

        return Rotation(
            from.yaw + (countsYaw * step).toFloat(),
            (from.pitch + (countsPitch * step).toFloat()).coerceIn(-89.9f, 89.9f),
            isNormalized = true
        )
    }

    /** Nearest whole mouse count, with the decision boundary wobbling like a hand. */
    private fun wobbleRound(counts: Double): Int {
        val wobble = Random.nextDouble(-0.18, 0.18)
        return kotlin.math.floor(counts + 0.5 + wobble).toInt()
    }

    private fun same(a: Rotation, b: Rotation): Boolean =
        abs(Mth.wrapDegrees(a.yaw - b.yaw)) <= SAME && abs(a.pitch - b.pitch) <= SAME

    /** Same goal, with the slack a recomputed point needs (see [GOAL_SLACK]). */
    private fun close(a: Rotation, b: Rotation): Boolean =
        abs(Mth.wrapDegrees(a.yaw - b.yaw)) + abs(a.pitch - b.pitch) <= GOAL_SLACK
}
