package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.modules.doubleclicks.DoubleClickScheduler
import com.liquidaddon.modules.doubleclicks.ExtraClick
import com.liquidaddon.modules.doubleclicks.extraClickAction
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
 * Adds one to three follow-ups to each actual attack-key click, after the original has been handled.
 * The hook is on Minecraft.startAttack rather than AttackEntityEvent: clicks on air and blocks, a
 * rebound attack key, and clicks queued by another clicker all take the same vanilla input path.
 *
 * Follow-ups are spaced across input ticks, never recursively copied. Only a click whose current
 * crosshair is an entity repeats the vanilla attack path. A block (or active mining), a miss and a
 * click swallowed by the miss cooldown get the swing animation alone: calling startAttack there
 * would start destroy actions, set a new miss cooldown, or do nothing at all. This keeps the module
 * from adding block-dig packets of its own, but it cannot guarantee an anti-cheat accepts the clicks.
 */
object ModuleDoubleClicks : ClientModule("DoubleClicks", Categories.LIQUIDADDON) {
    private val extraClicks by int("ExtraClicks", 1, 1..DoubleClickScheduler.MAX_EXTRA_CLICKS, "clicks")
    private val delay by int("Delay", 1, 1..5, "ticks")
    private val debug by boolean("Debug", false)

    private val scheduler = DoubleClickScheduler()
    private var sendingExtra = false
    private var queuedPlayer: LocalPlayer? = null
    private var queuedWorld: ClientLevel? = null

    override val tag: String
        get() = "+$extraClicks"

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
        if (queuedPlayer !== me || queuedWorld !== world) {
            reset()
            queuedPlayer = me
            queuedWorld = world
        }

        val kind = if (hit.type == HitResult.Type.BLOCK || gameMode.isDestroying) {
            ExtraClick.SWING_ONLY
        } else {
            ExtraClick.ATTACK
        }
        scheduler.recordOriginal(extraClicks, kind)
    }

    @Suppress("unused")
    private val inputHandler = handler<InputHandleEvent> {
        if (!canClick() || queuedPlayer !== mc.player || queuedWorld !== mc.level) {
            reset()
            return@handler
        }

        // InputHandleEvent is at the end of vanilla's handleKeybinds. A newly recorded original
        // consumes this tick, so the first extra can only run on a later tick.
        val planned = scheduler.takeNext(delay) ?: return@handler
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

    private fun canClick(): Boolean {
        val me = mc.player ?: return false
        // The same conditions vanilla needs before it looks at the attack key.
        return mc.level != null && mc.gameMode != null && mc.connection != null &&
            mc.hitResult != null && mc.gui.screen() == null && mc.gui.overlay() == null &&
            mc.mouseHandler.isMouseGrabbed && mc.isWindowActive &&
            me.isAlive && !me.isSpectator && !me.isUsingItem
    }

    private fun sendExtra(planned: ExtraClick) {
        val me = mc.player ?: return
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
                Component.literal("§8[DoubleClicks] §7extra $type, in coda ${scheduler.pendingCount}")
            )
        }
    }

    private fun reset() {
        scheduler.clear()
        queuedPlayer = null
        queuedWorld = null
    }
}
