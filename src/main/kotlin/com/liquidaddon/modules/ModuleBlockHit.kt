package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.BlinkPacketEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.item.isSword
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionHand
import kotlin.random.Random

/**
 * BlockHit — the block hit, done for you: **every click has a chance to turn into a block**.
 *
 * With a sword in the main hand, right-clicking starts a block (1.8 style blocking, and since the
 * newer combat updates the same action exists on modern servers). The point of a *block hit* is to
 * be blocking in the ticks around your own attack: the opponent's hit that lands in that window is
 * cut, while your attack still lands. Doing it by hand means alternating left and right click at
 * your own CPS; this module does it for you, one block per click.
 *
 * It never clicks for you: it reacts to the clicks that happen. `MultiPlayerGameMode.attack` fires
 * [AttackEntityEvent] both for your own left clicks and for the ones an AutoClicker (or a KillAura)
 * sends through the vanilla path, so the module works with all of them and stays silent when you
 * are not clicking at all — the classic "it blocks on its own while I do nothing" is exactly what
 * this module does not do.
 *
 * ## What it does per click
 *
 *  - [chance] decides whether this click becomes a block hit at all;
 *  - [requireMouseDown] restricts it to the moments the right mouse button is held (physically, or
 *    by an AutoClicker's use button), so it only ever helps an input you are already making;
 *  - the block starts [delay] ticks after the click — one tick by default, the way a hand answers an
 *    attack — and lasts [blockTicks] ticks, then the sword is released with the vanilla release
 *    packet;
 *  - while the block is up, the client is *actually* blocking: `useItem` on the main hand, the same
 *    call vanilla's right click makes, so the server sees an ordinary use. Nothing is faked, no
 *    packet is rewritten, and the arm animation is vanilla's.
 *
 * ## The two modes
 *
 *  - [Mode.MANUAL] — the block hit on its own: click, block, release. Nothing else is touched.
 *  - [Mode.LAG] — the same, plus a short lag right after the block starts ([lagTicks]). The use
 *    packet that starts the block leaves normally, then the connection holds the packets that
 *    follow (the release included) for a few ticks: on the server the block stays up for that long,
 *    so an incoming hit inside the window is still reduced, and when the queue drains everything
 *    arrives in order, one burst. It uses the client's normal `BlinkManager` queue and never flushes
 *    it on its own, so it cannot steal the packets another lag module (FakeLag, Backtrack, …) is
 *    holding: they flush theirs, and ours leaves with them.
 *
 * If the sword is already blocking — you are holding right click, or you have Require Mouse Down on
 * — the module does not fight the client: it counts the block hit and leaves the block state to
 * vanilla, because releasing a block the player is holding is the last thing a "legit" module
 * should do.
 */
object ModuleBlockHit : ClientModule("BlockHit", Categories.LIQUIDADDON) {

    enum class Mode(override val tag: String) : Tagged {
        MANUAL("Manual"),
        LAG("Lag")
    }

    /** No tick scheduled. */
    private const val NO_TICK = -1

    /** Probability that a click becomes a block hit. */
    private val chance by int("Chance", 100, 0..100, "%")

    /**
     * Only work while the right mouse button is held — the physical button, or the use button an
     * AutoClicker is holding. The module then never blocks unless the player is already pressing the
     * button that blocks, so every block hit is an answer to an input instead of an input on its own.
     */
    private val requireMouseDown by boolean("RequireMouseDown", false)

    /**
     * `Manual` — just the block hit. `Lag` — the block hit, then a short hold of the outgoing
     * packets so the server keeps the block up for [lagTicks] ticks.
     */
    private val mode by enumChoice("Mode", Mode.MANUAL)

    /** Ticks between the click and the block. 1 by default: the block is up before the next attack. */
    private val delay by int("Delay", 1, 0..5, "ticks")

    /** How long the block is held, in ticks. Two ticks cover the whole window around one click. */
    private val blockTicks by int("BlockTicks", 2, 1..10, "ticks")

