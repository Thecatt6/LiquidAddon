package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.aiming.BlockAim
import com.liquidaddon.aiming.BlockAimValues
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.collisionShape
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.block.stateOrEmpty
import net.ccbluex.liquidbounce.utils.raytracing.traceFromPlayer
import net.ccbluex.liquidbounce.utils.render.placement.PlacementRenderer
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * ModuleScaffoldLegit con Range Sliders (stile LiquidBounce Nextgen UI).
 *
 * The blocks it places are drawn by LiquidBounce's own placement renderer ([PlacementRenderer], the
 * one the normal Scaffold uses): a box appears on the new block, animates its size, and fades out.
 * Its settings (size curves, in/out time, colours, clump) are in the `Render` group, and `Enabled`
 * there turns only the visual effect off.
 *
 * **The aim.** The module decides which block the bridge needs next, picks the best face of it (the
 * one whose centre or nearest point asks for the smallest turn and is still comfortably in reach),
 * and aims there with the Aimassist's maths ([BlockAim]): a share of the remaining angle per tick,
 * capped by `MaxSpeed`, bent by `Smoothing` — or, with `Aim > Engine = AI`, the combat model
 * LiquidBounce already trained — with `Jitter` for the hand noise and the step snapped to whole
 * mouse counts, residual carried over. The face chosen for a cell is kept until the cell changes, so
 * the aim converges instead of chasing a goal recomputed from the moving eye on every tick. Those settings live in the collapsed `Aim` group, so the module menu only shows the
 * scaffolding settings until the `Aim` arrow is clicked. The placement itself waits for
 * [BlockAim.reached], i.e. until the exact rotation has been on the server for a tick: the click can
 * never arrive before the aim, which is the "post rotation" pattern anticheats look for.
 *
 * **The view.** `Aim > Silent` (default) keeps the camera where the player looks: only the rotation
 * the server receives moves, so you can watch straight ahead while the blocks are placed behind and
 * below you. `MoveFix` keeps the walk direction correct under that silent rotation.
 *
 * **The click.** Every placement is that verified face — the block clicked must be a real support
 * (not air, not water or lava, a collision box, not replaceable) and the cell must be free. That is
 * what GrimAC's `AirLiquidPlace` tests, and it is what this module used to skip when it clicked
 * `mc.hitResult`, a value picked by the last rendered frame.
 *
 * **The box.** [PlacementRenderer] draws every block this module places, not only the ones of the
 * climb: the flat block of the bridge and the one on top of the step both get their box, and a
 * placement confirmed a tick later is caught by the pending watch.
 */
object ModuleScaffoldLegit : ClientModule("ScaffoldLegit", Categories.LIQUIDADDON) {

    /**
     * How much a face already chosen for the current cell is preferred over a new one. Big enough to
     * beat any turn/distance difference a tick can produce, small enough to still lose to an invalid
     * face (which is skipped before scoring).
     */
    private const val STICKY_BONUS = 1000.0

    enum class Mode(override val tag: String) : Tagged {
        STAIRS("Stairs"),
        STEP("Step")
    }

    enum class Timing(override val tag: String) : Tagged {
        EARLY("Early"),
        PEAK("Peak")
    }

    /** FLAT: the edge click; WALK: going back onto that block, then the jump; AIR: the click on top. */
    private enum class Phase { FLAT, WALK, AIR }

    private val mode by enumChoice("Mode", Mode.STAIRS)
    private val eagle by boolean("Eagle", true)

    // Range Slider per la distanza dal bordo (come nello screenshot: es. 0.38 - 0.6)
    private val edgeDistance by floatRange("Edge Distance", 0.38f..0.6f, 0.05f..1.0f)

    private val jumpAt by float("JumpAt", 0.1f, -0.3f..0.45f)
    private val placeTiming by enumChoice("PlaceTiming", Timing.PEAK)

    // Range Sliders con la doppia pallina per i Delay
    private val shiftDelay by intRange("Shift Delay", 0..2, 0..20, "ticks")
    private val jumpDelay by intRange("Jump Delay", 0..1, 0..20, "ticks")

