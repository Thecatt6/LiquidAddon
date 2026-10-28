package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.aiming.AimassistCore
import com.liquidaddon.aiming.ClutchValues
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.collisionShape
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.block.state
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockOffsetOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTarget
import net.ccbluex.liquidbounce.utils.block.targetfinding.BlockPlacementTargetFindingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.ClickableCenterTargetPositionFactory
import net.ccbluex.liquidbounce.utils.block.targetfinding.FaceHandlingOptions
import net.ccbluex.liquidbounce.utils.block.targetfinding.PlayerLocationOnPlacement
import net.ccbluex.liquidbounce.utils.block.targetfinding.findBestBlockPlacementTarget
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.raytracing.traceFromPlayer
import net.ccbluex.liquidbounce.utils.render.placement.PlacementRenderer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

/**
 * Clutch — puts a block under you while you are falling, and lands on it.
 *
 * ## The aim
 *
 * The rotation is not written here. The module names the **point** to look at (a point on the face
 * of the block it is about to click) and [AimassistCore] turns the aim there with [ClutchValues]:
 * the AI engine with LiquidBounce's trained model, the mouse-count lattice with its carried residual,
 * the hand on top of it, the frame interpolation and the silent rotation through `RotationManager`.
 * Same engine as `AimbotAI` and `FireballDeflect`, so the same bypasses — and, because a clutch is a
 * look *down*, the vertical gets the same per-tick budget as the horizontal, which is what the
 * assist's 3°/tick pitch cap never allowed (the aim needs ~70° of pitch and has about four ticks).
 *
 * The point is not the exact centre of the face: it is a spot inside the middle of the face, redrawn
 * every couple of hundred milliseconds, so the crosshair travels to *a place on the face* the way a
 * hand does instead of locking onto one coordinate. The click is gated on the ray, not on the
 * coordinate, so a spot slightly off centre still places the block — and looks like a player.
 *
 * ## The click
 *
 * Rotation first, click second: the placement only goes out on a tick in which the rotation the
 * **server already has** traces onto that block and that face
 * ([BlockPlacementTarget.doesCrosshairTargetMatchRequirements] over
 * [traceFromPlayer]). A use packet that arrives before the rotation that justifies it is exactly
 * what a placement check looks for.
 *
 * ## What it places
 *
 * While falling with nothing to land on within two blocks, the module looks for the cheapest
 * placement, in this order:
 *
 *  1. the cell directly under the feet — the platform to land on, if anything at its level can be
 *     clicked (a wall beside it, that is the classic clutch);
 *  2. the four cells beside that one — the ledge on the wall, which is what makes the cell under the
 *     feet clickable on the *next* tick (this is how a fall down a cliff is saved);
 *  3. one cell lower, for a fall that is already past the feet;
 *  4. the four cells beside the feet themselves, and finally the cell the feet are in.
 *
 * When none of them can be clicked this tick, the aim does not die: it goes to where the block is
 * wanted (the top of the cell under the feet) and waits there, so the module is already looking at
 * the right place the tick a face becomes available.
 *
 * Every block that goes down is drawn by LiquidBounce's own [PlacementRenderer].
 */
object ModuleClutch : ClientModule("Clutch", Categories.LIQUIDADDON) {

    /** Ticks of fall the placement is aimed at: 1 meets a fast fall one cell lower. */
    private val lookAhead by int("LookAhead", 1, 0..4, "ticks")

    /** Ticks between two placements. */
    private val placeCooldown by int("PlaceCooldown", 1, 0..8, "ticks")

    /** How close the rotation the **server** has must be to the aimed point before the click goes out. */
    private val rotationTolerance by float("RotationTolerance", 6f, 1f..30f, "°")

    private val swingMode by enumChoice("SwingMode", SwingMode.DO_NOT_HIDE)
    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

    /**
     * The render: LiquidBounce's own [PlacementRenderer], the same one `ScaffoldLegit` uses, so every
     * block this module puts into the world is drawn where it was placed.
     */
    private val renderer = tree(PlacementRenderer("Render", true, this, keep = false))

    private val values = ClutchValues(this)

    /** Where the engine looks this tick — a point on the face — or `null` when there is nothing to do. */
    private var aimPoint: Vec3? = null

    private val core = AimassistCore(
        owner = this,
        values = values,
        forcedPoint = { aimPoint },
        // A fall is the most urgent thing a client can react to.
        rotationPriority = Priority.IMPORTANT_FOR_USER_SAFETY,
    )