    /** `Mode = Lag` only: how many ticks the packets after the block start are held back. */
    private val lagTicks by int("LagTicks", 4, 1..10, "ticks")

    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

    /** `tickCount` at which the block has to start, or [NO_TICK]. */
    private var startAt = NO_TICK

    /** `tickCount` at which the block has to be released, or [NO_TICK]. */
    private var releaseAt = NO_TICK

    /** `tickCount` until which outgoing packets are held (`Mode = Lag`), or [NO_TICK]. */
    private var lagUntil = NO_TICK

    /** True while the block currently up is *ours*: only our own block is ever released. */
    private var owningBlock = false

    private var clicks = 0
    private var blocks = 0
    private var lagged = 0
    private var lastSkip = "idle"

    override val tag: String
        get() = if (clicks == 0) "" else "$blocks/$clicks"

    override fun onEnabled() {
        reset()
        super.onEnabled()
    }

    override fun onDisabled() {
        // Never leave the sword in the player's hand up: the block has to end with the module.
        releaseNow()
        reset()
        super.onDisabled()
    }

    private fun reset() {
        startAt = NO_TICK
        releaseAt = NO_TICK
        lagUntil = NO_TICK
        owningBlock = false
        clicks = 0
        blocks = 0
        lagged = 0
        lastSkip = "idle"
    }

    // --- the click ------------------------------------------------------------------------

    /**
     * A click just happened. Both the player's own left click and the ones an AutoClicker sends end
     * up here, because both go through `MultiPlayerGameMode.attack`, whose head fires this event.
     */
    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> {
        val me = mc.player ?: return@handler
        clicks++

        if (!canOperate(me)) {
            note("skipped (${lastSkip})")
            return@handler
        }
        if (chance <= 0 || Random.nextInt(100) >= chance) {
            note("chance roll failed")
            return@handler
        }
        if (me.isUsingItem) {
            // The sword is already blocking: either the player holds right click or a previous block
            // hit is still up. Leave that state alone, it is the one we wanted anyway.
            counted()
            note("already blocking, counted")
            return@handler
        }

        startAt = me.tickCount + delay
        note("block hit planned (in $delay ticks, $blockTicks ticks long)")
    }

    // --- the block ------------------------------------------------------------------------

    /**
     * One tick: start the block when its tick comes, release it when it is over, and close the lag
     * window. All of it on `GameTickEvent`, so the `useItem` call below lands in the tick loop and
     * its packet goes out in the same tick as the movement packet, like vanilla's right click.
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player ?: return@handler

        if (startAt != NO_TICK && me.tickCount >= startAt) {
            startAt = NO_TICK
            if (startBlocking(me)) {
                counted()
                releaseAt = me.tickCount + blockTicks
                if (mode == Mode.LAG) {
                    // The use packet that starts the block was already sent by [startBlocking]: the
                    // hold begins now, so what stays behind is the rest of the block — the release
                    // first of all, which is what keeps the block up on the server.
                    lagUntil = me.tickCount + lagTicks
                    lagged++
                    note("blocking, lagging $lagTicks ticks")
                } else {
                    note("blocking for $blockTicks ticks")
                }
            }
        }

        if (releaseAt != NO_TICK && me.tickCount >= releaseAt) {
            releaseAt = NO_TICK
            releaseBlock(me)
        }

        if (lagUntil != NO_TICK && me.tickCount > lagUntil) {
            lagUntil = NO_TICK
            note("lag window closed")
        }
    }

    /**
     * Starts the block exactly the way the right click does: `useItem` on the main hand. Returns
     * `true` when the client really is using the item afterwards — a sword that is not allowed to
     * block (an older protocol, a server that stripped the component) leaves the state untouched and
     * the block hit is dropped instead of being counted twice.
     */
    private fun startBlocking(me: LocalPlayer): Boolean {
        if (mc.gameMode == null) {
            lastSkip = "not in game"
            return false
        }
        if (mc.gui.screen() != null) {
            lastSkip = "screen open"
            return false
        }
        if (!me.mainHandItem.isSword) {
            lastSkip = "not holding a sword"
            return false
        }
        if (me.isUsingItem) {
            lastSkip = "already using an item"
            return false
        }

        runCatching { interaction.useItem(me, InteractionHand.MAIN_HAND) }

        if (!me.isUsingItem) {
            lastSkip = "the sword did not start blocking"
            note("block rejected by the item")
            return false
        }
        owningBlock = true
        return true
    }