    /**
     * The placement animation: the box that appears on the block the module just placed and fades
     * out again — the same effect (and the same settings) LiquidBounce's own Scaffold draws.
     *
     * It is [PlacementRenderer], the renderer the normal Scaffold uses, added here exactly like
     * there (`PlacementRenderer("Render", true, this, keep = false)`):
     *
     *  - `Enabled` — turn the whole effect off without touching the scaffolding itself;
     *  - `Clump` — merge neighbouring boxes so a fast bridge does not stack a hundred of them;
     *  - `StartSize` / `EndSize` and their curves — the box grows/shrinks between the two sizes;
     *  - `FadeInCurve` / `FadeOutCurve`, `InTime` / `OutTime` — how long it takes to appear and to
     *    fade away, and with which easing;
     *  - `Color` / `OutlineColor` — the fill and the outline (green by default, like the Scaffold).
     *
     * Every block that is actually placed goes in through `addBlock` from the placement callback, so
     * a click that fails never draws a box. Nothing is drawn for the blocks that were already there.
     */
    private val renderer = tree(PlacementRenderer("Render", true, this, keep = false))

    /**
     * The aiming settings, in a group of their own so the module menu stays readable: only the
     * `Aim` row is shown until its arrow is clicked, then the whole list opens (see [BlockAimValues]
     * — `Enabled`, `Silent`, `MoveFix`, `Strength`, `MaxSpeed`, `Smoothing`, `GcdFix`, `GcdDither`).
     */
    private val aim = tree(BlockAimValues())

    /** The aim itself: the Aimassist's step maths, pointed at a block face instead of an entity. */
    private val blockAim = BlockAim(this, aim)

    /**
     * The cells this module has clicked but whose block is not in the world yet, with the ticks they
     * have been waiting. It is what makes the box appear for *every* placed block: when the block
     * shows up (server confirmation, a tick later, the vanilla click path) the box is added then.
     */
    private val pending = LinkedHashMap<BlockPos, Int>()

    /** The cell currently being aimed at, so the module knows when the aim has to be redone. */
    private var aimingCell: BlockPos? = null

    /**
     * The placement already chosen for [aimingCell]. It is reused instead of being recomputed every
     * tick: the point it aims at is derived from the eye position, which moves, so recomputing it
     * would hand the aim a slightly different goal on every tick — the aim would keep arriving and
     * never be "there", and the click would never come.
     */
    private var chosen: Placement? = null

    /** Consecutive ticks at the edge without a usable placement for the walkway extension. */
    private var flatMisses = 0

    /**
     * The face the module committed to for [aimingCell] (support block + face). Keeping it for the
     * whole placement stops the scorer from flip-flopping between two equally good faces every tick:
     * the aim would never converge and no click would ever go out.
     */
    private var stickySupport: BlockPos? = null
    private var stickyFace: Direction? = null

    private val chatLog by boolean("ChatLog", true)
    private val debug by boolean("Debug", false)

    private var phase = Phase.FLAT
    /** The block placed at the edge (WALK) or jumped from (AIR). */
    private var stepBlock: BlockPos? = null
    private var waitTicks = 0
    private var readyTicks = 0
    private var delay = 0
    private var airTicks = 0
    private var placedInAir = false
    private var jumpNow = false
    private var spaceHeld = false
    private var lastKeys = DirectionalInput(forwards = false, backwards = false, left = false, right = false)
    /** The walking direction of the last keys (unit vector), for JumpAt. */
    private var walkX = 0.0
    private var walkZ = 0.0
    private var lastDebug = ""
    /** Why the last [findPlacement] refused; only shown by the debug line. */
    private var lastReject = ""

    private var steps = 0
    private var misses = 0
    private var airTickSum = 0

    override val tag: String
        get() = if (steps == 0) "" else steps.toString()

    override fun onEnabled() {
        reset()
        steps = 0
        misses = 0
        airTickSum = 0
        pending.clear()
        blockAim.reset()
        aimingCell = null
        super.onEnabled()
    }

    override fun onDisabled() {
        // Let the boxes that are still on screen run their fade-out instead of vanishing: this is
        // what the normal Scaffold does when it is turned off.
        pending.clear()
        aimingCell = null
        blockAim.clear()
        renderer.clearSilently()
        super.onDisabled()
    }

