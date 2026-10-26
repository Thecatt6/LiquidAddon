package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.aiming.BlockAim
import com.liquidaddon.aiming.BlockAimValues
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
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
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
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
 * ModuleScaffoldLegit — the bridge and the stair climb of LiquidBounce's own Scaffold, with a
 * **simple aim** and the placement render.
 *
 * **The aim is the simple one.** The module decides which cell the bridge needs next, takes the
 * support block closest to the player (in a bridge that is the block under the feet) and asks the
 * aim for the **centre of the face** that cell shares with it: the crosshair sits in the middle of
 * the block, not on an edge or a corner. Same cell, same click, every tick — no scoring between
 * faces, so the aim cannot flip from one face to another and never gets stuck.
 *
 * The rotation is still the Aimassist's ([BlockAim]): a share of the remaining angle per tick,
 * capped by `MaxSpeed`, bent by `Smoothing`, `Jitter` for the hand noise, snapped to whole mouse
 * counts — or, with `Aim > Engine = AI`, LiquidBounce's already trained combat model. All of it in
 * the collapsed `Aim` group. `Silent` (default) keeps the camera free: only the rotation the server
 * receives moves.
 *
 * The click waits for the rotation the **server already has** (`RotationManager.serverRotation`,
 * written from the packets that really went out) to be on that face, then places with the very hit
 * and rotation that were planned: no click justified by a rotation the server has not received yet.
 *
 * **The rest is the old behaviour**: `Eagle` holds the player on the edge, the flat block of the
 * bridge is placed at the edge, `Mode = STAIRS` puts the block on top of it and jumps, `Mode = STEP`
 * jumps on the physical space key, `Shift Delay` / `Jump Delay` / `Edge Distance` / `PlaceTiming`
 * work as before.
 *
 * **The render** is [PlacementRenderer], the renderer LiquidBounce's own Scaffold uses: a box
 * appears on every block that is really placed and fades out (`Render` group: `Enabled`, `Clump`,
 * size curves, `InTime`/`OutTime`, colours).
 */
object ModuleScaffoldLegit : ClientModule("ScaffoldLegit", Categories.LIQUIDADDON) {

    /**
     * How close the rotation the server already has must be to the planned one before the click is
     * sent, in degrees. The click is placed with the planned hit and rotation; this is what makes
     * sure the server is looking there when it arrives.
     */
    private const val AIM_TOLERANCE = 1.2f

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

