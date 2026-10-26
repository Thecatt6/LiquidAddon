package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.aiming.BlockAim
import com.liquidaddon.aiming.BlockAimValues
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.block.stateOrEmpty
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockOffsetOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTarget
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTargetFindingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPosOffsets
import net.ccbluex.liquidbounce.utils.block.targetfinding.CenterTargetPositionFactory
import net.ccbluex.liquidbounce.utils.block.targetfinding.FaceHandlingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.PlayerLocationOnPlacement
import net.ccbluex.liquidbounce.utils.block.targetfinding.findBestBlockPlacementTarget
import net.ccbluex.liquidbounce.utils.block.targetfinding.verifyClick
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.render.placement.PlacementRenderer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.phys.BlockHitResult
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * ScaffoldLegit — the bridge and the stair climb, with a real aim.
 *
 * **What it does, every tick**
 *  1. it looks at the cell the bridge needs next — the block in front of the player at the level of
 *     the one under their feet;
 *  2. it finds the best way to put a block there with LiquidBounce's own target finding
 *     ([findBestBlockPlacementTarget]): the neighbour block + face whose click really places the
 *     stack in that cell, verified against vanilla's own placement context, preferring the face that
 *     points most directly at the eye;
 *  3. it aims at that face with the Aimassist's maths ([BlockAim]: a share per tick, `MaxSpeed`,
 *     `Smoothing`, `Jitter`, or LiquidBounce's already trained combat model with `Engine = AI`) and
 *     publishes the rotation tick by tick — silently, so the camera stays free;
 *  4. it clicks only when the ray from the rotation the **server already has**
 *     (`RotationManager.serverRotation`, written from the packets that were sent) hits that face.
 *     That is the "post rotation" order anticheats look for: never a click justified by a rotation
 *     the server has not received yet;
 *  5. every block it really places goes to [PlacementRenderer], which draws the box.
 *
 * **The climb.** `Mode = Stairs`: first the walkway block in front (same level as the one under the
 * feet), then the block **on top of it** — that second one is the step — then the module jumps, so
 * the player lands one block higher and the cycle repeats. `Mode = Bridge`: only the walkway, no
 * jumping, for a flat bridge (`Eagle` keeps the player on the edge meanwhile).
 *
 * Nothing here is guessed: the cell comes from the player's own position and facing, the click is
 * verified by vanilla's placement rules, and the rotation that justifies it is traced back from the
 * server state.
 */
object ModuleScaffoldLegit : ClientModule("ScaffoldLegit", Categories.LIQUIDADDON) {

    enum class Mode(override val tag: String) : Tagged {
        BRIDGE("Bridge"),
        STAIRS("Stairs")
    }

    private val mode by enumChoice("Mode", Mode.STAIRS)
    private val eagle by boolean("Eagle", true)

    /** How far ahead the block below the feet is looked at to decide whether the edge was reached. */
    private val edgeDistance by floatRange("Edge Distance", 0.38f..0.6f, 0.05f..1.0f)

    /** Ticks to wait before placing, drawn from this range (0-1 by default, like the old sliders). */
    private val placeDelay by intRange("Place Delay", 0..1, 0..20, "ticks")

    private val chatLog by boolean("ChatLog", true)
    private val debug by boolean("Debug", false)

    /**
     * The placement animation: the box that appears on the block the module just placed and fades
     * out again — the same effect (and the same settings) LiquidBounce's own Scaffold draws, through
     * the same class. Every block that is really placed goes in through `addBlock` from the
     * placement callback, so a refused click never draws anything.
     */
    private val renderer = tree(PlacementRenderer("Render", true, this, keep = false))

    /**
     * The aiming settings, in a group of their own so the module menu stays readable: only the
     * `Aim` row is shown until its arrow is clicked, then the whole list opens (`Enabled`,
     * `Silent`, `MoveFix`, `Strength`, `MaxSpeed`, `Smoothing`, `GcdFix`, `GcdDither`, `Jitter`,
     * `Engine` + `AI*`).
     */
    private val aim = tree(BlockAimValues())

    /** The aim itself: the Aimassist's step maths, pointed at a block face instead of an entity. */
    private val blockAim = BlockAim(this, aim)

