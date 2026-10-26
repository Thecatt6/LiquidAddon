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
import net.ccbluex.liquidbounce.utils.client.isOlderThanOrEqual1_8
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.item.isSword
import net.ccbluex.liquidbounce.utils.network.releaseUsingItemInTickLoop
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemUseAnimation
import kotlin.random.Random

/**
 * BlockHit — the block hit, done for you: **a click turns into a block**.
 *
 * A block is started the same way the right mouse button starts one (`useItem` on the hand that can
 * block), held for [blockTicks], and released with the vanilla release action. The point of a
 * *block hit* is to be blocking in the ticks around your own attack: the hit that lands inside that
 * window is cut, while your attack still lands. Doing it by hand means alternating left and right
 * click at your own CPS; this module does it for you, one block per click.
 *
 * It never clicks for you: it reacts to the clicks that happen. `MultiPlayerGameMode.attack` fires
 * [AttackEntityEvent] both for your own left clicks and for the ones an AutoClicker (or a KillAura)
 * sends through the vanilla path, so the module works with all of them and stays silent when you are
 * not clicking at all.
 *
 * ## Which hand blocks
 *
 * The hand whose item **really blocks** is used ([ItemUseAnimation.BLOCK]): a shield, or a sword on
 * a protocol where swords block. On a 1.8 server (ViaFabricPlus) there is no such client-side
 * animation at all — the server treats a main-hand use as a sword block — so a main-hand sword is
 * used there as well and the block exists on the server only.
 *
 * This is the difference between "BlockHit does nothing" and working: a modern client has **no**
 * sword blocking, so with only a sword in hand and no shield there is nothing to start, and the
 * module says so instead of pretending. With a shield in the off hand it raises the shield.
 *
 * ## What it does per click
 *
 *  - [chance] decides whether this click becomes a block hit at all;
 *  - [requireMouseDown] restricts it to the moments the right mouse button is held (physically, or
 *    by an AutoClicker's use button), so it only ever helps an input you are already making;
 *  - the block starts [delay] ticks after the click and lasts [blockTicks] ticks, then the sword (or
 *    shield) is released with the vanilla release action;
 *  - while the block is up, the client really is using the item: `useItem`, the same call vanilla's
 *    right click makes, so the server sees an ordinary use. Nothing is rewritten and the blocking
 *    animation is vanilla's.
 *
 * ## The two modes
 *
 *  - [Mode.MANUAL] — the block hit on its own: click, block, release. Nothing else is touched.
 *  - [Mode.LAG] — the same, plus a short hold of the outgoing packets right after the block starts
 *    ([lagTicks]). The use that starts the block leaves normally, then the packets that follow (the
 *    release included) are queued through the client's normal `BlinkManager`: on the server the
 *    block stays up for that long. The queue is drained as soon as the window closes and it is never
 *    flushed from here, so a FakeLag/Backtrack holding packets keeps its own.
 *
 * A block the player is holding down is never released: with the right button pressed vanilla owns
 * the state, and stopping it would show up as a stutter in the animation.
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
     * AutoClicker is holding.
     */
    private val requireMouseDown by boolean("RequireMouseDown", false)

    /**
     * `Manual` — just the block hit. `Lag` — the block hit, then a short hold of the outgoing
     * packets so the server keeps the block up for [lagTicks] ticks.
     */
    private val mode by enumChoice("Mode", Mode.MANUAL)

    /** Ticks between the click and the block. 1 by default: the block is up before the next attack. */
    private val delay by int("Delay", 1, 0..5, "ticks")

    /** How long the block is held, in ticks. Two ticks cover the window around one click. */
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

    /** The hand our block was started with. */
    private var blockHand: InteractionHand? = null

    /** Set once, so "there is nothing here that can block" is said once and not every click. */
    private var warnedNoBlock = false

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
        blockHand = null
        warnedNoBlock = false
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
            note("skipped ($lastSkip)")
            return@handler
        }
        if (chance <= 0 || Random.nextInt(100) >= chance) {
            note("chance roll failed")
            return@handler
        }
        if (me.isUsingItem) {
            // Already blocking — the player holds right click, or a previous block hit is still up.
            // That state is the one we wanted anyway: leave it alone.
            counted()
            note("already blocking, counted")
            return@handler
        }
        if (blockingHand(me) == null) {
            if (!warnedNoBlock) {
                warnedNoBlock = true
                chat(
                    "§7[BlockHit] §csulla tua versione la spada non blocca: " +
                        "serve uno scudo nella mano secondaria"
                )
            }
            note("no blocking item in hand")
            return@handler
        }

        startAt = me.tickCount + delay
        note("block hit planned (in $delay ticks, $blockTicks ticks long)")
    }

    // --- the block ------------------------------------------------------------------------

    /**
     * One tick: start the block when its tick comes, release it when it is over, close the lag
     * window. All of it on `GameTickEvent`, so the use call lands in the tick loop and its packet
     * goes out in the same tick as the movement packet, like vanilla's right click.
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
                    // The use packet that starts the block already left: the hold begins now, so
                    // what stays behind is the release, which is what keeps the block up for longer
                    // on the server.
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
     * The hand whose item can block, or `null` when there is none.
     *
     * The rule is LiquidBounce's own (`KillAuraAutoBlock.findBlockableHand`): an item whose use
     * animation is [ItemUseAnimation.BLOCK] — a shield, or a sword on the protocols where swords
     * block. On a 1.8 server the server treats a main-hand use as a sword block even though this
     * client has no animation for it, so the main hand is used there too.
     */
    private fun blockingHand(me: LocalPlayer): InteractionHand? {
        val level = mc.level
        val animated = InteractionHand.entries.firstOrNull { hand ->
            val stack = me.getItemInHand(hand)
            stack.useAnimation == ItemUseAnimation.BLOCK &&
                (level == null || stack.isItemEnabled(level.enabledFeatures())) &&
                !me.cooldowns.isOnCooldown(stack)
        }
        if (animated != null) {
            return animated
        }
        return if (isOlderThanOrEqual1_8 && me.mainHandItem.isSword) {
            InteractionHand.MAIN_HAND
        } else {
            null
        }
    }

    /**
     * Starts the block exactly the way the right click does: `useItem` on the hand that can block.
     *
     * Nothing is required afterwards beyond the call itself. On a 1.8 server the client has no use
     * animation for a sword, so `me.isUsingItem` stays `false` although the server is blocking;
     * demanding it here is what made the module refuse every block on those servers.
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
        val hand = blockingHand(me) ?: run {
            lastSkip = "no blocking item in hand"
            return false
        }

        runCatching { useItem(hand) }

        // The client is using the item: a shield (or a blocking sword) really is up.
        val using = me.isUsingItem
        // On a 1.8 server the sword blocks on the server only: this client has no animation for it,
        // so "isUsingItem" says nothing there and the use packet is all that matters.
        val serverSideSword = !using && isOlderThanOrEqual1_8 && hand == InteractionHand.MAIN_HAND

        if (!using && !serverSideSword) {
            lastSkip = "nothing started blocking"
            note("the use did not start a block")
            return false
        }

        owningBlock = true
        blockHand = hand
        note("blocking with " + hand.name.lowercase())
        return true
    }

    /**
     * Ends our block with the vanilla release action. A block the player is holding down is never
     * released: with the right button pressed vanilla owns the state and would only have to start it
     * again on the next tick, which shows up as a stutter in the animation.
     */
    private fun releaseBlock(me: LocalPlayer) {
        if (!owningBlock) {
            return
        }
        owningBlock = false
        blockHand = null
        if (!me.isUsingItem || mc.options.keyUse.isPressedOnAny) {
            return
        }
        runCatching { interaction.releaseUsingItemInTickLoop() }
    }

    /** Releases immediately, for disable: the module must not leave the player blocking. */
    private fun releaseNow() {
        if (!owningBlock) {
            return
        }
        owningBlock = false
        blockHand = null
        val me = mc.player ?: return
        if (!me.isUsingItem || mc.options.keyUse.isPressedOnAny) {
            return
        }
        runCatching { interaction.releaseUsingItemInTickLoop() }
    }

    // --- the lag --------------------------------------------------------------------------

    /**
     * `Mode = Lag`: while the window is open, outgoing packets are queued instead of sent.
     *
     * The null packet is `BlinkManager` asking "may I flush now?" once per frame and once per tick;
     * answering `PASS` keeps the queue from being emptied in the middle of the window. When the
     * window is over nothing is answered at all, i.e. the default `FLUSH` answer stands, and the
     * manager drains the queue on its own. Answering `PASS` there as well was the bug that left the
     * queue stuck forever — the connection stayed frozen after the first block hit.
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
            blockHand = null
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
