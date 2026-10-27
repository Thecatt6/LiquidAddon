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
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleSwordBlock
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.isBlocksAttacksExisting
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.item.isSword
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemUseAnimation
import kotlin.math.abs
import kotlin.random.Random

/**
 * BlockHit — the block hit, done for you: **a click turns into a block**.
 *
 * A block is opened the moment after a click and closed [blockTicks] ticks later, with the two
 * packets the right button itself uses ([ServerboundUseItemPacket], then the vanilla release
 * action). The point of a *block hit* is to be blocking in the ticks around your own attack: the
 * hit that lands inside that window is cut, while your attack still lands. Doing it by hand means
 * alternating left and right click at your own CPS; this module does it for you, one block per
 * click.
 *
 * It never clicks for you: it reacts to the clicks that happen. `MultiPlayerGameMode.attack` fires
 * [AttackEntityEvent] both for your own left clicks and for the ones an AutoClicker (or a KillAura)
 * sends through the vanilla path, so the module works with all of them and stays silent when you
 * are not clicking at all.
 *
 * ## The block is opened on the server, not on the client
 *
 * That is the one thing this module cannot do differently, and the reason the block used to do
 * nothing. LiquidBounce rewrites the vanilla input handling: while `player.isUsingItem` is true and
 * the right button is *not* held, the client releases the item by itself, and every left click of
 * that tick is thrown away (`keyAttack.clickCount = 0`) unless MultiActions allows attacking while
 * using. A block started the vanilla way — `useItem(hand)`, i.e. the client really raising the
 * shield — is therefore **released in the very tick it starts**, and it eats the click that should
 * have been the next hit: the old version blocked nothing and stopped the player from hitting.
 *
 * So the client is never put into the "using item" state. The two packets are sent on their own,
 * which is all a block hit needs anyway: the damage is cut on the server, not on this screen. As a
 * bonus the client keeps moving and clicking at full speed, and nothing here can fight with the
 * block the player is holding down themselves. The cost is that you do not see your own shield go
 * up for those two ticks — the block is there, it just is not drawn.
 *
 * ## Which hand blocks
 *
 * The hand whose item **really blocks**: one whose use animation is [ItemUseAnimation.BLOCK], which
 * is a shield, or a sword on a protocol where this client animates swords as blocking.
 *
 * A sword the *server* blocks with but this client does not animate is used too, when it will be
 * understood:
 *  - a 1.8 server (ViaFabricPlus) — a main hand use is a sword block there;
 *  - 1.21.5 and newer — swords block on their own (`BlocksAttacks`);
 *  - anything in between, only while LiquidBounce's own SwordBlock is running and not set to
 *    `OnlyVisual`: it rewrites the use packet of a main hand sword into one those servers accept.
 *
 * With none of that in hand there is nothing to open, and the module says so instead of pretending.
 *
 * ## What it does per click
 *
 *  - [chance] decides whether this click becomes a block hit at all;
 *  - [requireMouseDown] restricts it to the moments the right mouse button is held (physically, or
 *    by an AutoClicker's use button), so it only ever helps an input you are already making;
 *  - the block opens [delay] ticks after the click and lasts [blockTicks] ticks, then it is closed
 *    with the vanilla release action;
 *  - the two actions are always kept apart: at least [releaseCooldown] ticks between closing a
 *    block and opening the next one, and at least [attackGap] ticks between your attack and the
 *    block (one more when the aim has not moved since the attack). A release and a use in one
 *    server tick, or an attack and a use carrying the same rotation, are two actions no input
 *    device produces — and on a 1.8 server the first one is read as "blocking while attacking".
 *
 * ## The two modes
 *
 *  - [Mode.MANUAL] — the block hit on its own: click, block, release. Nothing else is touched.
 *  - [Mode.LAG] — the same, plus a short hold of the outgoing packets right after the block opens
 *    ([lagTicks]). The use that opens the block leaves normally, then the packets that follow (the
 *    release included) are queued through the client's normal `BlinkManager`: on the server the
 *    block stays up for that long. The queue is drained as soon as the window closes and it is
 *    never flushed from here, so a FakeLag/Backtrack holding packets keeps its own.
 */
object ModuleBlockHit : ClientModule("BlockHit", Categories.LIQUIDADDON) {

