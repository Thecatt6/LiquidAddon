package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockOffsetOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTarget
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTargetFindingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.CenterTargetPositionFactory
import net.ccbluex.liquidbounce.utils.block.targetfinding.ClickTrace
import net.ccbluex.liquidbounce.utils.block.targetfinding.FaceHandlingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.PlayerLocationOnPlacement
import net.ccbluex.liquidbounce.utils.block.targetfinding.findBestBlockPlacementTarget
import net.ccbluex.liquidbounce.utils.client.RestrictedSingleUseAction
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.render.placement.PlacementRenderer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.phys.AABB
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * ModuleScaffoldLegit — the Eagle up staircase, and a box on every block it places.
 *
 * **The click no longer needs the crosshair.** The module works out where the block has to go — the
 * cell over the void in front of the player, or the one on top of the block it jumped from — asks
 * LiquidBounce's target finding which face puts it there, and looks at that face *silently*: the
 * same rotation channel the Aimassist's `Silent` uses, `RotationManager`, configured in the
 * `Rotations` group (`AngleSmooth`, `MovementCorrection`, `ResetThreshold`, `TicksUntilReset`). Your
 * view never moves; the server is the one that sees the look.
 *
 * **The look stays down for as long as the bridge is running**, and not only for the tick of the
 * click: the rotation is asked for again every tick, on the face of the block being worked on, so what
 * the server sees is one continuous look at the block that drifts as the bridge advances — the look of
 * someone whose eyes never leave the block they are placing. A look that dropped for the click and came
 * straight back up afterwards is a look no hand makes, and it is the first thing that gives a scaffold
 * away. It comes back to the camera, slowly, only when the bridge stops: no space held, no void in
 * front, no blocks left.
 *
 * The click leaves once that look has really arrived on the face. That is the post-rotation pattern
 * every placement check is built around: a click sent from a look that is still on its way is the one
 * Grim reports as a placement the player could not have made. So the order is always look first, click
 * after — the rotation goes out in the movement packet of one tick, and the click goes out on a later
 * one, when the server already has that look.
 *
 * Because the rotation is recomputed every tick and verified before the click, a step that is
 * interrupted (a block that appears under the cell, a face that goes out of reach, a look that drifted
 * while the player was flying up) is simply retried on the next tick instead of being sent blind.
 *
 * `Eagle` holds the player on the edge, `Mode = STAIRS` places the block at the edge, the block on
 * top of it in the air, and jumps; `Mode = STEP` jumps on the physical space key. `Edge Distance`,
 * `JumpAt`, `Shift Delay`, `Jump Delay`, `PlaceTiming`, `ChatLog` and `Debug` do what they did
 * before — and the space key is only taken away while the module is really using it: a step that is
 * running, or the click it is about to make at this edge. Anywhere else you jump as if it were off.
 *
 * **The render** is unchanged: [PlacementRenderer] — the renderer LiquidBounce's own Scaffold uses,
 * with its `Render` settings group — draws a box on *every* block this module places, the flat one of
 * the bridge included. The cell is remembered when the click goes out and the box is added as soon as
 * the block is really in the world, so a click the server refuses draws nothing.
 */
object ModuleScaffoldLegit : ClientModule("ScaffoldLegit", Categories.LIQUIDADDON) {

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

    /**
     * How far the look the server has may sit from the one a click asked for, in degrees (yaw + pitch).
     *
     * Wide enough for the tick of travel between the two: the player keeps moving, so the look asked
     * for this tick is not exactly the one the server already has. The exact check is [clickWouldLand],
     * which is the one that decides whether the click can be explained.
     */
    private const val AIM_TOLERANCE = 6f

    /** Ticks the click of a step may keep waiting for its look before the step is given up on. */
    private const val AIR_BUDGET = 12

    private val mode by enumChoice("Mode", Mode.STAIRS)
    private val eagle by boolean("Eagle", true)

    // Range Slider per la distanza dal bordo (come nello screenshot: es. 0.38 - 0.6)
    private val edgeDistance by floatRange("Edge Distance", 0.38f..0.6f, 0.05f..1.0f)

