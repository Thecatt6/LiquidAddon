package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.aiming.AimassistCore
import com.liquidaddon.aiming.ClutchValues
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.block.collisionShape
import net.ccbluex.liquidbounce.utils.block.state
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockOffsetOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTarget
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTargetFindingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.CenterTargetPositionFactory
import net.ccbluex.liquidbounce.utils.block.targetfinding.FaceHandlingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.PlayerLocationOnPlacement
import net.ccbluex.liquidbounce.utils.block.targetfinding.findBestBlockPlacementTarget
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.raytracing.traceFromPlayer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.phys.Vec3

/**
 * Clutch — places a block under you while you are falling into the void, and lands on it.
 *
 * ## The aim is the aimbot's
 *
 * The rotation is not written here. This module hands [AimassistCore] the **point** to aim at — the
 * face of the block that is about to be clicked — and the engine does the rest with [ClutchValues]:
 * `Engine = AI` with LiquidBounce's trained model, the mouse-count lattice with its carried residual
 * (`GcdFix`, `GcdDither`), the hand on top of it (`MouseNoise`, `Overshoot`, `StrengthSpread`), the
 * per-tick pitch limit, the frame interpolation, and the silent rotation through `RotationManager`
 * with `MoveFix`. Same engine as `AimbotAI` and `FireballDeflect`, same bypasses, nothing duplicated.
 *
 * ## The order of the two actions
 *
 * Rotation first, click second. The block is only placed on a tick in which the rotation the
 * **server already has** traces onto exactly that block and that face
 * ([BlockPlacementTarget.doesCrosshairTargetMatchRequirements]): the use packet then arrives
 * justified by a rotation that got there before it, which is both what makes the placement land and
 * what keeps the packet looking like a player who looked down and right-clicked.
 *
 * ## What it places, and where
 *
 * Every tick, while [Trigger] says it should:
 *
 *  - the cell is predicted [lookAhead] ticks ahead of the fall, so a fast fall is met by a block
 *    slightly lower instead of one the player has already passed;
 *  - the cells are tried in order: under the feet first (the platform to land on), then one and two
 *    blocks lower when the fall is fast, then the four side cells at the same level (bridging back
 *    to a wall, which is how a clutch against a cliff works);
 *  - the block itself is chosen by LiquidBounce's own target finding
 *    ([findBestBlockPlacementTarget]), so the face is one the server accepts, the reach is the real
 *    one and the aim point is a point on a face rather than the middle of a block;
 *  - the stack comes from the hotbar or the off hand and is selected **silently**
 *    ([SilentHotbar]), so the client never sees the switch.
 */
object ModuleClutch : ClientModule("Clutch", Categories.LIQUIDADDON) {

    /** When the module works at all. */
    enum class Trigger(override val tag: String) : Tagged {
        /** Only when there is nothing to land on: the void below you. */
        FALLING("Falling"),

        /** Any time you are in the air and coming down, void or not. */
        AIRBORNE("Airborne")
    }

    private val trigger by enumChoice("Trigger", Trigger.FALLING)

    /** How far below the feet the module looks before deciding that there is nothing to land on. */
    private val scanDepth by int("ScanDepth", 4, 1..12, "blocks")

    /** Ticks of fall the placement is aimed at: 1 meets a fast fall one block lower. */
    private val lookAhead by int("LookAhead", 1, 0..4, "ticks")

    /** Ticks between two placements. A clutch is a few blocks, not a tower. */
    private val placeCooldown by int("PlaceCooldown", 1, 0..8, "ticks")

    /** How close the rotation the **server** has must be before the click goes out. */
    private val rotationTolerance by float("RotationTolerance", 4f, 0.5f..20f, "°")

    private val swingMode by enumChoice("SwingMode", SwingMode.DO_NOT_HIDE)
    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

    /** The engine, with this module's settings and the point this module picks. */
    private val values = ClutchValues(this)

    /** The point the engine aims at this tick, or `null` when there is nothing to do. */
    private var aimPoint: Vec3? = null

    private val core = AimassistCore(
        owner = this,
        values = values,
        forcedPoint = { aimPoint },
        // A fall is the most urgent thing a client can react to: above any aim.
        rotationPriority = Priority.IMPORTANT_FOR_USER_SAFETY,
    )

