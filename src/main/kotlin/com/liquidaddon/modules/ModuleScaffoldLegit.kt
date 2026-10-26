package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.render.placement.PlacementRenderer
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.lang.reflect.Field
import java.lang.reflect.Method
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

    /** Set when a click went through the vanilla path, so the box is drawn only if the block landed. */
    private var pendingRender: BlockPos? = null

    /** Ticks [pendingRender] is still allowed to wait for its block before the box is forgotten. */
    private var pendingRenderTicks = 0

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

    private var steps = 0
    private var misses = 0
    private var airTickSum = 0

    /** KeyMapping.clickCount: the clicks queued for handleKeybinds (private; 26.x names are not obfuscated). */
    private val clickCount: Field by lazy {
        KeyMapping::class.java.getDeclaredField("clickCount").apply { isAccessible = true }
    }

    /** Minecraft.pick(float) is private: the crosshair of this tick, before handleKeybinds reads the clicks. */
    private val pickMethod: Method? by lazy {
        runCatching {
            Minecraft::class.java.getDeclaredMethod("pick", Float::class.javaPrimitiveType).apply { isAccessible = true }
        }.getOrNull()
    }

    override val tag: String
        get() = if (steps == 0) "" else steps.toString()

    override fun onEnabled() {
        reset()
        steps = 0
        misses = 0
        airTickSum = 0
        pendingRender = null
        pendingRenderTicks = 0
        super.onEnabled()
    }

    override fun onDisabled() {
        // Let the boxes that are still on screen run their fade-out instead of vanishing: this is
        // what the normal Scaffold does when it is turned off.
        pendingRender = null
        pendingRenderTicks = 0
        renderer.clearSilently()
        super.onDisabled()
    }

    private fun reset() {
        phase = Phase.FLAT
        stepBlock = null
        readyTicks = 0
        jumpNow = false
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
            stepBlock = me.onPos
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
        }
        when (phase) {
            Phase.AIR -> airTick(me)
            Phase.FLAT -> if (mode == Mode.STAIRS && spaceHeld && me.onGround()) flatTick(me)
            Phase.WALK -> if (me.onGround()) walkTick(me)
        }
    }

    /** 1: at the edge, the click that puts a block behind at the level of the one under the feet. */
    private fun flatTick(me: LocalPlayer) {
        if (!atEdge(me, lastKeys)) {
            debug(me, "piano: non sei sul bordo (tieni S verso il vuoto)")
            readyTicks = 0
            return
        }
        val under = me.onPos
        val cell = clickCell(me) ?: run {
            debug(me, "piano: sul bordo, ma il mirino non mette un blocco dietro (" + crosshairText() + ")")
            readyTicks = 0
            return
        }
        if (cell.y != under.y) {
            debug(me, "piano: il blocco andrebbe al livello " + cell.y + ", non a quello sotto i piedi " + under.y)
            readyTicks = 0
            return
        }
        debug(me, "piano: clic sulla faccia laterale")
        if (readyTicks == 0) {
            delay = drawShiftDelay()
        }
        if (readyTicks++ < delay) {
            return
        }
        place(me, cell)
        phase = Phase.WALK
        stepBlock = cell
        waitTicks = 0
        readyTicks = 0
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
        if (me.onPos == block && past >= jumpAt) {
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
                }
                report(me)
                reset()
            }
            return
        }
        airTicks++
        if (placedInAir || airTicks > 12) {
            return
        }
        // Peak: the block waits until the jump stops rising
        if (placeTiming == Timing.PEAK && me.deltaMovement.y > 0.0) {
            return
        }
        val cell = clickCell(me)
        if (cell == null || cell != from.above()) {
            debug(me, "aria tick $airTicks: il mirino non mette il blocco sopra (" + crosshairText() + ")")
            return
        }
        if (readyTicks++ < delay) {
            return
        }
        place(me, cell)
        debug(me, "aria tick $airTicks: clic sulla faccia di sopra")
        placedInAir = true
        airTickSum += airTicks
    }

    private fun report(me: LocalPlayer) {
        if (!chatLog) {
            return
        }
        if (!placedInAir) {
            me.sendSystemMessage(Component.literal("ยง7[EagleUpTest] gradino mancato: tieni la visuale ferma, in basso "
                    + "verso il ponte (circa 80 gradi)"))
        } else if (steps % 5 == 0) {
            me.sendSystemMessage(Component.literal("ยง7[EagleUpTest] gradini $steps (mancati $misses), tick salto medio "
                    + "%.2f, %s, shift %d-%d, jump %d-%d".format(
                airTickSum.toDouble() / steps, placeTiming.tag,
                shiftDelay.first, shiftDelay.last, jumpDelay.first, jumpDelay.last
            )))
        }
    }

    /**
     * The cell the block would go into if the use key were pressed now (crosshair refreshed), if it is useful for
     * the bridge: free, below the feet's level, next to the player's footprint and not inside the player; else null.
     */
    private fun clickCell(me: LocalPlayer): BlockPos? {
        pickMethod?.let { method -> runCatching { method.invoke(mc, 1.0f) } }
        val hit = mc.hitResult as? BlockHitResult ?: return null
        if (hit.type != HitResult.Type.BLOCK) {
            return null
        }
        val cell = hit.blockPos.relative(hit.direction)
        if (cell.y > floor(me.y + 1.0e-3).toInt() - 1) {
            return null
        }
        val cellBox = AABB(cell)
        val box = me.boundingBox
        if (box.intersects(cellBox) || !box.inflate(0.6, 0.0, 0.6).intersects(cellBox.minX, box.minY - 3.0, cellBox.minZ,
                cellBox.maxX, box.maxY, cellBox.maxZ)) {
            return null
        }
        val level = mc.level ?: return null
        return if (level.getBlockState(cell).canBeReplaced()) cell else null
    }

    /** Debug: what is under the crosshair (block and face). */
    private fun crosshairText(): String {
        val hit = mc.hitResult as? BlockHitResult ?: return "niente"
        if (hit.type != HitResult.Type.BLOCK) {
            return "niente"
        }
        return "faccia " + hit.direction.name.lowercase() + " del blocco " + hit.blockPos.x + " " + hit.blockPos.y + " " +
                hit.blockPos.z + ", pitch " + "%.0f".format(mc.player?.xRot ?: 0f)
    }

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
     * Places the block at [cell] — the cell [clickCell] already validated — and hands it to the
     * [renderer], which draws the box that appears on it and fades out again, exactly like the box
     * LiquidBounce's own Scaffold draws on the blocks it places.
     *
     * The placement goes through LiquidBounce's `doPlacement`, the same helper the normal Scaffold
     * uses, so the block lands on the tick the module decided on and the swing is reported the way
     * vanilla does. `onPlacementSuccess` is the callback that runs only when the placement really
     * happened: that is where the box is added, so a click that the server or the client refuses
     * never leaves a phantom box behind.
     *
     * If the crosshair is not on a block any more (it was a few lines earlier: this is only a
     * safety net), the click is queued for `handleKeybinds` as before and the box is drawn from
     * [tickHandler] once the block is really there.
     */
    private fun place(me: LocalPlayer, cell: BlockPos) {
        val hit = mc.hitResult as? BlockHitResult
        if (hit != null && hit.type == HitResult.Type.BLOCK) {
            val done = runCatching {
                doPlacement(hit, Rotation(me.yRot, me.xRot), onPlacementSuccess = {
                    renderer.addBlock(cell)
                    pendingRender = null
                    true
                })
            }.isSuccess
            if (done) return
        }
        val key = mc.options.keyUse
        clickCount.setInt(key, clickCount.getInt(key) + 1)
        // The vanilla path places during handleKeybinds: the box is added once the block is there.
        pendingRender = cell
        pendingRenderTicks = 0
    }

    /**
     * Draws the box for a placement that went through the vanilla click path, but only after the
     * block has actually appeared in the world, so a refused click cannot render anything.
     */
    private fun flushPendingRender() {
        val cell = pendingRender ?: return
        val level = mc.level ?: return
        // A refused click must not leave this waiting forever and then draw a box on a block that
        // somebody else placed: half a second is plenty for the click to be processed.
        if (++pendingRenderTicks > 10) {
            pendingRender = null
            return
        }
        if (!level.getBlockState(cell).canBeReplaced()) {
            renderer.addBlock(cell)
            pendingRender = null
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

    private fun holdingBlock(me: LocalPlayer): Boolean = me.mainHandItem.item is BlockItem

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