    /**
     * Cells clicked but whose block is not in the world yet, with the ticks they have been waiting.
     * When the block shows up a tick later (server confirmation) its box is still drawn.
     */
    private val pending = LinkedHashMap<BlockPos, Int>()

    /** The cell being aimed at, and the click that puts a block in it (kept for the whole cell). */
    private var aimCell: BlockPos? = null
    private var target: BlockPlacementTarget? = null

    /** Consecutive ticks in which the planned click could not be traced from the server rotation. */
    private var traceMisses = 0

    /** Ticks to wait before the click, drawn from [placeDelay] once the aim is there. */
    private var delayTicks = 0
    private var delayTarget = -1

    /** The walking direction of the last keys, used as the bridge direction. */
    private var walkX = 0.0
    private var walkZ = 0.0

    private var jumpNow = false
    private var jumpCooldown = 0
    private var groundTicks = 0
    private var wasOnGround = true
    private var lastDebug = ""
    private var blockedTicks = 0
    private var lastDebugAt = 0L
    private var warnedEmptyHandAt = 0L

    private var steps = 0
    private var misses = 0

    private var lastJumpLevel = Int.MIN_VALUE

    /**
     * The last walkway block placed (the one at the level of the block under the feet). Its step —
     * the block on top of it — is placed on the next tick, as soon as the player is not standing on
     * the walkway itself (a block cannot be placed inside the player).
     */
    private var walkway: BlockPos? = null

    override val tag: String
        get() = if (steps == 0) "" else steps.toString()

    override fun onEnabled() {
        reset()
        steps = 0
        misses = 0
        pending.clear()
        blockAim.reset()
        super.onEnabled()

        val me = mc.player
        if (chatLog && me != null) {
            // Say what the module is about to do: "nothing happens" is otherwise impossible to tell
            // apart from "the module did not start".
            me.sendSystemMessage(
                Component.literal(
                    "§7[ScaffoldLegit] §aattivo§7 - ${mode.tag}, mira " +
                        (if (aim.enabled) (if (aim.silent) "silenziosa" else "visibile") else "spenta") +
                        ", ${aim.engine.tag}" +
                        if (handWithBlock(me) == null) " §c- nessun blocco in mano!" else ""
                )
            )
        }
    }

    override fun onDisabled() {
        // Let the boxes that are still on screen run their fade-out instead of vanishing: this is
        // what the normal Scaffold does when it is turned off.
        pending.clear()
        reset()
        blockAim.reset()
        renderer.clearSilently()
        super.onDisabled()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        pending.clear()
        reset()
        renderer.clearSilently()
    }

    private fun reset() {
        aimCell = null
        target = null
        traceMisses = 0
        delayTicks = 0
        delayTarget = -1
        jumpNow = false
        jumpCooldown = 0
        groundTicks = 0
        wasOnGround = true
        walkway = null
        blockedTicks = 0
        blockAim.clear()
    }