    private val jumpAt by float("JumpAt", 0.1f, -0.3f..0.45f)
    private val placeTiming by enumChoice("PlaceTiming", Timing.PEAK)

    // Range Sliders con la doppia pallina per i Delay
    private val shiftDelay by intRange("Shift Delay", 0..2, 0..20, "ticks")
    private val jumpDelay by intRange("Jump Delay", 0..1, 0..20, "ticks")

    /** How far a click may reach: the face has to be inside it, both to look at it and to place. */
    private val range by float("Range", 4.5f, 2f..6f, "blocks")

    /** The swing of the click: `DoNotHide` is the one a hand makes, the others hide it from someone. */
    private val swingMode by enumChoice("Swing", SwingMode.DO_NOT_HIDE)

    /**
     * The rotation engine, the same one the Aimassist and every placement in LiquidBounce use: how
     * fast the look travels (`AngleSmooth`), how it walks back to the camera when the click is done,
     * and whether the walk is corrected to match the look the server is being shown.
     *
     * `MovementCorrection` starts on `Silent`: while the look is away from the camera, the keys and
     * the walk are derived from the look instead of from the camera, so what the server sees still
     * holds together — a player walking where it is looking. `Off` leaves the walk exactly as the
     * hand makes it, which is more faithful to the keys, but then the server sees a player looking
     * one way and walking another, and that mismatch is the thing an anti-cheat looks for. It costs
     * nothing here: the step in the air has no keys to correct, and at the edge the look is already
     * pointed at the cell, so it is the direction the walk is going anyway.
     */
    private val rotations = tree(RotationsValueGroup(this, MovementCorrection.SILENT))

    private val chatLog by boolean("ChatLog", true)
    private val debug by boolean("Debug", false)

    private var phase = Phase.FLAT

    /**
     * The placement animation (LiquidBounce's own renderer, with its `Render` settings group): a box
     * appears on the block that was just placed and fades out. It is the same class the normal
     * Scaffold uses, added the same way (`keep = false`, so the boxes of a disabled module finish
     * their fade-out instead of vanishing).
     */
    private val renderer = tree(PlacementRenderer("Render", true, this, keep = false))

    /**
     * The cells this module has clicked, with the ticks they have been waiting. The block of a click
     * only exists a tick later, so the box is added when the block is really there — that is what
     * makes the box appear on *every* block, the flat ones of the bridge included, and never on a
     * click that failed.
     */
    private val pending = LinkedHashMap<BlockPos, Int>()

    /** The block placed at the edge (WALK) or jumped from (AIR). */
    private var stepBlock: BlockPos? = null

    /**
     * What the module wants the look on: the face, whether the click that goes with it may leave as
     * soon as the look has arrived, and what to do once it has.
     */
    private class Look(val target: BlockPlacementTarget, val click: Boolean, val onPlaced: () -> Unit)

    /** What this tick wants the look on, or nothing when the look only has to stay where it is. */
    private var look: Look? = null

    /**
     * The face the look is on: held from tick to tick, so that a click does not leave the look hanging
     * in the middle of a bridge.
     */
    private var heldLook: BlockPlacementTarget? = null

