package com.liquidaddon.aiming

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.random.Random

/**
 * The settings of [BlockAim], shown in the module menu as the collapsed `Aim` group.
 *
 * They are the Aimassist's aiming knobs, restricted to what a placement needs: how big a share of
 * the remaining angle is covered per tick ([strength]), the hard cap of a single tick ([maxSpeed]),
 * the response curve ([smoothing]), the mouse lattice ([gcdFix]/[gcdDither]) and how the rotation
 * reaches the server ([silent] + [moveFix]).
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

    /** `true` while a goal is set or the aim is still walking back. */
    val active: Boolean
        get() = goal != null || sent != null

    /** Asks the aim to go to [rotation]. Repeated calls with the same goal change nothing. */
    fun aimAt(rotation: Rotation) {
        val current = goal
        if (current != null && same(current, rotation)) {
            return
        }
        goal = rotation
        reached = false
        stable = 0
        releaseTicks = 0
    }

    /** Stops aiming: the aim walks back to the camera over the next ticks. */
    fun clear() {
        goal = null
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

        val share = (values.strength / 100f) * shape(abs(deltaYaw))
        var stepYaw = (deltaYaw * share).coerceIn(-values.maxSpeed, values.maxSpeed)
        var stepPitch = (deltaPitch * share).coerceIn(-values.maxSpeed, values.maxSpeed)

        // Never walk past the goal: a correction that overshoots is a wobble, and a wobble around a
        // block face means the click lands on a different face.
        if (abs(stepYaw) >= abs(deltaYaw)) stepYaw = deltaYaw
        if (abs(stepPitch) >= abs(deltaPitch)) stepPitch = deltaPitch

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
}