    /**
     * The brain. It runs on `RotationUpdateEvent` — the event `RotationManager` fires just before it
     * computes the rotation of this tick — so the step published here is the `currentRotation` of
     * the very same tick and rides this tick's movement packet. That is the whole reason the aim
     * "starts": it is requested from the place the manager is listening for, every tick, as long as
     * there is a cell to fill — no key, no cursor and no phase gate can leave it idle.
     */
    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        val me = mc.player ?: return@handler
        brain(me)
        // Always, even when the brain found nothing to aim at: that is what walks the aim back to
        // the camera (and what keeps the request alive while it is chasing a face).
        blockAim.tick()
        watchRotation(me)
    }

    /**
     * The aim can only move if `RotationManager` applies it. When something else holds the rotation
     * (a combat module pausing rotations, an open inventory, a higher priority request) nothing
     * would happen and the module would look broken: say it once in a while instead.
     */
    private fun watchRotation(me: LocalPlayer) {
        if (!aim.enabled || !aim.silent || blockAim.applied) {
            blockedTicks = 0
            return
        }
        if (++blockedTicks == 60) {
            me.sendSystemMessage(
                Component.literal(
                    "§7[ScaffoldLegit] §cla rotazione è bloccata da LiquidBounce " +
                        "(KillAura/combat in pausa o inventario aperto)"
                )
            )
        }
    }

    /** Decides what the bridge needs next and aims at it. */
    private fun brain(me: LocalPlayer) {
        val ground = me.onGround()

        if (ground) {
            groundTicks++
            if (!wasOnGround) {
                onLanded(me)
            }
        } else {
            groundTicks = 0
        }
        wasOnGround = ground
        if (jumpCooldown > 0) {
            jumpCooldown--
        }

        if (!ground) {
            // Nothing to place on the way up: the arc is short and the block below the feet is
            // already there. The aim is released so the camera comes back.
            idle()
            return
        }

        val direction = bridgeDirection(me)
        val feet = underFeet(me)

        // 1. The step of the walkway placed a tick ago. It goes on top of it, and only while the
        //    player is not standing on that walkway: a block cannot be placed inside the player.
        val walk = walkway
        if (mode == Mode.STAIRS && walk != null) {
            if (underFeet(me) == walk) {
                // The player already walked onto it; its step is behind us now, the next walkway
                // (step 2) is what continues the climb.
                walkway = null
            } else {
                val step = walk.above()
                if (isFree(step)) {
                    aimAndPlace(me, step)
                    return
                }
                walkway = null
            }
        }

        val ahead = feet.offset(direction.first, 0, direction.second)

        // 2. The walkway: the cell in front, at the level of the block under the feet.
        if (isFree(ahead)) {
            aimAndPlace(me, ahead)
            return
        }

        // 3. The step in front is filled: jump onto it, and the next cycle starts one level higher.
        //    Only on a walkway this module built — a block with nothing under it. On natural ground
        //    the block in front is terrain, not something to climb with placements.
        if (mode == Mode.STAIRS && isFree(ahead.below()) && !isFree(ahead.above())) {
            idle()
            if (jumpCooldown == 0 && groundTicks >= 2) {
                debug(me, "salita: salto sul gradino")
                jumpNow = true
                jumpCooldown = 6
                lastJumpLevel = me.blockY
            }
            return
        }

        debug(me, "niente da fare: davanti c'è già un blocco")
        idle()
    }

    /** Aims at the best click for [cell] and places it as soon as the server rotation is on it. */
    private fun aimAndPlace(me: LocalPlayer, cell: BlockPos) {
        val hand = handWithBlock(me) ?: run {
            warnEmptyHand(me)
            idle()
            return
        }

        if (aimCell != cell) {
            aimCell = cell
            target = null
            delayTicks = 0
            delayTarget = -1
            traceMisses = 0
            blockAim.clear()
        }

        val click = target ?: findTarget(me, cell, hand)?.also { target = it }
        if (click == null) {
            val why = if (lastTargetError.isEmpty()) "nessuna faccia raggiungibile" else lastTargetError
            debug(me, "nessuna faccia valida per " + posText(cell) + " ($why)")
            idle()
            return
        }

        if (aim.enabled) {
            blockAim.aimAt(click.rotation, click.interactionPoint)
        }

        // The click has to be justified by the rotation the server already received, so it is traced
        // from there. As long as the ray does not reach the planned face the module keeps aiming.
        val server = blockAim.serverRotation(me)
        val hit = click.verifyClick(rotation = server)
        if (hit == null) {
            if (++traceMisses % 6 == 0) {
                // The player moved and the planned click is stale: look for another face.
                target = null
            }
            debug(
                me,
                "mirando " + posText(cell) + ": resta %.1f°".format(blockAim.remaining(me))
            )
            return
        }

        traceMisses = 0
        if (delayTarget < 0) {
            delayTarget = drawDelay()
            delayTicks = 0
        }
        if (delayTicks++ < delayTarget) {
            return
        }
        delayTarget = -1
        place(me, click, hit, server)
    }

    /**
     * The best click that puts a block in [cell], by LiquidBounce's own target finding: it checks
     * with vanilla's placement context that the click really places the stack there, and prefers the
     * face that points most directly at the eye (the one the crosshair reaches first).
     *
     * Only the cell itself is investigated (`NO_OFFSET`): the module decides where the bridge goes,
     * the target finding only decides how to click it.
     */
    private fun findTarget(me: LocalPlayer, cell: BlockPos, hand: InteractionHand): BlockPlacementTarget? {
        return try {
            findTargetUnsafe(me, cell, hand)
        } catch (error: Throwable) {
            lastTargetError = error.javaClass.simpleName + ": " + error.message
            null
        }
    }

    /** Why the last target search blew up, for the debug line. */
    private var lastTargetError = ""

    private fun findTargetUnsafe(me: LocalPlayer, cell: BlockPos, hand: InteractionHand): BlockPlacementTarget? {
        val options = BlockPlacementTargetFindingOptions(
            BlockOffsetOptions(
                BlockPosOffsets.NO_OFFSET.offsets,
                BlockPlacementTargetFindingOptions.leastBlockDistanceToPos(me.position()),
            ),
            FaceHandlingOptions(CenterTargetPositionFactory),
            stackToPlaceWith = me.getItemInHand(hand),
            PlayerLocationOnPlacement(
                position = me.position(),
                pose = me.pose,
                rotation = blockAim.serverRotation(me),
            ),
        )
        return findBestBlockPlacementTarget(cell, options)
    }

    /**
     * Places the block of [click] with the very hit that was verified, reports the swing the way
     * vanilla does, and hands the cell to the [renderer].
     */
    private fun place(me: LocalPlayer, click: BlockPlacementTarget, hit: BlockHitResult, rotation: Rotation) {
        val cell = click.placedBlock
        val hand = handWithBlock(me) ?: return
        pending[cell] = 0
        delayTicks = 0
        delayTarget = -1
        runCatching {
            doPlacement(
                hit,
                rotation,
                hand,
                onPlacementSuccess = {
                    pending.remove(cell)
                    renderer.addBlock(cell)
                    placed(me, cell)
                    true
                },
                swingMode = SwingMode.DO_NOT_HIDE,
            )
        }.onFailure { error ->
            pending.remove(cell)
            debug(me, "click rifiutato: " + (error.javaClass.simpleName))
        }
    }

    /** Bookkeeping after a block really went down. */
    private fun placed(me: LocalPlayer, cell: BlockPos) {
        steps++
        aimCell = null
        target = null
        traceMisses = 0
        if (mode == Mode.STAIRS) {
            // A block at the level of the one under the feet is a walkway: the block that turns it
            // into a step goes on top of it on the next tick. Anything higher was that step.
            walkway = if (cell.y == underFeet(me).y) cell else null
            // The jump onto the step does not have to wait for a cooldown.
            jumpCooldown = 0
        }
        debug(me, "piazzato " + posText(cell) + " (totale $steps)")
        if (chatLog && steps % 8 == 0) {
            me.sendSystemMessage(
                Component.literal("§7[ScaffoldLegit] blocchi $steps (§cmancati $misses§7)")
            )
        }
    }

    /** Landing after a jump: count it as a step of the climb, or as a miss. */
    private fun onLanded(me: LocalPlayer) {
        if (lastJumpLevel == Int.MIN_VALUE) {
            return
        }
        if (me.blockY > lastJumpLevel) {
            misses = 0
        } else {
            misses++
        }
        lastJumpLevel = Int.MIN_VALUE
    }

    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> { event ->
        val me = mc.player ?: return@handler
        walkDirection(me, event.directionalInput)
        if (jumpNow) {
            if (me.onGround()) {
                event.jump = true
                jumpNow = false
            }
        }
        // Eagle: while the block below the point ahead is air the player is held on the edge, so the
        // walkway block can be aimed and placed without walking into the void.
        if (eagle && me.onGround() && atEdge(me, event.directionalInput)) {
            event.sneak = true
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        flushPendingRender()
    }

    /**
     * Draws the box for the placements that did not confirm on the spot: as soon as the block is
     * really in the world the box is added, and after half a second the cell is forgotten instead of
     * waiting forever.
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

    /** Stops aiming: the aim walks back to the camera over the next ticks. */
    private fun idle() {
        aimCell = null
        target = null
        traceMisses = 0
        delayTicks = 0
        delayTarget = -1
        blockAim.clear()
    }

    /**
     * The horizontal axis the bridge follows: the movement keys if any, otherwise where the camera
     * looks. Falling back to the camera is what makes the module work while the player stands still
     * — the usual case when the aim "never started".
     */
    private fun bridgeDirection(me: LocalPlayer): Pair<Int, Int> {
        val length = hypot(walkX, walkZ)
        val dx: Double
        val dz: Double
        if (length < 1.0e-6) {
            val yaw = Math.toRadians(me.yRot.toDouble())
            dx = -sin(yaw)
            dz = cos(yaw)
        } else {
            dx = walkX / length
            dz = walkZ / length
        }
        // The block has to share a face with its support, so the bridge follows one axis at a time.
        return if (abs(dx) >= abs(dz)) {
            (if (dx >= 0) 1 else -1) to 0
        } else {
            0 to (if (dz >= 0) 1 else -1)
        }
    }

    /** The walking direction of [keys] in the world (Entity.moveRelative); nothing leaves it as is. */
    private fun walkDirection(me: LocalPlayer, keys: DirectionalInput) {
        val forward = (if (keys.forwards) 1.0 else 0.0) - (if (keys.backwards) 1.0 else 0.0)
        val strafe = (if (keys.left) 1.0 else 0.0) - (if (keys.right) 1.0 else 0.0)
        val length = hypot(forward, strafe)
        if (length < 1e-6) {
            walkX = 0.0
            walkZ = 0.0
            return
        }
        val yaw = Math.toRadians(me.yRot.toDouble())
        walkX = (strafe * cos(yaw) - forward * sin(yaw)) / length
        walkZ = (forward * cos(yaw) + strafe * sin(yaw)) / length
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
        val distance = drawEdgeDistance()
        val below = BlockPos.containing(me.x + dx * distance, me.y - 0.5, me.z + dz * distance)
        return level.getBlockState(below).isAir
    }

    /** The hand that holds a placeable block, main hand first. */
    private fun handWithBlock(me: LocalPlayer): InteractionHand? = when {
        me.mainHandItem.item is BlockItem && me.mainHandItem.count > 0 -> InteractionHand.MAIN_HAND
        me.offhandItem.item is BlockItem && me.offhandItem.count > 0 -> InteractionHand.OFF_HAND
        else -> null
    }

    /** A cell a block can go into. */
    private fun isFree(pos: BlockPos): Boolean = pos.stateOrEmpty.canBeReplaced()

    /** The block the player is standing on: the one under the feet, not the one they are inside. */
    private fun underFeet(me: LocalPlayer): BlockPos = BlockPos.containing(me.x, me.y - 0.5, me.z)

    private fun drawDelay(): Int {
        val min = placeDelay.first
        val max = placeDelay.last
        return if (max > min) Random.nextInt(min, max + 1) else min
    }

    private fun drawEdgeDistance(): Float {
        val min = edgeDistance.start
        val max = edgeDistance.endInclusive
        return if (max > min) Random.nextDouble(min.toDouble(), max.toDouble()).toFloat() else min
    }

    /** Debug: a position, short. */
    private fun posText(pos: BlockPos): String = pos.x.toString() + " " + pos.y + " " + pos.z

    /** Debug: one chat line whenever what the module waits for changes (throttled). */
    private fun debug(me: LocalPlayer, text: String) {
        val now = System.currentTimeMillis()
        if (debug && (text != lastDebug || now - lastDebugAt > 1000)) {
            lastDebug = text
            lastDebugAt = now
            me.sendSystemMessage(Component.literal("§8[ScaffoldLegit] §7$text"))
        }
    }

    /** "Nothing is happening" is almost always an empty hand: say it instead of doing nothing. */
    private fun warnEmptyHand(me: LocalPlayer) {
        val now = System.currentTimeMillis()
        if (chatLog && now - warnedEmptyHandAt > 3000) {
            warnedEmptyHandAt = now
            me.sendSystemMessage(Component.literal("§7[ScaffoldLegit] §cnon hai blocchi in mano"))
        }
    }
}