    /** The placement being aimed at. */
    private var plan: BlockPlacementTarget? = null

    /** The spot on the face the aim is drifting to, and when it is redrawn. */
    private var faceOffset = Vec3.ZERO
    private var faceOffsetUntil = 0L

    private var lastPlaceTick = -100
    private var placed = 0

    /** Ticks in a row the click was held because the ray did not land on the face. */
    private var missTicks = 0
    private var status = "idle"

    override val tag: String
        get() = if (placed == 0) "" else placed.toString()

    @Suppress("unused")
    private val mouseHandler = handler<MouseRotationEvent> { core.onMouseRotation(it) }

    @Suppress("unused")
    private val renderFrameHandler = handler<GameRenderEvent> { core.onRender() }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player
        if (me == null) return@handler

        updatePlan(me)
        core.tick()
        tryPlace(me)
        reportStatus(me)
    }

    override fun onEnabled() {
        core.onEnable()
        clearPlan()
        lastPlaceTick = -100
        placed = 0
        missTicks = 0
        status = "idle"
        super.onEnabled()
        chat("§7[Clutch] §aattivo §7- mira dell'aimbot (" + values.engine.tag + "), piazza quando la rotazione è sulla faccia")
    }

    override fun onDisabled() {
        clearPlan()
        renderer.clearSilently()
        core.onDisable()
        super.onDisabled()
    }

    // --- when it works ----------------------------------------------------------------------

    /**
     * Airborne over the void: nothing to land on within [SUPPORT_DEPTH] blocks.
     *
     * This is deliberately *not* "already falling": the aim has to be down there **before** the fall
     * starts, or the few ticks the pitch needs are ticks the fall does not give. A player who is
     * about to clutch looks down first, and so does this.
     *
     * The support distance is fixed at two blocks: closer than that you are already landing, and a
     * block there would only be a block thrown away.
     */
    private fun overVoid(me: LocalPlayer): Boolean {
        if (mc.gameMode == null || me.isSpectator) return false
        if (me.onGround()) return false
        if (me.isInWater || me.isInLava) return false
        // Still going up (a jump): not a fall yet, and the aim is not pushed down for nothing.
        if (me.deltaMovement.y >= 0.1) return false

        for (depth in 1..SUPPORT_DEPTH) {
            val pos = BlockPos.containing(me.x, me.y - depth, me.z)
            if (pos.state == null) continue
            if (!pos.collisionShape.isEmpty) return false
        }
        return true
    }

    /** Actually coming down: only now is a click allowed. */
    private fun clutching(me: LocalPlayer): Boolean =
        overVoid(me) && me.deltaMovement.y < -0.02

    // --- the plan ---------------------------------------------------------------------------

    /** Chooses where to look and what to click. Runs before the engine's tick, every tick. */
    private fun updatePlan(me: LocalPlayer) {
        if (mc.gui.screen() != null) {
            clearPlan()
            status = "schermo aperto"
            return
        }

        if (!overVoid(me)) {
            clearPlan()
            status = "non sto cadendo"
            return
        }

        val stack = blockStack() ?: run {
            clearPlan()
            status = "nessun blocco"
            return
        }

        // A plan still clickable stays: re-picking every tick would move the aim between two faces
        // and never arrive on either.
        val current = plan
        if (current != null && clickable(me, current)) {
            aimPoint = aimAt(current)
            status = "punto pronto: " + current.placedBlock.toShortString()
            return
        }

        val found = findPlan(me, stack)
        plan = found
        faceOffsetUntil = 0L
        missTicks = 0

        if (found != null) {
            aimPoint = aimAt(found)
            status = "nuovo punto: " + found.placedBlock.toShortString()
            return
        }

        // Nothing clickable yet: keep looking exactly where the block is wanted. The aim is alive
        // (the module is visibly looking down) and the tick a face appears the click can follow.
        aimPoint = wantedPoint(me)
        status = "nessuna faccia, guardo dove voglio il blocco"
    }

    /**
     * The point the engine is told to look at: the face of the block to click, moved by a fresh spot
     * inside the middle of that face. Redrawn every 180-400 ms so the aim is a place on a face, not
     * a coordinate that is returned to forever.
     */
    private fun aimAt(target: BlockPlacementTarget): Vec3 {
        val now = System.currentTimeMillis()
        // A spot that keeps failing to land the click is given up: the aim goes back to the middle of
        // the face, which is the one point a ray always reaches. A hand does the same.
        if (missTicks >= FACE_GIVE_UP_TICKS) {
            faceOffset = Vec3.ZERO
            faceOffsetUntil = Long.MAX_VALUE
        } else if (now > faceOffsetUntil) {
            faceOffsetUntil = now + 180 + Random.nextInt(220)
            // Where inside the face a hand would put the crosshair: the middle, not the centre.
            faceOffset = inFaceOffset(target.direction, FACE_SPREAD_MAX * values.humanize)
        }

        val box = AABB(target.interactedBlockPos)
        val raw = target.interactionPoint.add(faceOffset)

        fun clamp(v: Double, min: Double, max: Double) = v.coerceIn(min + FACE_MARGIN, max - FACE_MARGIN)

        // Only the two axes *inside* the face are clamped: the axis of the face normal has to stay
        // exactly on the plane, or the point would move into the block it is drawn on.
        var point = when (target.direction.axis) {
            Direction.Axis.X -> Vec3(raw.x, clamp(raw.y, box.minY, box.maxY), clamp(raw.z, box.minZ, box.maxZ))
            Direction.Axis.Y -> Vec3(clamp(raw.x, box.minX, box.maxX), raw.y, clamp(raw.z, box.minZ, box.maxZ))
            Direction.Axis.Z -> Vec3(clamp(raw.x, box.minX, box.maxX), clamp(raw.y, box.minY, box.maxY), raw.z)
        }

        // Below [minPlacementY] the server resolves the click to the box underneath the face.
        if (point.y < target.minPlacementY + FACE_MARGIN) {
            point = Vec3(point.x, target.minPlacementY + FACE_MARGIN, point.z)
        }
        return point
    }

    /** A random offset that lies **in** the plane of [direction]'s face. */
    private fun inFaceOffset(direction: Direction, size: Double): Vec3 {
        fun r() = (Random.nextDouble() * 2.0 - 1.0) * size
        var x = r()
        var y = r()
        var z = r()
        when (direction.axis) {
            Direction.Axis.X -> x = 0.0
            Direction.Axis.Y -> y = 0.0
            Direction.Axis.Z -> z = 0.0
        }
        return Vec3(x, y, z)
    }

    /** Where the block is wanted: the top of the cell under the feet. */
    private fun wantedPoint(me: LocalPlayer): Vec3 {
        val feet = feetCell(me)
        return Vec3(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5)
    }

    /** The cell the feet are in, one tick of fall ahead. */
    private fun feetCell(me: LocalPlayer): BlockPos {
        val predicted = me.position().add(me.deltaMovement.scale(lookAhead.toDouble()))
        return BlockPos.containing(predicted.x, predicted.y, predicted.z)
    }

    /** Still a click we could make from where the player is now. */
    private fun clickable(me: LocalPlayer, target: BlockPlacementTarget): Boolean {
        val level = mc.level ?: return false
        val state = level.getBlockState(target.placedBlock)
        if (!state.canBeReplaced()) return false
        val reach = me.blockInteractionRange() + 0.3
        return target.interactionPoint.distanceTo(me.eyePosition) <= reach
    }

    /**
     * The cheapest placement available this tick, searched around the feet in the order a clutch
     * wants them, every cell verified by LiquidBounce's own target finding.
     */
    private fun findPlan(me: LocalPlayer, stack: ItemStack): BlockPlacementTarget? {
        val feet = feetCell(me)
        val sides = SIDES

        val cells = ArrayList<BlockPos>(16)
        cells += feet.below()
        // Beside the cell under the feet: the ledge on a wall. It is what makes the cell under the
        // feet clickable on the next tick, so it comes before anything else that is not a direct hit.
        for (direction in sides) cells += feet.below().relative(direction)
        cells += feet.below(2)
        for (direction in sides) cells += feet.relative(direction)
        cells += feet

        val options = BlockPlacementTargetFindingOptions(
            offsetOptions = BlockOffsetOptions.Default,
            // The clickable variant: when the middle of the face is hidden behind the block itself,
            // it hands back a point on the face that can actually be seen — and a point that cannot
            // be reached by a ray is a click that never passes the gate.
            faceHandlingOptions = FaceHandlingOptions(ClickableCenterTargetPositionFactory),
            stackToPlaceWith = stack,
            playerLocationOnPlacement = PlayerLocationOnPlacement(),
        )

        val ownBox = me.boundingBox
        val reach = me.blockInteractionRange()

        for (cell in cells) {
            // A cell the player is standing in cannot be filled: the server refuses it and it would
            // be a block inside our own feet.
            if (ownBox.intersects(AABB(cell))) continue

            val target = runCatching { findBestBlockPlacementTarget(cell, options) }.getOrNull() ?: continue
            if (target.interactionPoint.distanceTo(me.eyePosition) > reach) continue
            return target
        }

        return null
    }

    private fun clearPlan() {
        plan = null
        aimPoint = null
        faceOffset = Vec3.ZERO
        faceOffsetUntil = 0L
        missTicks = 0
    }

    /** The item of the first hotbar or off-hand slot holding blocks. */
    private fun blockStack(): ItemStack? = blockSlot()?.itemStack?.takeIf { it.count > 0 }

    private fun blockSlot() = Slots.OffhandWithHotbar.findSlot { stack ->
        stack.item is BlockItem && stack.count > 0
    }

    // --- the click --------------------------------------------------------------------------

    /**
     * The placement, gated by the rotation.
     *
     * The rotation the server already has must be on the aimed point (degrees) **and** the ray from
     * the eyes with that rotation must land on exactly that block and that face — the degree check
     * alone would let a click through that the server resolves somewhere else.
     */
    private fun tryPlace(me: LocalPlayer) {
        val target = plan ?: return
        // The aim may already be down there while the fall has not really started: the click waits.
        if (!clutching(me)) return
        if (me.tickCount - lastPlaceTick < placeCooldown) return

        if (!clickable(me, target)) {
            plan = null
            return
        }

        val server = RotationManager.serverRotation
        val point = aimPoint ?: target.interactionPoint
        val offset = server.directionAngleTo(Rotation.lookingAt(point, me.eyePosition))
        if (offset > rotationTolerance) {
            status = "rotazione a %.1f° dalla faccia".format(offset)
            return
        }

        val trace = runCatching { traceFromPlayer(server, range = me.blockInteractionRange() + 1.0) }
            .getOrNull()
        if (trace == null || !target.doesCrosshairTargetMatchRequirements(trace)) {
            status = "il mirino del server non è sulla faccia"
            missTicks++
            return
        }

        val slot = blockSlot() ?: run {
            status = "nessun blocco"
            plan = null
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
                onPlacementSuccess = { done = true; true },
                onItemUseSuccess = { done = true; true },
                swingMode = swingMode,
            )
        }.onFailure { status = "piazzamento fallito (${it.javaClass.simpleName})" }

        lastPlaceTick = me.tickCount
        if (!done) {
            status = "il piazzamento non è passato"
            return
        }

        placed++
        missTicks = 0
        renderer.addBlock(target.placedBlock)
        status = "piazzato " + target.placedBlock.toShortString()
        if (chatLog) chat("§7[Clutch] §f$placed §7blocchi")
        plan = null
    }

    // --- debug ------------------------------------------------------------------------------

    /**
     * One line every second, fixed format, always the same fields: `fall` (how far the fall is now),
     * `cell` (what is being clicked), `aim` (the point), `rot` (the angle the server is off by) and
     * the reason the tick ended the way it did. A clutch either happens or it does not — this line
     * says which of the two, and where.
     */
    private fun reportStatus(me: LocalPlayer) {
        if (!debug) return
        if (me.tickCount % 20 != 0) return

        val point = aimPoint
        val rot = if (point != null) {
            RotationManager.serverRotation.directionAngleTo(Rotation.lookingAt(point, me.eyePosition))
        } else {
            -1f
        }
        val cell = plan?.placedBlock?.toShortString() ?: "—"
        val aimText = point?.let { "%.1f,%.1f,%.1f".format(it.x, it.y, it.z) } ?: "—"
        chat(
            "§8[Clutch D] §7fall §f%.1f §7cell §f%s §7aim §f%s §7rot §f%.1f° §7| §f%s".format(
                me.fallDistance, cell, aimText, rot, status
            )
        )
    }

    /** Ticks the silently selected slot is kept before LiquidBounce restores the real one. */
    private const val SLOT_RESET_TICKS = 4

    /** How far below the feet a support still counts as "about to land". */
    private const val SUPPORT_DEPTH = 2

    /** Half-width of the spot the aim picks inside a face at `Humanize 100`, in blocks. */
    private const val FACE_SPREAD_MAX = 0.26

    /** Keeps the aimed point inside the clicked block, and above the source box of the face. */
    private const val FACE_MARGIN = 0.04

    /** Ticks a spot on the face may miss the ray before the aim goes back to the face centre. */
    private const val FACE_GIVE_UP_TICKS = 4

    private val SIDES = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
}