    enum class Mode(override val tag: String) : Tagged {
        MANUAL("Manual"),
        LAG("Lag")
    }

    /** Nothing scheduled. */
    private const val IDLE = -1

    /** Ticks a block may wait for its gates to clear before it is dropped. */
    private const val GATE_WAIT_MAX = 3

    /** Two rotations closer than this (in degrees) are the same rotation to a server. */
    private const val SAME_ROTATION_DEGREES = 0.05f

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

    /**
     * Ticks between the click and the block. A block can only open on a tick boundary, so 0 and 1
     * both mean "the next tick": the block is up before the next attack either way.
     */
    private val delay by int("Delay", 1, 0..5, "ticks")

    /** How long the block is held, in ticks. Two ticks cover the window around one click. */
    private val blockTicks by int("BlockTicks", 2, 1..10, "ticks")

    /** `Mode = Lag` only: how many ticks the packets after the block opened are held back. */
    private val lagTicks by int("LagTicks", 4, 1..10, "ticks")

    /**
     * Ticks that must pass between **closing** a block and opening the next one.
     *
     * A release and a use in the same tick are two opposite actions inside one server tick: the
     * server applies them in order and sees a player who let go of the shield and used it again in
     * zero time, which is not something a hand (or an input repeat) does. On a 1.8 server it is
     * worse: the sword block is a server-side state, so the pair reads as "blocking while
     * attacking" — the `AutoBlock` check. Two ticks of separation remove the pattern and cost
     * nothing, because a block hit only has to cover the ticks around the hit.
     */
    private val releaseCooldown by int("ReleaseCooldown", 2, 0..6, "ticks")

    /**
     * Ticks that must pass between your **attack** and the block that follows it.
     *
     * An attack packet and a use packet carrying the same rotation inside the same tick is the
     * shape of a script: a hand cannot swing and raise a shield at the same instant, from the same
     * angle, and have the two leave together. One tick is enough to break the coincidence, and the
     * block lands on the hit either way — the damage window of a hit is longer than a tick.
     */
    private val attackGap by int("AttackGap", 1, 0..4, "ticks")

    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

    /** Ticks until the block opens, or [IDLE]. */
    private var startIn = IDLE

    /** Ticks until the block is closed, or [IDLE]. */
    private var releaseIn = IDLE

    /** Ticks the outgoing packets are still held back (`Mode = Lag`), or [IDLE]. */
    private var lagIn = IDLE

    /** True while a block *we* opened is up: only our own block is ever closed. */
    private var owningBlock = false

    /** The hand our block was opened with. */
    private var blockHand: InteractionHand? = null

    /** Set once, so "there is nothing here that can block" is said once and not every click. */
    private var warnedNoBlock = false

    /** The tick the last block of ours was closed, or [IDLE]. */
    private var lastReleaseTick = IDLE

    /** The tick (and rotation) of the last attack, for the same-rotation rule. */
    private var lastAttackTick = IDLE
    private var lastAttackYaw = 0f
    private var lastAttackPitch = 0f

    /**
     * Ticks a block may keep waiting for its gates to clear. A block that opens late is a block that
     * does not cover the hit, so a blocked one is dropped instead of being chased.
     */
    private var gateWait = 0

    private var clicks = 0
    private var blocks = 0
    private var lagged = 0
    private var lastSkip = "idle"

    override val tag: String
        get() = if (clicks == 0) "" else "$blocks/$clicks"

    override fun onEnabled() {
        reset()
        super.onEnabled()

        val me = mc.player ?: return
        val hand = blockingHand(me)
        if (hand == null) {
            chat(
                "§7[BlockHit] §cattivo, ma non c'è niente che blocchi: §7serve uno scudo nella " +
                    "mano secondaria, o una spada su un server 1.8 (o 1.21.5+)"
            )
        } else {
            chat(
                "§7[BlockHit] §aattivo §7- blocca con §f" + me.getItemInHand(hand).hoverName.string +
                    " §7(" + hand.name.lowercase() + ", lato server: non vedi l'animazione)"
            )
        }
    }

    override fun onDisabled() {
        // Never leave a block of ours open: the block has to end with the module.
        closeBlock()
        reset()
        super.onDisabled()
    }

