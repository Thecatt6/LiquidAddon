package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.modules.doubleclicks.DoubleClickScheduler
import com.liquidaddon.modules.doubleclicks.ExtraClick
import com.liquidaddon.modules.doubleclicks.extraClickAction
import com.liquidaddon.modules.doubleclicks.useExtraAllowed
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.InputHandleEvent
import net.ccbluex.liquidbounce.event.events.ScreenEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleNoMissCooldown
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.HitResult

/**
 * Adds one to three follow-ups to each real click, after the original has been handled: left click
 * (attack key) and, with [rightClick], right click (use key) as well.
 *
 * Both hooks sit on the vanilla input path, `Minecraft.startAttack` and `Minecraft.startUseItem`,
 * rather than on LiquidBounce events: clicks on air and blocks, a rebound key, and clicks queued by
 * another clicker all take that same path.
 *
 * Follow-ups are spaced across input ticks, never recursively copied, and at most one extra is sent
 * per tick. A left click only repeats the vanilla attack when the crosshair is on an entity; a block
 * (or active mining), a miss and a click swallowed by the miss cooldown get the swing animation
 * alone. A right click repeats the vanilla use only when vanilla would accept it (not mining, not
 * already using an item, hands free); otherwise it is dropped, because the right button has no
 * animation to fall back on. Nothing here can guarantee an anti-cheat accepts the extra clicks.
 */
object ModuleDoubleClicks : ClientModule("DoubleClicks", Categories.LIQUIDADDON) {
    private val extraClicks by int("ExtraClicks", 1, 1..DoubleClickScheduler.MAX_EXTRA_CLICKS, "clicks")
    private val delay by int("Delay", 1, 1..5, "ticks")
    private val rightClick by boolean("RightClick", true)
    private val debug by boolean("Debug", false)

    // One queue per button, so each keeps its own kind and count. Only one follow-up leaves per
    // tick, whichever button it belongs to: that is what keeps two interactions out of one tick.
    private val attackScheduler = DoubleClickScheduler()
    private val useScheduler = DoubleClickScheduler()

    private var sendingExtra = false
    private var queuedPlayer: LocalPlayer? = null
    private var queuedWorld: ClientLevel? = null

    override val tag: String
        get() = if (rightClick) "+$extraClicks/+$extraClicks" else "+$extraClicks"

    override fun onEnabled() = reset()

    override fun onDisabled() = reset()

    /** Called by the Minecraft.startAttack RETURN mixin, not by our synthetic follow-up. */
    fun onGameClick() {
        if (!running || sendingExtra) return
        if (!canClick()) {
            reset()
            return
        }

        // A click vanilla swallowed in its miss cooldown did not happen; do not copy it.
        val cooldownBypassed =
            ModuleNoMissCooldown.running && ModuleNoMissCooldown.removeAttackCooldown
        if (mc.missTime > 0 && !cooldownBypassed) return

        val me = mc.player ?: return
        val world = mc.level ?: return
        val gameMode = mc.gameMode ?: return
        val hit = mc.hitResult ?: return
        bind(me, world)

        val kind = if (hit.type == HitResult.Type.BLOCK || gameMode.isDestroying) {
            ExtraClick.SWING_ONLY
        } else {
            ExtraClick.ATTACK
        }
        attackScheduler.recordOriginal(extraClicks, kind)
    }

    /** Called by the Minecraft.startUseItem RETURN mixin, not by our synthetic follow-up. */
    fun onGameUse() {
        if (!running || !rightClick || sendingExtra) return
        if (!canClick()) {
            reset()
            return
        }

        val me = mc.player ?: return
        val world = mc.level ?: return
        val gameMode = mc.gameMode ?: return
        // Vanilla returns before touching anything in these two cases, so there was no use to copy.
        if (gameMode.isDestroying || mc.hitResult == null) return
        bind(me, world)

        useScheduler.recordOriginal(extraClicks, ExtraClick.USE)
    }