    /**
     * The aim is stepped here, on `RotationUpdateEvent`: `RotationManager` fires it just before it
     * computes the rotation of the tick, so the step published now is the `currentRotation` of this
     * very tick and rides this tick's movement packet. Stepped one handler later it would always be
     * a tick behind, and the click would arrive before the rotation that justifies it.
     */
    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        blockAim.tick()
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
    }

    /**
     * `true` when the rotation the server already has is the one that looks at the block.
     *
     * It is read from `RotationManager.serverRotation`, which is written from the outgoing movement
     * packets: if the last packet carried the planned rotation, a click arriving now is judged with
     * it — the "post rotation" order. Judging by a tick counter instead would be a guess; this is
     * the state of the server.
     */
    private fun aimed(me: LocalPlayer, rotation: Rotation): Boolean =
        blockAim.serverRotation(me).directionAngleTo(rotation) <= AIM_TOLERANCE

    /** Nothing to aim at in this phase: let the aim walk back to the camera. */
    private fun idle() {
        aimingCell = null
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
            debug(me, "piano: il blocco davanti ai piedi c'è già")
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
            place(me, legacy)
            phase = Phase.WALK
            stepBlock = legacy.cell
            waitTicks = 0
            readyTicks = 0
            return
        }

        val placement = findPlacement(me, cell) ?: run {
            debug(me, "piano: nessuna faccia raggiungibile per il blocco " + posText(cell) + " (" + reason() + ")")
            idle()
            readyTicks = 0
            return
        }
        if (aimingCell != cell) {
            aimingCell = cell
            readyTicks = 0
        }
        blockAim.aimAt(placement.rotation, placement.point)
        debug(me, "piano: mirando al blocco " + posText(cell) + " (" + placement.text() + ")")
        // The click waits until the server is already looking there.
        if (!aimed(me, placement.rotation)) {
            return
        }
        if (readyTicks == 0) {
            delay = drawShiftDelay()
        }
        if (readyTicks++ < delay) {
            return
        }
        place(me, placement)
        phase = Phase.WALK
        stepBlock = cell
        waitTicks = 0
        readyTicks = 0
        aimingCell = null
    }

    /** 2: back onto the new block, then the jump once past its middle by JumpAt (MovementInputEvent). */
    private fun walkTick(me: LocalPlayer) {
        val block = stepBlock ?: return reset()
        // Walking over the block that was just placed: nothing to aim at.
        idle()
        if (++waitTicks > 40) {
            debug(me, "salita: non sei arrivato sul blocco nuovo in 2 secondi, ricomincio")
            reset() // never got onto it
            return
        }
        // how far past the middle of the new block, along the walk
        val past = (me.x - (block.x + 0.5)) * walkX + (me.z - (block.z + 0.5)) * walkZ
        if (underFeet(me) == block && past >= jumpAt) {
            debug(me, "salita: sul blocco nuovo, salto")
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
            place(me, legacy)
            placedInAir = true
            airTickSum += airTicks
            return
        }

        // The aim starts while the jump is still rising — it only has a few ticks — but the block
        // itself is placed when the arc stops rising (PEAK) or at once (EARLY).
        val placement = findPlacement(me, cell) ?: run {
            debug(me, "aria tick $airTicks: nessuna faccia raggiungibile sopra " + posText(from) + " (" + reason() + ")")
            return
        }
        if (aimingCell != cell) {
            aimingCell = cell
            readyTicks = 0
        }
        blockAim.aimAt(placement.rotation, placement.point)
        if (placeTiming == Timing.PEAK && me.deltaMovement.y > 0.0) {
            return
        }
        if (!aimed(me, placement.rotation)) {
            return
        }
        if (readyTicks++ < delay) {
            return
        }
        place(me, placement)
        debug(me, "aria tick $airTicks: blocco sopra " + posText(from) + " (" + placement.text() + ")")
        placedInAir = true
        airTickSum += airTicks
        aimingCell = null
    }

    private fun report(me: LocalPlayer) {
        if (!chatLog) {
            return
        }
        if (!placedInAir) {
            me.sendSystemMessage(Component.literal("§7[EagleUpTest] gradino mancato: nessuna faccia del blocco "
                    + "raggiungibile sopra il gradino (controlla Aim > MaxSpeed / Range)"))
        } else if (steps % 5 == 0) {
            me.sendSystemMessage(Component.literal("§7[EagleUpTest] gradini $steps (mancati $misses), tick salto medio "
                    + "%.2f, %s, shift %d-%d, jump %d-%d".format(
                airTickSum.toDouble() / steps, placeTiming.tag,
                shiftDelay.first, shiftDelay.last, jumpDelay.first, jumpDelay.last
            )))
        }
    }

    /**
     * One click: the cell the block lands in, the hit that will be sent to the server and the
     * rotation that points at it.
     */
    private class Placement(
        val cell: BlockPos,
        val hit: BlockHitResult,
        val rotation: Rotation,
        /** The point of the face the aim is pointed at (also the distance fed to the AI engine). */
        val point: Vec3,
    ) {
        /** Debug: which face of which block. */
        fun text(): String = hit.direction.name.lowercase() + " di " + posText(hit.blockPos)
    }

    /**
     * The click that puts a block in [cell] — **simple**.
     *
     * A cell is filled by clicking the face of a neighbouring block that points at it. Of those
     * neighbours the module takes the one closest to the player — in a bridge that is the block
     * under the feet, right at the edge — and the point aimed at is the **centre of that face**, so
     * the crosshair sits in the middle of the block instead of on an edge or a corner.
     *
     * The same cell always gives the same click: no scoring between faces, nothing sticky, nothing
     * that can flip from one tick to the next.
     *
     * A neighbour only counts if it is a real support — solid, not water or lava (no collision
     * box), not something vanilla would replace — which is what GrimAC's `AirLiquidPlace` tests on
     * the clicked position.
     */
    private fun findPlacement(me: LocalPlayer, cell: BlockPos): Placement? {
        val level = mc.level ?: return null

        if (!level.getBlockState(cell).canBeReplaced()) {
            lastReject = "la cella è già occupata"
            return null
        }
        if (me.boundingBox.intersects(AABB(cell))) {
            lastReject = "la cella è dentro il giocatore"
            return null
        }

        val eye = me.eyePosition
        // Reach is measured from the eye; a hair of slack so a good click is not refused for nothing.
        val reach = me.blockInteractionRange() - 0.1

        var best: Placement? = null
        var bestDistance = Double.MAX_VALUE

        for (direction in Direction.entries) {
            val support = cell.relative(direction)
            val state = support.stateOrEmpty
            if (state.isAir || state.canBeReplaced() || support.collisionShape.isEmpty) {
                continue
            }

            val face = direction.opposite
            // The centre of the face the cell shares with this block.
            val point = Vec3.atCenterOf(support).relative(face, 0.5)
            val distance = eye.distanceTo(point)
            if (distance > reach) {
                continue
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = Placement(
                    cell = cell,
                    hit = BlockHitResult(point, face, support, false),
                    rotation = Rotation.lookingAt(point, eye),
                    point = point,
                )
            }
        }

        lastReject = if (best == null) "nessun appoggio a portata per la cella" else ""
        return best
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
            me.sendSystemMessage(Component.literal("§8[EagleUpTest D] §7$text"))
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
    private fun place(me: LocalPlayer, click: Placement) {
        val cell = click.cell
        // Watched from before the call: if the block only appears a tick later, the box is drawn by
        // [flushPendingRender] once it is really there.
        pending[cell] = 0
        runCatching {
            doPlacement(click.hit, click.rotation, hand = handWithBlock(me), onPlacementSuccess = {
                pending.remove(cell)
                renderer.addBlock(cell)
                true
            })
        }
    }

    /** The hand that holds a block to place: main hand first, then the off hand. */
    private fun handWithBlock(me: LocalPlayer): InteractionHand = when {
        me.mainHandItem.item is BlockItem && me.mainHandItem.count > 0 -> InteractionHand.MAIN_HAND
        me.offhandItem.item is BlockItem && me.offhandItem.count > 0 -> InteractionHand.OFF_HAND
        else -> InteractionHand.MAIN_HAND
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