    private fun reset() {
        startIn = IDLE
        releaseIn = IDLE
        lagIn = IDLE
        owningBlock = false
        blockHand = null
        warnedNoBlock = false
        lastReleaseTick = IDLE
        lastAttackTick = IDLE
        gateWait = 0
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

        // Remembered for the same-rotation rule below: the tick the attack left in and the rotation
        // it carried. An attack and a use with the same angles inside one tick is a script's
        // signature, not a player's.
        lastAttackTick = me.tickCount
        lastAttackYaw = me.yRot
        lastAttackPitch = me.xRot

        if (!canOperate(me)) {
            note("skipped ($lastSkip)")
            return@handler
        }
        if (chance <= 0 || Random.nextInt(100) >= chance) {
            note("chance roll failed")
            return@handler
        }
        if (me.isUsingItem) {
            // The client is already using an item: the player is holding right click, or an
            // AutoClicker is. That block is the one we wanted anyway - leave it alone.
            counted()
            note("already using an item, counted")
            return@handler
        }
        if (owningBlock) {
            note("our block is still up")
            return@handler
        }

        // `delay - 1`: the tick of the click is already running, so the first tick that can open a
        // block is the next one, and that is what both 0 and 1 mean.
        startIn = (delay - 1).coerceAtLeast(0)
        note("block hit planned (in $startIn ticks, $blockTicks ticks long)")
    }

    // --- the block ------------------------------------------------------------------------

    /**
     * One tick: open the block when its tick comes, close it when it is over, close the lag window.
     * All of it on `GameTickEvent`, which runs at the head of the tick, so the use packet goes out
     * at the start of a tick and never inside the input handling that would tear it down.
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        // Counted down before anything can return: a lag window that never closes would hold the
        // connection forever.
        if (lagIn != IDLE) {
            lagIn--
            if (lagIn <= 0) {
                lagIn = IDLE
                note("lag window closed")
            }
        }

        val me = mc.player ?: return@handler

        // The release comes first, and it closes the tick to a new block: one action per tick. When
        // the release and the use of the next block would land together, the use waits — otherwise
        // the server gets two opposite actions inside one tick, which on a 1.8 server is exactly the
        // "blocking while attacking" pattern. It also used to overwrite `releaseIn` and leave the
        // old block open forever.
        if (releaseIn != IDLE) {
            releaseIn--
            if (releaseIn <= 0) {
                releaseIn = IDLE
                closeBlock()
            }
        }

        if (startIn != IDLE) {
            if (startIn == 0) {
                val gate = gateReason(me)
                if (gate != null) {
                    // Retried for a few ticks and then dropped: a block that opens late no longer
                    // covers the hit it was planned for, and opening it anyway is only a packet.
                    if (gateWait >= GATE_WAIT_MAX) {
                        startIn = IDLE
                        gateWait = 0
                        note("dropped, $gate")
                    } else {
                        gateWait++
                        note("waiting, $gate")
                    }
                } else {
                    startIn = IDLE
                    gateWait = 0
                    if (openBlock(me)) {
                        counted()
                        releaseIn = blockTicks
                        if (mode == Mode.LAG) {
                            // The use packet that opens the block has already left: the hold starts
                            // now, so what stays behind is the release, which is what keeps the
                            // block up for longer on the server.
                            lagIn = lagTicks
                            lagged++
                            note("blocking, lagging $lagTicks ticks")
                        } else {
                            note("blocking for $blockTicks ticks")
                        }
                    }
                }
            } else {
                startIn--
            }
        }
    }

    /**
     * Why a block may not open on this tick, or `null` when it may.
     *
     * Two rules, both about *keeping the two actions apart*:
     *
     *  - [releaseCooldown] ticks must have passed since the block of ours that was just closed;
     *  - [attackGap] ticks since your own attack — and one more when the rotation has not moved
     *    since it, because an attack and a use carrying the same angles inside the same tick is
     *    what a script looks like. A hand that swings and then raises the shield always has a
     *    different angle between the two.
     */
    private fun gateReason(me: LocalPlayer): String? {
        val tick = me.tickCount

        if (lastReleaseTick != IDLE) {
            val since = tick - lastReleaseTick
            if (since < releaseCooldown) {
                return "released $since ticks ago (cooldown $releaseCooldown)"
            }
        }

        if (lastAttackTick != IDLE) {
            val since = tick - lastAttackTick
            if (since < attackGap) {
                return "your attack was $since ticks ago (gap $attackGap)"
            }
            if (since < attackGap + 1 && sameRotationAsAttack(me)) {
                return "same rotation as your attack"
            }
        }

        return null
    }

