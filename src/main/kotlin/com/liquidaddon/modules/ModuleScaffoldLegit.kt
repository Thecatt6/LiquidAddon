package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
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
 * Why timing is enough: with the view still, looking down at about 80 degrees towards the bridge, the crosshair falls
 * on the SIDE face of the block under the feet while standing at its edge (the ray passes beyond the edge), so a
 * click there places a block behind at the same level; at the top of a jump the eyes are 1.25 blocks higher and the
 * same ray falls on the TOP face of that block, so a click places a block on top of it. Edge click, step back onto
 * it, jump, click at the top, land one block higher: the staircase two blocks thick.
 *
 * Mode Stairs (hold space; your own jumps are held back, the module jumps):
 *  1. on the ground, Eagle sneaks at the edge; there, once the crosshair's face would place a block behind under the
 *     feet's level, it right-clicks (after MinDelay..MaxDelay ticks);
 *  2. it waits until the player has walked back onto that block, past its middle by JumpAt, then jumps straight up:
 *     shift and the walking keys are released for the whole jump (walking on, the player would drift almost one
 *     block back in the air, the crosshair would no longer reach the top face and the step would fall into the void);
 *  3. in the air, at PlaceTiming (Early = first tick the block fits under the feet, 3rd tick of the jump; Peak = top of
 *     the jump) plus the delay, if the crosshair's face would place a block on top of the one jumped from, it
 *     right-clicks; the player lands one block higher and it starts again from 1.
 * Mode Step: Eagle while walking; each space press is one jump with the click of point 3 (one block up).
 *
 * Every click is a click: one more press queued on the use key (KeyMapping.clickCount) at the HEAD of Minecraft.tick,
 * after refreshing the crosshair (Minecraft.pick, private); vanilla startUseItem places it with the player's aim, the
 * normal USE_ITEM_ON packet and swing. A click is only queued when the block under the crosshair's face would really
 * go where the bridge needs it (free cell, under the feet's level, next to the player, not inside the player).
 *
 * Settings:
 *   Mode             Stairs (hold space = the whole staircase) or Step (space = one block up)
 *   Eagle            sneak at the edge on the ground
 *   EdgeDistance     how far ahead (blocks, along the movement keys) the edge is looked for
 *   JumpAt           Stairs: how far past the middle of the new block (blocks, along the walk) the jump starts
 *   PlaceTiming      Early or Peak: when the block goes under the feet during the jump
 *   MinDelay         ticks to wait, at least, before each click once its moment came
 *   MaxDelay         ticks to wait, at most (random in between, every click)
 *   ChatLog          every 5 steps: how many, misses, and at which tick of the jump the block went down
 *   Debug            writes in chat what the module waits for, whenever it changes
 *
 * Only on the test servers (TestServers: single player, local/LAN, anticheat-test.com).
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
    private val edgeDistance by float("EdgeDistance", 0.2f, 0.05f..0.5f)
    private val jumpAt by float("JumpAt", 0.1f, -0.3f..0.45f)
    private val placeTiming by enumChoice("PlaceTiming", Timing.PEAK)
    private val minDelay by int("MinDelay", 0, 0..8, "ticks")
    private val maxDelay by int("MaxDelay", 1, 0..8, "ticks")
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
        super.onEnabled()
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
            delay = drawDelay()
        } else if (eagle && me.onGround() && phase != Phase.AIR && atEdge(me, event.directionalInput)) {
            event.sneak = true
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player ?: return@handler
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
            delay = drawDelay()
        }
        if (readyTicks++ < delay) {
            return
        }
        click()
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
        click()
        debug(me, "aria tick $airTicks: clic sulla faccia di sopra")
        placedInAir = true
        airTickSum += airTicks
    }

    private fun report(me: LocalPlayer) {
        if (!chatLog) {
            return
        }
        if (!placedInAir) {
            me.sendSystemMessage(Component.literal("§7[EagleUpTest] gradino mancato: tieni la visuale ferma, in basso "
                    + "verso il ponte (circa 80 gradi)"))
        } else if (steps % 5 == 0) {
            me.sendSystemMessage(Component.literal("§7[EagleUpTest] gradini $steps (mancati $misses), tick del salto medio "
                    + "%.2f, %s, ritardo %d-%d".format(airTickSum.toDouble() / steps, placeTiming.tag, minDelay, maxDelay)))
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

    /** One right click for this tick's handleKeybinds, as if the player had pressed the use key. */
    private fun click() {
        val key = mc.options.keyUse
        clickCount.setInt(key, clickCount.getInt(key) + 1)
    }

    private fun drawDelay(): Int = minDelay + if (maxDelay > minDelay) Random.nextInt(maxDelay - minDelay + 1) else 0

    private fun holdingBlock(me: LocalPlayer): Boolean = me.mainHandItem.item is BlockItem

    /** Eagle: the block below the point EdgeDistance ahead, along where the keys push, is air. */
    private fun atEdge(me: LocalPlayer, keys: DirectionalInput): Boolean {
        val forward = (if (keys.forwards) 1.0 else 0.0) - (if (keys.backwards) 1.0 else 0.0)
        val strafe = (if (keys.left) 1.0 else 0.0) - (if (keys.right) 1.0 else 0.0)
        val length = hypot(forward, strafe)
        if (length < 1e-6) {
            return false
        }
        // Entity.moveRelative: forward along the yaw, left = the yaw minus 90 degrees
        val yaw = Math.toRadians(me.yRot.toDouble())
        val dx = (strafe * cos(yaw) - forward * sin(yaw)) / length
        val dz = (forward * cos(yaw) + strafe * sin(yaw)) / length
        val level = mc.level ?: return false
        val below = BlockPos.containing(me.x + dx * edgeDistance, me.y - 0.5, me.z + dz * edgeDistance)
        return level.getBlockState(below).isAir
    }
}