    private fun reset() {
        phase = Phase.FLAT
        stepBlock = null
        readyTicks = 0
        jumpNow = false
        aimingCell = null
        chosen = null
        flatMisses = 0
        blockAim.clear()
    }

    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> { event ->
        val me = mc.player ?: return@handler
        if (phase == Phase.AIR) {
            // the step is a jump straight up: no shift, no walking until the landing
            event.directionalInput = DirectionalInput(false, backwards = false, left = false, right = false)
            event.sneak = false
            if (mode == Mode.STAIRS) {
                event.jump = false
            }
            return@handler
        }
        lastKeys = event.directionalInput
        walkDirection(me, event.directionalInput)
        val userJump = event.jump
        spaceHeld = userJump
        if (mode == Mode.STAIRS) {
            if (!spaceHeld && phase != Phase.AIR) {
                reset() // space released: only Eagle
            }
            event.jump = false // the module jumps, at its moment
        }
        val startJump = me.onGround() && (jumpNow || (mode == Mode.STEP && userJump && phase != Phase.AIR))
        if (startJump) {
            // this jump is a step: the block under the feet gets one on top
            event.jump = true
            event.directionalInput = DirectionalInput(forwards = false, backwards = false, left = false, right = false)
            event.sneak = false
            jumpNow = false
            phase = Phase.AIR
            stepBlock = underFeet(me)
            airTicks = 0
            readyTicks = 0
            placedInAir = false
            delay = drawJumpDelay()
        } else if (eagle && me.onGround() && phase != Phase.AIR && atEdge(me, event.directionalInput)) {
            event.sneak = true
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player ?: return@handler
        flushPendingRender()
        if (phase == Phase.FLAT && !(mode == Mode.STAIRS && spaceHeld)) {
            debug(me, "spazio non premuto: solo Eagle")
            blockAim.clear()
        }
        // The phase decides where to aim (and whether the aim is there yet); the step runs after it,
        // so this tick's rotation is the one the movement packet of this tick carries — which is
        // what makes the click of the *next* tick land on a server that is already looking there.
        when (phase) {
            Phase.AIR -> airTick(me)
            Phase.FLAT -> if (mode == Mode.STAIRS && spaceHeld && me.onGround()) flatTick(me)
            Phase.WALK -> if (me.onGround()) walkTick(me) else idle()
        }
        blockAim.tick()
    }

    /** Nothing to aim at in this phase: let the aim walk back to the camera. */
    private fun idle() {
        aimingCell = null
        chosen = null
        stickySupport = null
        stickyFace = null
        blockAim.clear()
    }

    /** 1: at the edge, the block behind at the level of the one under the feet. */
    private fun flatTick(me: LocalPlayer) {
        if (!atEdge(me, lastKeys)) {
            debug(me, "piano: non sei sul bordo (tieni S verso il vuoto)")
            idle()
            readyTicks = 0
            return
        }
        val cell = flatCell(me) ?: run {
            debug(me, "piano: niente da piazzare (" + reason() + ")")
            idle()
            readyTicks = 0
            return
        }
        if (!aim.enabled) {
            // Aim off: the old behaviour, the crosshair has to be on a usable face.
            val legacy = crosshairPlacement(me, cell) ?: run {
                debug(me, "piano: mirino non sul blocco (" + reason() + ")")
                readyTicks = 0
                return
            }
            place(legacy)
            phase = Phase.WALK
            stepBlock = legacy.cell
            waitTicks = 0
            readyTicks = 0
            return
        }

        if (aimingCell != cell) {
            aimingCell = cell
            chosen = null
            stickySupport = null
            stickyFace = null
            readyTicks = 0
        }

        // The face chosen for this cell is kept until the cell changes: see [chosen].
        val placement = chosen ?: findPlacement(me, cell)?.also { chosen = it }
        if (placement == null) {
            // Nothing to extend the walkway with here (typically the side face of the block under
            // the feet, which is only visible when the eye hangs over the edge). Standing still and
            // waiting would never get the player up a level, so after a couple of attempts the
            // module takes the step instead: jump, then place the block on top of the one under the
            // feet — the climb the bridge needs.
            debug(me, "piano: nessuna faccia raggiungibile per " + posText(cell) + " (" + reason() + ")")
            if (++flatMisses >= 2 && !jumpNow) {
                debug(me, "piano: passo diretto, salto")
                jumpNow = true
            }
            return
        }
        flatMisses = 0

        blockAim.aimAt(placement.rotation, placement.point)
        debug(me, "piano: mirando al blocco " + posText(cell) + " (" + placement.text() + ")")
        // The click waits until the server is looking exactly there.
        if (!blockAim.reached) {
            return
        }
        if (readyTicks == 0) {
            delay = drawShiftDelay()
        }
        if (readyTicks++ < delay) {
            return
        }
        place(placement)
        phase = Phase.WALK
        stepBlock = cell
        waitTicks = 0
        readyTicks = 0
        aimingCell = null
        chosen = null
    }

    /**
     * 2: the jump onto the new block.
     *
     * The jump is what makes the player go *up*: it is triggered as soon as the feet are over the
     * new block — not only once the player is past its middle by [jumpAt], which a player who stops
     * walking never reaches and which used to leave the module stuck at the edge forever. If the
     * block could not be placed at all, the jump happens anyway: the air phase then puts the block
     * on top of the one under the feet, so the module climbs instead of stalling.
     */
    private fun walkTick(me: LocalPlayer) {
        val block = stepBlock
        // Walking over the block that was just placed: nothing to aim at.
        idle()
        if (++waitTicks > 20) {
            // Two seconds stuck here means the walk onto the block is not going to happen (a wall,
            // a stop, a wrong direction): take the step from where we are instead of looping.
            debug(me, "salita: niente da fare, salto da qui")
            jumpNow = true
            return
        }
        if (block == null) {
            jumpNow = true
            return
        }
        val past = (me.x - (block.x + 0.5)) * walkX + (me.z - (block.z + 0.5)) * walkZ
        val onIt = underFeet(me) == block
        if (onIt || past >= jumpAt || waitTicks >= 3) {
            debug(me, "salita: salto (sul blocco=$onIt, avanti=%.2f)".format(past))
            jumpNow = true
        } else {
            debug(me, "salita: aspetto di essere sul blocco nuovo")
        }
    }

    /** 3: in the air, the block on top of the one jumped from. */
    private fun airTick(me: LocalPlayer) {
        val from = stepBlock ?: return reset()
        if (me.onGround()) {
            idle()
            if (airTicks > 0) {
                if (placedInAir) {
                    steps++
                } else {
                    misses++
                }
                report(me)
                reset()
            }
            return
        }
        airTicks++
        if (placedInAir || airTicks > 16) {
            idle()
            return
        }

        val cell = from.above()
        if (!aim.enabled) {
            // Aim off: the crosshair has to be on the top face of the block that was jumped from.
            if (placeTiming == Timing.PEAK && me.deltaMovement.y > 0.0) {
                return
            }
            val legacy = crosshairPlacement(me, cell) ?: run {
                debug(me, "aria tick $airTicks: mirino non sul blocco sopra (" + reason() + ")")
                return
            }
            if (readyTicks++ < delay) {
                return
            }
            place(legacy)
            placedInAir = true
            airTickSum += airTicks
            return
        }

        // The aim starts while the jump is still rising — it only has a few ticks — but the block
        // itself is placed when the arc stops rising (PEAK) or at once (EARLY).
        if (aimingCell != cell) {
            aimingCell = cell
            chosen = null
            stickySupport = null
            stickyFace = null
            readyTicks = 0
        }
        val placement = chosen ?: findPlacement(me, cell)?.also { chosen = it } ?: run {
            debug(me, "aria tick $airTicks: nessuna faccia raggiungibile sopra " + posText(from) + " (" + reason() + ")")
            return
        }
        blockAim.aimAt(placement.rotation, placement.point)
        if (placeTiming == Timing.PEAK && me.deltaMovement.y > 0.0) {
            return
        }
        if (!blockAim.reached) {
            return
        }
        if (readyTicks++ < delay) {
            return
        }
        place(placement)
        debug(me, "aria tick $airTicks: blocco sopra " + posText(from) + " (" + placement.text() + ")")
        placedInAir = true
        airTickSum += airTicks
        aimingCell = null
        chosen = null
    }

    private fun report(me: LocalPlayer) {
        if (!chatLog) {
            return
        }
        if (!placedInAir) {
            me.sendSystemMessage(Component.literal("ยง7[EagleUpTest] gradino mancato: nessuna faccia del blocco "
                    + "raggiungibile sopra il gradino (controlla Aim > MaxSpeed / Range)"))
        } else if (steps % 5 == 0) {
            me.sendSystemMessage(Component.literal("ยง7[EagleUpTest] gradini $steps (mancati $misses), tick salto medio "
                    + "%.2f, %s, shift %d-%d, jump %d-%d".format(
                airTickSum.toDouble() / steps, placeTiming.tag,
                shiftDelay.first, shiftDelay.last, jumpDelay.first, jumpDelay.last
            )))
        }
    }

    /**
     * One verified click: the cell the block lands in, the raycast that proves the face and the
     * rotation that produces it. `place` gets this object, so the face validated here is exactly the
     * face sent to the server.
     */
    private class Placement(
        val cell: BlockPos,
        val hit: BlockHitResult,
        val rotation: Rotation,
        /** The point on the face the aim is pointed at (used by the AI engine for the distance). */
        val point: Vec3
    ) {
        /** Debug: which face of which block. */
        fun text(): String = hit.direction.name.lowercase() + " di " + posText(hit.blockPos)
    }

    /**
     * The best click that puts a block in [cell].
     *
     * Every neighbour of [cell] is a candidate support: the block is placed by clicking the face of a
     * neighbour that points at the cell. A candidate counts only if
     *  - it is a real support: not air, not water or lava (no collision box), not replaceable —
     *    exactly what GrimAC's `AirLiquidPlace` tests on the clicked position;
     *  - the point aimed at is inside reach and the ray from the eyes actually lands on that block
     *    and that face (so the client and the server agree on what was clicked);
     *  - putting the block there is legal: the cell is free and does not intersect the player.
     *
     * The winner is the cheapest one to *aim at*: the smallest turn away from where the server is
     * currently looking, plus a small penalty for distance. That is the "best part of the block" for
     * a bridge — the face you would have clicked yourself — instead of a fixed face that sometimes
     * asks for a 90° flick (and sometimes is not visible at all).
     */
    private fun findPlacement(me: LocalPlayer, cell: BlockPos): Placement? {
        val level = mc.level ?: return null

        if (!level.getBlockState(cell).canBeReplaced()) {
            lastReject = "la cella è già occupata"
            return null
        }
        val cellBox = AABB(cell)
        if (me.boundingBox.intersects(cellBox)) {
            lastReject = "la cella è dentro il giocatore"
            return null
        }

        val eye = me.eyePosition
        val reach = me.blockInteractionRange()
        val current = if (aim.silent) {
            RotationManager.currentRotation ?: Rotation(me.yRot, me.xRot)
        } else {
            Rotation(me.yRot, me.xRot)
        }

        var best: Placement? = null
        var bestScore = Double.MAX_VALUE

        for (direction in Direction.entries) {
            val support = cell.relative(direction)
            val state = support.stateOrEmpty
            // Solid, not a fluid (empty collision shape) and not something vanilla would replace.
            if (state.isAir || state.canBeReplaced() || support.collisionShape.isEmpty) {
                continue
            }

            val face = direction.opposite
            if (support.relative(face) != cell) {
                continue
            }

            for (point in candidatePoints(support, face, eye)) {
                val distance = eye.distanceTo(point)
                if (distance > reach) {
                    continue
                }
                val rotation = Rotation.lookingAt(point, eye)
                // The ray has to land on this face of this block: that is what the server checks.
                val traced = traceFromPlayer(rotation)
                if (traced.type != HitResult.Type.BLOCK) continue
                if (traced.blockPos != support || traced.direction != face) continue

                // A face already chosen for this cell wins over an equally good one, so the aim
                // does not jump between two candidates tick after tick.
                val sticky = support == stickySupport && face == stickyFace
                val score = current.directionAngleTo(rotation) + distance * 0.35 -
                    (if (sticky) STICKY_BONUS else 0.0)
                if (score < bestScore) {
                    bestScore = score
                    best = Placement(cell, traced, rotation, point)
                }
            }
        }

        if (best == null) {
            lastReject = "nessuna faccia del blocco è raggiungibile"
            return null
        }
        lastReject = ""
        stickySupport = best.hit.blockPos
        stickyFace = best.hit.direction
        return best
    }

    /**
     * The points worth aiming at on [face]: its centre, and the point of the face closest to the
     * eyes. The second one keeps the click inside reach when the player is right next to the block;
     * it is pulled a little off the edges so the ray never grazes the neighbouring block. Both are
     * scored by [findPlacement] and the better one wins.
     */
    private fun candidatePoints(support: BlockPos, face: Direction, eye: Vec3): List<Vec3> {
        val center = Vec3.atCenterOf(support).relative(face, 0.5)
        val margin = 0.09
        fun clamp(value: Double, min: Double) = value.coerceIn(min + margin, min + 1.0 - margin)

        val x = clamp(eye.x, support.x.toDouble())
        val y = clamp(eye.y, support.y.toDouble())
        val z = clamp(eye.z, support.z.toDouble())
        val nearest = when (face.axis) {
            Direction.Axis.X -> Vec3(center.x, y, z)
            Direction.Axis.Y -> Vec3(x, center.y, z)
            Direction.Axis.Z -> Vec3(x, y, center.z)
        }
        return if (nearest.distanceTo(center) < 1.0e-4) listOf(center) else listOf(center, nearest)
    }

    /**
     * With `Aim > Enabled` off: the click the use key would send right now, along the crosshair, if
     * it happens to put the block in [cell]. Kept because it is the behaviour the module had before
     * the aim existed, and it is the fallback when the aim cannot find a face.
     */
    private fun crosshairPlacement(me: LocalPlayer, cell: BlockPos): Placement? {
        val rotation = RotationManager.currentRotation ?: Rotation(me.yRot, me.xRot)
        val hit = traceFromPlayer(rotation)
        if (hit.type != HitResult.Type.BLOCK) {
            lastReject = "il mirino non tocca nessun blocco"
            return null
        }
        val support = hit.blockPos
        val state = support.stateOrEmpty
        if (state.isAir || support.collisionShape.isEmpty || state.canBeReplaced()) {
            lastReject = "il blocco cliccato non è un appoggio (aria o liquido)"
            return null
        }
        if (support.relative(hit.direction) != cell) {
            lastReject = "il mirino mette il blocco in un'altra cella"
            return null
        }
        lastReject = ""
        return Placement(cell, hit, rotation, hit.location)
    }

    /** Debug: a position, short. */
    private fun posText(pos: BlockPos): String = pos.x.toString() + " " + pos.y + " " + pos.z

    /** Debug: why the click was refused. */
    private fun reason(): String = lastReject.ifEmpty { "nessuna cella valida" }

    /** Debug: one chat line whenever what the module waits for changes. */
    private fun debug(me: LocalPlayer, text: String) {
        if (debug && text != lastDebug) {
            lastDebug = text
            me.sendSystemMessage(Component.literal("ยง8[EagleUpTest D] ยง7$text"))
        }
    }

    /** The walking direction of [keys] in the world (Entity.moveRelative), or nothing if no key. */
    private fun walkDirection(me: LocalPlayer, keys: DirectionalInput) {
        val forward = (if (keys.forwards) 1.0 else 0.0) - (if (keys.backwards) 1.0 else 0.0)
        val strafe = (if (keys.left) 1.0 else 0.0) - (if (keys.right) 1.0 else 0.0)
        val length = hypot(forward, strafe)
        if (length < 1e-6) {
            return
        }
        val yaw = Math.toRadians(me.yRot.toDouble())
        walkX = (strafe * cos(yaw) - forward * sin(yaw)) / length
        walkZ = (forward * cos(yaw) + strafe * sin(yaw)) / length
    }

    /**
     * Places the block of [click] — the cell [findPlacement] validated, with the very hit result and
     * rotation it traced — and hands the block to the [renderer], which draws the box that appears on
     * it and fades out, exactly like LiquidBounce's own Scaffold.
     *
     * The placement goes through LiquidBounce's `doPlacement`, so the block lands on the tick the
     * module decided on and the swing is reported the way vanilla does. `onPlacementSuccess` runs
     * only when the placement really happened: that is where the box is added, so a click the server
     * or the client refuses never leaves a phantom box behind.
     *
     * The cell is put in [pending] **before** the call: if the block only shows up a tick later
     * (server confirmation) or the click goes through the vanilla path, [flushPendingRender] still
     * draws its box. That is what makes the effect appear on *every* block the module places, not
     * just the ones of the climb.
     */
    private fun place(click: Placement) {
        val cell = click.cell
        // Watched from before the call: if the block only appears a tick later, the box is drawn by
        // [flushPendingRender] once it is really there.
        pending[cell] = 0
        // The face was chosen a few ticks ago and the player has moved since: the click has to be
        // traced again from where the eyes are now, along the very rotation the server has, or the
        // click would point at a spot the server cannot see from the position it has. The cached
        // face is kept as long as the new ray ends on it.
        val hit = runCatching {
            val fresh = traceFromPlayer(click.rotation)
            if (fresh.type == HitResult.Type.BLOCK && fresh.blockPos == click.hit.blockPos &&
                fresh.direction == click.hit.direction
            ) {
                fresh
            } else {
                click.hit
            }
        }.getOrDefault(click.hit)

        runCatching {
            doPlacement(hit, click.rotation, onPlacementSuccess = {
                pending.remove(cell)
                renderer.addBlock(cell)
                true
            })
        }
    }

    /**
     * Draws the box for the placements that did not confirm on the spot: as soon as the block is
     * really in the world the box is added, so a refused click cannot render anything, and after
     * half a second the cell is forgotten instead of waiting forever.
     */
    private fun flushPendingRender() {
        if (pending.isEmpty()) {
            return
        }
        val level = mc.level ?: return
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val cell = entry.key
            entry.setValue(entry.value + 1)
            if (!level.getBlockState(cell).canBeReplaced()) {
                renderer.addBlock(cell)
                iterator.remove()
                continue
            }
            if (entry.value > 10) {
                iterator.remove()
            }
        }
    }