    /** Whether the aim is (still) exactly where it was when the last attack left. */
    private fun sameRotationAsAttack(me: LocalPlayer): Boolean =
        abs(Mth.wrapDegrees(me.yRot - lastAttackYaw)) < SAME_ROTATION_DEGREES &&
            abs(me.xRot - lastAttackPitch) < SAME_ROTATION_DEGREES

    /**
     * The hand whose item blocks, or `null` when there is none.
     *
     * First a hand the client itself blocks with ([ItemUseAnimation.BLOCK]: a shield, or a sword on
     * a protocol where swords are animated), then a sword the server blocks with — see the class
     * documentation for the three cases in which a main hand sword is one.
     */
    private fun blockingHand(me: LocalPlayer): InteractionHand? {
        InteractionHand.entries.firstOrNull { hand ->
            val stack = me.getItemInHand(hand)
            stack.useAnimation == ItemUseAnimation.BLOCK &&
                stack.isItemEnabled(me.level().enabledFeatures()) &&
                !me.cooldowns.isOnCooldown(stack)
        }?.let { return it }

        return if (me.mainHandItem.isSword &&
            (isBlocksAttacksExisting || ModuleSwordBlock.running && !ModuleSwordBlock.onlyVisual)
        ) {
            InteractionHand.MAIN_HAND
        } else {
            null
        }
    }

    /**
     * Opens the block on the server: the same [ServerboundUseItemPacket] the right button sends,
     * with the same prediction sequence the right button would ask for.
     *
     * Nothing is started on the client on purpose — no `useItem`, no "using item" state. See the
     * class documentation: the client owning that state is exactly what made the block useless.
     */
    private fun openBlock(me: LocalPlayer): Boolean {
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
            if (!warnedNoBlock) {
                warnedNoBlock = true
                chat(
                    "§7[BlockHit] §cnon c'è niente che possa bloccare: §7metti uno scudo nella " +
                        "mano secondaria, o usa una spada su un server 1.8 (o 1.21.5+)"
                )
            }
            return false
        }

        val sent = runCatching {
            // A silent hotbar swap may have changed the held item without telling the server.
            interaction.ensureHasSentCarriedItem()
            interaction.startPrediction(world) { sequence ->
                ServerboundUseItemPacket(hand, sequence, me.yRot, me.xRot)
            }
        }.isSuccess

        if (!sent) {
            lastSkip = "use packet failed"
            note("the use packet was not sent")
            return false
        }

        owningBlock = true
        blockHand = hand
        note("blocking with " + hand.name.lowercase())
        return true
    }

    /**
     * Closes the block we opened, with the vanilla release action.
     *
     * A block the client is using on its own is left alone: the player is holding right click (or an
     * AutoClicker is), that block is theirs, and vanilla's own release covers the server as well.
     * Every other block is ours and only we can close it — on a 1.8 server with a sword the client
     * has no "using" state at all, so a block that was never closed would stay up forever.
     */
    private fun closeBlock() {
        if (!owningBlock) {
            return
        }
        owningBlock = false
        val hand = blockHand
        blockHand = null

        // The tick of the close, whatever happens next: a new block cannot open before the cooldown,
        // so the release and the next use can never share a server tick.
        lastReleaseTick = mc.player?.tickCount ?: IDLE

        if (mc.player?.isUsingItem == true) {
            note("client is using an item on its own, release left to vanilla")
            return
        }
        if (mc.connection == null) {
            return
        }

        runCatching {
            network.send(
                ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                    BlockPos.ZERO,
                    Direction.DOWN,
                )
            )
        }
        note("block released (" + (hand?.name?.lowercase() ?: "?") + ")")
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
        if (lagIn == IDLE || event.origin != TransferOrigin.OUTGOING) {
            return@handler
        }
        event.action = if (event.packet == null) {
            BlinkManager.Action.PASS
        } else {
            BlinkManager.Action.QUEUE
        }
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
            // A new world has no block of ours in it, and no tick that means anything.
            closeBlock()
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