    /**
     * The distance from the edge drawn for this step. Edge Distance is a range, and drawing it once
     * per step instead of once per check is what keeps the module from deciding it is at the edge on
     * one tick and not on the next while the player stands still.
     */
    private var edgeNow = 0.5f

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
        super.onEnabled()
    }

    override fun onDisabled() {
        // The boxes that are still on screen run their fade-out instead of vanishing.
        pending.clear()
        reset()
        heldLook = null // the look is theirs again
        renderer.clearSilently()
        super.onDisabled()
    }

    private fun reset() {
        phase = Phase.FLAT
        stepBlock = null
        readyTicks = 0
        jumpNow = false
        edgeNow = getEdgeDistance()
    }

    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> { event ->
        val me = mc.player ?: return@handler
        if (phase == Phase.AIR) {
            // the step is a jump straight up: no shift, no walking until the landing
            event.directionalInput = DirectionalInput(forwards = false, backwards = false, left = false, right = false)
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
            if (!spaceHeld) {
                reset() // space released: only Eagle
            }
            // The space key belongs to the module only while it is doing something with it: a step
            // that is running, or the click it is about to make at this edge. Anywhere else — flat
            // ground, no blocks in hand, no void in front — the player has to be able to jump exactly
            // as if the module were off, which is what swallowing the key outright used to prevent.
            if (phase != Phase.FLAT || (spaceHeld && me.onGround() && canPlace(me) && atEdge(me, event.directionalInput))) {
                event.jump = false
            }
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
        look = null
        if (mc.gui.screen() != null) {
            debug(me, "schermata aperta")
            heldLook = null
            return@handler
        }
        if (me.isUsingItem) {
            // The player is eating, or holding a bow: that use is theirs, and a click of ours would
            // land on whatever they are using instead of on the block.
            debug(me, "stai usando un oggetto")
            return@handler
        }
        when (phase) {
            Phase.AIR -> airTick(me)
            Phase.FLAT -> if (mode == Mode.STAIRS && spaceHeld && me.onGround()) {
                flatTick(me)
            } else if (mode == Mode.STAIRS) {
                debug(me, "spazio non premuto: solo Eagle")
            }
            Phase.WALK -> if (me.onGround()) walkTick(me)
        }
        holdLook(me)
    }

    /** 1: at the edge, the click that puts a block behind at the level of the one under the feet. */
    private fun flatTick(me: LocalPlayer) {
        val cell = edgeCell(me, lastKeys)
        if (cell == null) {
            debug(me, "piano: non sei sul bordo (tieni S verso il vuoto)")
            readyTicks = 0
            return
        }
        if (!canPlace(me)) {
            debug(me, "piano: nessun blocco in mano")
            readyTicks = 0
            return
        }
        val target = placement(me, cell)
        if (target == null) {
            debug(me, "piano: sul bordo, ma non c'è una faccia dove mettere il blocco")
            readyTicks = 0
            return
        }
        if (readyTicks == 0) {
            delay = drawShiftDelay()
        }
        val ready = readyTicks++ >= delay
        debug(me, "piano: blocco in " + short(cell) + " sulla faccia " + target.direction.name.lowercase() +
            " di " + short(target.interactedBlockPos))
        look = Look(target, ready) {
            phase = Phase.WALK
            stepBlock = cell
            waitTicks = 0
            readyTicks = 0
        }
    }

    /** 2: back onto the new block, then the jump once past its middle by JumpAt (MovementInputEvent). */
    private fun walkTick(me: LocalPlayer) {
        val block = stepBlock ?: return reset()
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

    /** 3: in the air, the click that puts a block on top of the one jumped from. */
    private fun airTick(me: LocalPlayer) {
        val from = stepBlock ?: return reset()
        if (me.onGround()) {
            if (airTicks > 0) {
                if (placedInAir) {
                    steps++
                } else {
                    misses++
                    debug(me, "aria: nessun blocco piazzato in $airTicks tick")
                }
                report(me)
                reset()
            }
            return
        }
        airTicks++
        if (placedInAir || airTicks > AIR_BUDGET) {
            return
        }
        // Peak: the block waits until the jump stops rising, where the look also stops drifting
        if (placeTiming == Timing.PEAK && me.deltaMovement.y > 0.0) {
            return
        }
        if (!canPlace(me)) {
            debug(me, "aria tick $airTicks: nessun blocco in mano")
            return
        }
        val target = placement(me, from.above())
        if (target == null) {
            debug(me, "aria tick $airTicks: non c'è una faccia sopra il blocco di partenza")
            return
        }
        val ready = readyTicks++ >= delay
        look = Look(target, ready) {
            placedInAir = true
            airTickSum += airTicks
            debug(me, "aria tick $airTicks: blocco sopra " + short(from))
        }
    }

    /**
     * Holds the look on the block the module is working on, and lets the click of this tick leave as
     * soon as that look has really arrived on the face.
     *
     * This runs every tick while the bridge is running, not only on the tick of a click: the rotation
     * asked for again and again is what makes the look the server sees one continuous look at the
     * block, instead of a glance that drops for the click and comes straight back up. When there is
     * nothing new to look at this tick — the walk back onto the block, the landing of a jump — the
     * look stays on the face it was already on.
     */
    private fun holdLook(me: LocalPlayer) {
        // Held only while the bridge is really running: a step in the air, the walk back onto the
        // block, or a click waiting at the edge. Everything else is the player's own look again.
        val running = when (phase) {
            Phase.AIR -> true
            Phase.WALK -> true
            Phase.FLAT -> mode == Mode.STAIRS && spaceHeld && me.onGround() && atEdge(me, lastKeys)
        }
        if (!running || !canPlace(me)) {
            heldLook = null
            return
        }

        val next = look
        if (next != null) {
            heldLook = next.target
        }
        val aim = heldLook ?: return
        RotationManager.setRotationTarget(
            rotation = aim.rotation,
            considerInventory = true,
            valueGroup = rotations,
            // Scaffold and friends: a KillAura that wants the rotation for itself outranks nothing
            // here, and nothing here outranks safety.
            priority = Priority.IMPORTANT_FOR_PLAYER_LIFE,
            provider = this,
            whenReached = RestrictedSingleUseAction(
                { next?.click == true && canPlace(me) && lookArrived(aim) && clickWouldLand(aim) },
                { place(me, aim, next?.onPlaced) }
            )
        )
    }

    /** The click itself: the vanilla right click on that face, with the look that asked for it. */
    private fun place(me: LocalPlayer, target: BlockPlacementTarget, onPlaced: (() -> Unit)?) {
        val hand = placeHand(me) ?: return
        val level = mc.level ?: return
        if (!level.getBlockState(target.placedBlock).canBeReplaced()) {
            return
        }
        pending[target.placedBlock] = 0
        runCatching {
            doPlacement(target.blockHitResult, target.rotation, hand, swingMode = swingMode)
        }
        onPlaced?.invoke()
    }

    /** True when the look the server has is the one this click asked for. */
    private fun lookArrived(target: BlockPlacementTarget): Boolean {
        val sent = RotationManager.serverRotation
        return abs(Mth.wrapDegrees(sent.yaw - target.rotation.yaw)) +
            abs(sent.pitch - target.rotation.pitch) <= AIM_TOLERANCE
    }

    /**
     * True when a ray leaving the eye with the look the server has still lands on the face this click
     * wants.
     *
     * The player keeps moving between the look and the click — the whole point of the step is a body
     * in the air — so a face that left the reach, or one the look drifted off while the player was
     * rising, is retried on the next tick instead of being clicked blind.
     */
    private fun clickWouldLand(target: BlockPlacementTarget): Boolean {
        val hit = target.verifyClick(
            rotation = RotationManager.serverRotation,
            range = range.toDouble(),
            trace = ClickTrace.TARGET_SHAPE,
        ) ?: return false
        return target.doesCrosshairTargetMatchRequirements(hit)
    }

    /**
     * What a click that fills [cell] has to know, or null when there is nothing to click: the cell has
     * to be free and out of the player's own box, and one of its neighbours has to have a face that
     * puts a block there — the target finding of LiquidBounce decides which one, the same way its own
     * Scaffold does.
     */
    private fun placement(me: LocalPlayer, cell: BlockPos): BlockPlacementTarget? {
        val level = mc.level ?: return null
        if (!level.getBlockState(cell).canBeReplaced()) {
            return null
        }
        if (me.boundingBox.intersects(AABB(cell))) {
            return null
        }
        val hand = placeHand(me) ?: return null
        val target = findBestBlockPlacementTarget(
            cell,
            BlockPlacementTargetFindingOptions(
                BlockOffsetOptions.Default,
                FaceHandlingOptions(CenterTargetPositionFactory),
                stackToPlaceWith = me.getItemInHand(hand),
                PlayerLocationOnPlacement(),
            )
        ) ?: return null
        // A face further than the click may reach is a face the server would refuse, however good the
        // look at it is.
        return target.takeIf { me.eyePosition.distanceTo(target.interactionPoint) <= range }
    }

    /** The hand with a block in it, or null when the player is holding something else. */
    private fun placeHand(me: LocalPlayer): InteractionHand? =
        InteractionHand.entries.firstOrNull { me.getItemInHand(it).item is BlockItem }

    /** Whether there is anything to place with at all. */
    private fun canPlace(me: LocalPlayer) = placeHand(me) != null

    private fun report(me: LocalPlayer) {
        if (!chatLog) {
            return
        }
        if (!placedInAir) {
            me.sendSystemMessage(Component.literal("§7[ScaffoldLegit] gradino mancato: " +
                "servono dei blocchi in mano e un bordo davanti a te"))
        } else if (steps % 5 == 0) {
            me.sendSystemMessage(Component.literal("§7[ScaffoldLegit] gradini $steps (mancati $misses), tick salto medio " +
                "%.2f, %s, shift %d-%d, jump %d-%d".format(
                    airTickSum.toDouble() / steps, placeTiming.tag,
                    shiftDelay.first, shiftDelay.last, jumpDelay.first, jumpDelay.last
                )))
        }
    }

    /** Debug: one chat line whenever what the module waits for changes. */
    private fun debug(me: LocalPlayer, text: String) {
        if (debug && text != lastDebug) {
            lastDebug = text
            me.sendSystemMessage(Component.literal("§8[ScaffoldLegit D] §7$text"))
        }
    }

    /** The walking direction of [keys] in the world (Entity.moveRelative), or nothing if no key. */
    private fun walkDirection(me: LocalPlayer, keys: DirectionalInput) {
        val vector = walkVector(me, keys) ?: return
        walkX = vector.first
        walkZ = vector.second
    }

    /** Where [keys] push, as a unit vector on the ground, or null when the player is not walking. */
    private fun walkVector(me: LocalPlayer, keys: DirectionalInput): Pair<Double, Double>? {
        val forward = (if (keys.forwards) 1.0 else 0.0) - (if (keys.backwards) 1.0 else 0.0)
        val strafe = (if (keys.left) 1.0 else 0.0) - (if (keys.right) 1.0 else 0.0)
        val length = hypot(forward, strafe)
        if (length < 1e-6) {
            return null
        }
        val yaw = Math.toRadians(me.yRot.toDouble())
        return ((strafe * cos(yaw) - forward * sin(yaw)) / length) to
            ((forward * cos(yaw) + strafe * sin(yaw)) / length)
    }

    /** Eagle: the block under the point [edgeNow] ahead, along where the keys push, is air. */
    private fun atEdge(me: LocalPlayer, keys: DirectionalInput) = edgeCell(me, keys) != null

    /**
     * The cell over the void in front of the player, at the level of the block under the feet: the one
     * the bridge has to fill. Null when the player is not walking, or that cell is not free.
     */
    private fun edgeCell(me: LocalPlayer, keys: DirectionalInput): BlockPos? {
        val vector = walkVector(me, keys) ?: return null
        val level = mc.level ?: return null
        val cell = BlockPos.containing(
            me.x + vector.first * edgeNow,
            me.y - 0.5,
            me.z + vector.second * edgeNow
        )
        return if (level.getBlockState(cell).isAir) cell else null
    }

    /** The block the player is standing on: the one under the feet, not the one they are inside. */
    private fun underFeet(me: LocalPlayer): BlockPos = BlockPos.containing(me.x, me.y - 0.5, me.z)

    /**
     * Adds the box for the cells that were clicked a moment ago and now hold a block. A cell whose
     * block never shows up is forgotten after half a second, so a refused click leaves nothing
     * behind.
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

    /** "12 -34 5", for the debug lines. */
    private fun short(pos: BlockPos) = pos.x.toString() + " " + pos.y + " " + pos.z
}