    /** The placement being aimed at. */
    private var plan: BlockPlacementTarget? = null

    private var lastPlaceTick = -100
    private var placed = 0
    private var lastNote = ""

    override val tag: String
        get() = if (placed == 0) "" else placed.toString()

    /** Friction is fixed to zero for this profile; the handler keeps the engine whole. */
    @Suppress("unused")
    private val mouseHandler = handler<MouseRotationEvent> { core.onMouseRotation(it) }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player ?: return@handler

        // The plan first: the engine can only aim at a point that already exists this tick.
        updatePlan(me)
        core.tick()
        tryPlace(me)
    }

    /** Frame interpolation: the tick's step drawn slice by slice, when the camera is ours. */
    @Suppress("unused")
    private val renderHandler = handler<GameRenderEvent> { core.onRender() }

    override fun onEnabled() {
        core.onEnable()
        clearPlan()
        lastPlaceTick = -100
        placed = 0
        lastNote = ""
        super.onEnabled()
        chat(
            "§7[Clutch] §aattivo §7- mira dell'aimbot (" + values.engine.tag +
                "), piazza quando la rotazione del server è sulla faccia"
        )
    }

    override fun onDisabled() {
        clearPlan()
        core.onDisable()
        super.onDisabled()
    }

    // --- when it works ----------------------------------------------------------------------

    /** Nothing to land on below the feet, and coming down. */
    private fun shouldClutch(me: LocalPlayer): Boolean {
        if (me.onGround()) return false
        if (me.deltaMovement.y >= -0.02) return false

        if (trigger == Trigger.AIRBORNE) return true

        for (depth in 1..scanDepth) {
            val pos = BlockPos.containing(me.x, me.y - depth, me.z)
            if (pos.state == null) continue
            if (!pos.collisionShape.isEmpty) {
                // There is something to land on after all.
                return false
            }
        }
        return true
    }

    // --- the plan ---------------------------------------------------------------------------

    /**
     * Keeps [plan] up to date: the placement target, the cell it fills and the point to aim at.
     *
     * A plan that is still valid is kept — re-picking it every tick would make the aim jump between
     * two faces and never arrive on either — and it is replaced as soon as the cell is no longer
     * empty or the click is no longer possible from where the player is now.
     */
    private fun updatePlan(me: LocalPlayer) {
        if (mc.gameMode == null || me.isSpectator || mc.gui.screen() != null) {
            clearPlan()
            return
        }

        if (!shouldClutch(me)) {
            clearPlan()
            note("niente da fare (a terra, o un appoggio sotto)")
            return
        }

        val slot = blockSlot()
        if (slot == null) {
            clearPlan()
            note("nessun blocco in hotbar")
            return
        }

        // A plan already chosen stays as long as it is still a placement we could make.
        val current = plan
        if (current != null && stillValid(me, current)) {
            aimPoint = current.interactionPoint
            return
        }

        val target = findPlacement(me) ?: run {
            clearPlan()
            note("nessuna faccia raggiungibile")
            return
        }

        plan = target
        aimPoint = target.interactionPoint
    }

    /** The plan is still a click the server would accept, from where the player is now. */
    private fun stillValid(me: LocalPlayer, target: BlockPlacementTarget): Boolean {
        val level = mc.level ?: return false
        val state = level.getBlockState(target.placedBlock)
        if (!state.canBeReplaced()) return false

        val distance = target.interactionPoint.distanceTo(me.eyePosition)
        if (distance > me.blockInteractionRange() + 0.3) return false

        return true
    }

    /**
     * The first cell, in the order a clutch wants them, that LiquidBounce's target finding can turn
     * into a real click: under the feet, deeper while falling fast, then the sides (bridging back to
     * a wall).
     */
    private fun findPlacement(me: LocalPlayer): BlockPlacementTarget? {
        val level = mc.level ?: return null
        val stack = blockSlot()?.itemStack ?: return null

        val predicted = me.position().add(me.deltaMovement.scale(lookAhead.toDouble()))
        val feet = BlockPos.containing(predicted.x, predicted.y, predicted.z)

        val candidates = LinkedHashSet<BlockPos>()
        candidates += feet.below()
        // A fast fall is met lower, or the block is one the player has already gone past.
        if (me.deltaMovement.y < -0.4) {
            candidates += feet.below(2)
            candidates += feet.below(3)
        }
        // Against a wall the useful cell is the one beside the player, at the level of the feet.
        for (direction in listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            candidates += feet.relative(direction)
        }
        if (me.deltaMovement.y < -0.4) {
            for (direction in listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
                candidates += feet.below().relative(direction)
            }
        }

        val options = BlockPlacementTargetFindingOptions(
            offsetOptions = BlockOffsetOptions.Default,
            faceHandlingOptions = FaceHandlingOptions(CenterTargetPositionFactory),
            stackToPlaceWith = stack,
            playerLocationOnPlacement = PlayerLocationOnPlacement(),
        )

        for (cell in candidates) {
            if (!level.getBlockState(cell).canBeReplaced()) continue
            // The cell the player is inside cannot be filled: the server refuses it, and it would
            // also be a block inside our own feet.
            if (me.boundingBox.intersects(cell.x.toDouble(), cell.y.toDouble(), cell.z.toDouble(),
                    cell.x + 1.0, cell.y + 1.0, cell.z + 1.0)) {
                continue
            }

            val target = findBestBlockPlacementTarget(cell, options) ?: continue
            if (target.interactionPoint.distanceTo(me.eyePosition) > me.blockInteractionRange() - 0.1) {
                continue
            }
            return target
        }

        return null
    }

    private fun clearPlan() {
        plan = null
        aimPoint = null
    }

    /** The first hotbar or off-hand slot holding blocks, or `null`. */
    private fun blockSlot() = Slots.OffhandWithHotbar.findSlot { stack ->
        stack.item is BlockItem && stack.count > 0
    }

    // --- the click --------------------------------------------------------------------------

    /**
     * The placement, gated by the rotation.
     *
     * The gate is the whole point of doing this with the aiming engine: the rotation the server has
     * must already be on that face — checked by degrees *and* by tracing the ray from the eyes with
     * that rotation — before the use packet leaves. A click that goes out first and a rotation that
     * follows it is the pattern every placement check calls "silent rotation".
     */
    private fun tryPlace(me: LocalPlayer) {
        val target = plan ?: return
        if (me.tickCount - lastPlaceTick < placeCooldown) return

        if (!stillValid(me, target)) {
            clearPlan()
            return
        }

        val server = RotationManager.serverRotation
        val offset = server.directionAngleTo(target.rotation)
        if (offset > rotationTolerance) {
            note("rotazione a %.1f° dalla faccia".format(offset))
            return
        }

        // Degrees are not enough: the ray has to land on that block, on that face.
        val trace = runCatching { traceFromPlayer(server, range = me.blockInteractionRange() + 0.5) }
            .getOrNull()
        if (trace == null || !target.doesCrosshairTargetMatchRequirements(trace)) {
            note("il mirino del server non è ancora sulla faccia")
            return
        }

        val slot = blockSlot() ?: run {
            note("nessun blocco in hotbar")
            clearPlan()
            return
        }
        // The switch has to be invisible: the client never sees the hotbar move.
        SilentHotbar.selectSlotSilently(this, slot, SLOT_RESET_TICKS)

        var done = false
        runCatching {
            doPlacement(
                hitResult = target.blockHitResult,
                rotation = server,
                hand = slot.useHand,
                onPlacementSuccess = {
                    done = true
                    true
                },
                swingMode = swingMode,
            )
        }.onFailure { note("piazzamento fallito (${it.javaClass.simpleName})") }

        lastPlaceTick = me.tickCount
        if (!done) {
            note("il server non ha accettato il piazzamento")
            return
        }

        placed++
        if (chatLog) {
            chat("§7[Clutch] §f$placed blocchi piazzati in caduta")
        }
        if (debug) {
            note("piazzato su " + target.placedBlock.toShortString())
        }
        clearPlan()
    }

    /** One chat line per *change* of state: the same reason repeated is noise, not debugging. */
    private fun note(message: String) {
        if (!debug || message == lastNote) return
        lastNote = message
        chat("§8[Clutch D] §7$message")
    }

    /** Ticks the silently selected slot is kept before LiquidBounce restores the real one. */
    private const val SLOT_RESET_TICKS = 4

}