    /**
     * Ends our block with the vanilla release packet. A block the player is holding down is never
     * released: with the right button pressed vanilla owns the state and would only have to start it
     * again on the next tick, which would show up as a stutter in the swing animation.
     */
    private fun releaseBlock(me: LocalPlayer) {
        if (!owningBlock) {
            return
        }
        owningBlock = false
        if (!me.isUsingItem || mc.options.keyUse.isPressedOnAny) {
            return
        }
        runCatching { interaction.releaseUsingItem(me) }
    }

    /** Releases immediately, for disable: the module must not leave the player blocking. */
    private fun releaseNow() {
        if (!owningBlock) {
            return
        }
        owningBlock = false
        val me = mc.player ?: return
        if (!me.isUsingItem || mc.options.keyUse.isPressedOnAny) {
            return
        }
        runCatching { interaction.releaseUsingItem(me) }
    }

    // --- the lag --------------------------------------------------------------------------

    /**
     * `Mode = Lag`: while the window is open, outgoing packets are queued instead of sent.
     *
     * The null packet is `BlinkManager` asking "may I flush now?" once per frame and once per tick;
     * answering `PASS` keeps the queue from being emptied in the middle of the window, which is the
     * same answer every module that holds packets gives. Nothing is ever flushed from here: the
     * manager drains the queue on its own on the first frame the window is closed, and if another
     * module is holding packets they leave together with ours, in order.
     */
    @Suppress("unused")
    private val blinkHandler = handler<BlinkPacketEvent> { event ->
        if (lagUntil == NO_TICK) {
            return@handler
        }
        if (event.origin != TransferOrigin.OUTGOING) {
            return@handler
        }
        val me = mc.player
        if (me == null || me.tickCount > lagUntil) {
            lagUntil = NO_TICK
            return@handler
        }
        if (event.packet == null) {
            event.action = BlinkManager.Action.PASS
            return@handler
        }
        event.action = BlinkManager.Action.QUEUE
    }

    // --- guards ---------------------------------------------------------------------------

    /** In game, with a screen closed, and — when asked — with the right button actually held. */
    private fun canOperate(me: LocalPlayer): Boolean {
        if (mc.gameMode == null) {
            lastSkip = "not in game"
            return false
        }
        if (mc.gui.screen() != null) {
            lastSkip = "screen open"
            return false
        }
        if (!me.mainHandItem.isSword) {
            lastSkip = "not holding a sword"
            return false
        }
        // `isPressedOnAny` is the physical right button *or* a module (an AutoClicker's use button)
        // holding it: both are the player asking to block.
        if (requireMouseDown && !mc.options.keyUse.isPressedOnAny) {
            lastSkip = "right button not held"
            return false
        }
        return true
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { event ->
        if (event.world == null) {
            // A new world has no block of ours in it, and no tick count that means anything.
            owningBlock = false
            reset()
        }
    }

    /** One block hit landed: count it, and give the running total once every ten. */
    private fun counted() {
        blocks++
        if (chatLog && blocks % 10 == 0) {
            chat("§7[BlockHit] §f$blocks block hits out of $clicks clicks (lagged $lagged)")
        }
    }

    private fun note(message: String) {
        if (debug) {
            chat("§7[BlockHit] §f$message")
        }
    }
}