    @Suppress("unused")
    private val inputHandler = handler<InputHandleEvent> {
        if (!canClick() || queuedPlayer !== mc.player || queuedWorld !== mc.level) {
            reset()
            return@handler
        }

        // InputHandleEvent is at the end of vanilla's handleKeybinds. A newly recorded original
        // consumes this tick, so the first extra can only run on a later tick. One follow-up per
        // tick in total: the left queue is drained first, so a right click can wait a tick longer
        // when three left follow-ups are still pending.
        val planned = attackScheduler.takeNext(delay) ?: useScheduler.takeNext(delay) ?: return@handler
        sendExtra(planned)
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        // handleKeybinds is not called while a screen is open; do not leave old clicks waiting.
        if (queuedPlayer != null &&
            (!canClick() || queuedPlayer !== mc.player || queuedWorld !== mc.level)
        ) {
            reset()
        }
    }

    @Suppress("unused")
    private val screenHandler = handler<ScreenEvent> { event ->
        if (event.screen != null) reset()
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldChangeEvent> { reset() }

    private fun bind(me: LocalPlayer, world: ClientLevel) {
        if (queuedPlayer !== me || queuedWorld !== world) {
            reset()
            queuedPlayer = me
            queuedWorld = world
        }
    }

    private fun canClick(): Boolean {
        val me = mc.player ?: return false
        // The same conditions vanilla needs before it looks at the attack or use key.
        return mc.level != null && mc.gameMode != null && mc.connection != null &&
            mc.hitResult != null && mc.gui.screen() == null && mc.gui.overlay() == null &&
            mc.mouseHandler.isMouseGrabbed && mc.isWindowActive &&
            me.isAlive && !me.isSpectator && !me.isUsingItem
    }

    private fun sendExtra(planned: ExtraClick) {
        val me = mc.player ?: return
        if (planned == ExtraClick.USE) {
            sendUseExtra(me)
            return
        }

        val gameMode = mc.gameMode ?: return
        val hit = mc.hitResult ?: return
        val noMissCooldown = ModuleNoMissCooldown.running
        if (hit.type == HitResult.Type.MISS && noMissCooldown && ModuleNoMissCooldown.cancelAttackOnMiss) {
            return
        }

        val action = extraClickAction(
            planned = planned,
            overEntity = hit.type == HitResult.Type.ENTITY,
            mining = gameMode.isDestroying,
            missCooldown = mc.missTime > 0 && !(noMissCooldown && ModuleNoMissCooldown.removeAttackCooldown),
        )
        if (action == ExtraClick.SWING_ONLY) {
            // This sends the normal punch animation, not a START/STOP/ABORT_DESTROY_BLOCK packet.
            SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)
        } else {
            sendingExtra = true
            try {
                // Resolve the current crosshair, reach, item and attack through Minecraft itself.
                mc.startAttack()
            } finally {
                sendingExtra = false
            }
        }

        if (debug) {
            val type = if (action == ExtraClick.ATTACK) "attacco" else "swing"
            me.sendSystemMessage(
                Component.literal("§8[DoubleClicks] §7extra $type, in coda ${attackScheduler.pendingCount}")
            )
        }
    }

    /** Second use of the right button: the same path `handleKeybinds` takes for the use key. */
    private fun sendUseExtra(me: LocalPlayer) {
        val gameMode = mc.gameMode ?: return
        val allowed = useExtraAllowed(
            planned = ExtraClick.USE,
            handsBusy = me.isHandsBusy,
            usingItem = me.isUsingItem,
            mining = gameMode.isDestroying,
        )
        if (!allowed || mc.hitResult == null) return

        sendingExtra = true
        try {
            mc.startUseItem()
        } finally {
            sendingExtra = false
        }

        if (debug) {
            me.sendSystemMessage(
                Component.literal("§8[DoubleClicks] §7extra uso, in coda ${useScheduler.pendingCount}")
            )
        }
    }

    private fun reset() {
        attackScheduler.clear()
        useScheduler.clear()
        queuedPlayer = null
        queuedWorld = null
    }
}