    // Estrae un ritardo casuale nell'intervallo dello slider
    private fun drawShiftDelay(): Int {
        val min = shiftDelay.first
        val max = shiftDelay.last
        return if (max > min) Random.nextInt(min, max + 1) else min
    }

    private fun drawJumpDelay(): Int {
        val min = jumpDelay.first
        val max = jumpDelay.last
        return if (max > min) Random.nextInt(min, max + 1) else min
    }

    // Estrae un valore casuale di EdgeDistance nell'intervallo dello slider
    private fun getEdgeDistance(): Float {
        val min = edgeDistance.start
        val max = edgeDistance.endInclusive
        return if (max > min) Random.nextDouble(min.toDouble(), max.toDouble()).toFloat() else min
    }

    /** The block the player is standing on: the one under the feet, not the one they are inside. */
    private fun underFeet(me: LocalPlayer): BlockPos = BlockPos.containing(me.x, me.y - 0.5, me.z)

    /**
     * The block the bridge needs next: one block ahead along the walk, at the level of the block
     * under the feet — the one the player is about to step onto. `null` when it is already there,
     * when there is no walk direction, or when it would be inside the player.
     */
    private fun flatCell(me: LocalPlayer): BlockPos? {
        val length = hypot(walkX, walkZ)
        if (length < 1.0e-6) {
            return null
        }
        val dx = walkX / length
        val dz = walkZ / length
        val level = mc.level ?: return null
        val feetY = floor(me.y + 1.0e-3).toInt()
        val ahead = BlockPos.containing(me.x + dx * 0.9, me.y - 0.5, me.z + dz * 0.9)

        // Only the walkway level, never a block to trip over.
        if (ahead.y > feetY - 1) {
            lastReject = "il blocco andrebbe sopra i piedi"
            return null
        }
        if (!level.getBlockState(ahead).canBeReplaced()) {
            lastReject = "il blocco davanti c'è già"
            return null
        }
        if (me.boundingBox.intersects(AABB(ahead))) {
            lastReject = "la cella è dentro il giocatore"
            return null
        }
        return ahead
    }

    /** Eagle: the block below the point EdgeDistance ahead, along where the keys push, is air. */
    private fun atEdge(me: LocalPlayer, keys: DirectionalInput): Boolean {
        val forward = (if (keys.forwards) 1.0 else 0.0) - (if (keys.backwards) 1.0 else 0.0)
        val strafe = (if (keys.left) 1.0 else 0.0) - (if (keys.right) 1.0 else 0.0)
        val length = hypot(forward, strafe)
        if (length < 1e-6) {
            return false
        }
        val yaw = Math.toRadians(me.yRot.toDouble())
        val dx = (strafe * cos(yaw) - forward * sin(yaw)) / length
        val dz = (forward * cos(yaw) + strafe * sin(yaw)) / length
        val level = mc.level ?: return false
        val currentDistance = getEdgeDistance()
        val below = BlockPos.containing(me.x + dx * currentDistance, me.y - 0.5, me.z + dz * currentDistance)
        return level.getBlockState(below).isAir
    }
}