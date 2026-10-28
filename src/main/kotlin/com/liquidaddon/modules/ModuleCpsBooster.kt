package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import kotlin.random.Random

/**
 * CpsBooster — one left click of the player's becomes up to [multiplier] attacks.
 *
 * The player's own click lands exactly as it always does. The module then owes the target the rest,
 * and pays it over the following ticks with [spread] ticks of random spacing between one attack and
 * the next. It never multiplies an attack it made itself, so a click is turned into [multiplier]
 * attacks and not into a loop.
 *
 * **What this can and cannot buy.** A target cannot be damaged more than about twice a second: for
 * the ten ticks after a hit it is invulnerable, and an attack that arrives inside that window is
 * answered by the server with nothing at all — no damage, no knockback, not even a sound. So no
 * module can triple the damage done to a single target, and a multiplier that ignores the window is
 * only buying the one thing a PvP anticheat is built to see: a click rate no hand makes, carrying
 * hits that do not count.
 *
 * What it buys instead is the other half of the rate. Where a hand has to *hit* the moment the
 * window opens, [onlyWhenItLands] holds the follow-up until the server would take it and lets it go
 * then — the window is never missed again — and in a fight against several enemies, each with a
 * window of their own, the difference between a click that is spent and a click that is wasted is
 * the difference the extra attacks make.
 *
 * The rate is capped at [maxCps] counted over the last second, and every interval is drawn at random
 * inside [spread]: a fixed interval between attacks is the pattern an autoclicker is caught on, not
 * the rate itself.
 *
 * **With the other modules.** `AttackEntityEvent` does not say who attacked, so a click made by the
 * TriggerBot or by a KillAura is multiplied too — they go through the same door as a hand. That is
 * bounded by [maxCps], and it is the same hits it would have made anyway, only followed up. An
 * attack cancelled by another module (HitSelect drops them on purpose) is not sent: it waits in the
 * queue and is tried again, until its deadline.
 */
object ModuleCpsBooster : ClientModule("CpsBooster", Categories.LIQUIDADDON) {

    /** How many attacks one click of the player's is turned into. 1 leaves the clicking alone. */
    private val multiplier by int("Multiplier", 2, 1..3)

    /**
     * Ticks between one attack and the next, drawn at random in the range.
     *
     * The default sits just past the ten ticks a target spends invulnerable after a hit, which is
     * where a follow-up actually lands.
     */
    private val spread by intRange("Spread", 10..13, 1..20, "ticks")

    /** A hard ceiling on the attacks this module produces, counted over the last second. */
    private val maxCps by int("MaxCps", 12, 2..20)

    /**
     * Hold a follow-up until the server would accept it: the target's invulnerability window open,
     * the weapon charged, the target still in reach.
     *
     * Off, the follow-ups go out on schedule, and most of them are answered with nothing.
     */
    private val onlyWhenItLands by boolean("OnlyWhenItLands", true)

    /** Minimum attack strength (the 1.9+ weapon cooldown) before a follow-up is sent. */
    private val minCooldown by float("MinCooldown", 0.9f, 0f..1f)

    /** How far a follow-up may reach. The server measures its own distance, and its own is 3.0. */
    private val reach by float("Reach", 3.0f, 2.0f..4.0f)

    private val swing by enumChoice("Swing", SwingMode.DO_NOT_HIDE)

    private val debug by boolean("Debug", false)

    /** An attack the player has already made and the module still owes. */
    private class Owed(val entity: Entity, val at: Int, val deadline: Int)

    /** How long a follow-up may wait for its window before it is given up on. */
    private const val MAX_WAIT = 20

    /** How many follow-ups may be owed at once: past this, the oldest is let go. */
    private const val MAX_OWED = 8

    /** Ticks in a second, for the rate cap. */
    private const val SECOND = 20

    private val owed = ArrayDeque<Owed>()
    private val sent = ArrayDeque<Int>()
    private var tick = 0

    /** Set while the module is sending one of its own attacks, so [attackHandler] ignores it. */
    private var paying = false

    private var extra = 0
    private var dropped = 0

    override val tag: String
        get() = if (multiplier <= 1) "" else multiplier.toString() + "x"

    override fun onEnabled() {
        forget()
        super.onEnabled()
    }

    override fun onDisabled() {
        forget()
        super.onDisabled()
    }

    private fun forget() {
        owed.clear()
        sent.clear()
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        // The player's click is the only thing this multiplies: a follow-up of ours fires this event
        // again on its way out, and multiplying that would never stop.
        if (paying) {
            return@handler
        }
        val repeats = multiplier - 1
        if (repeats <= 0) {
            return@handler
        }
        var at = tick
        repeat(repeats) {
            at += nextIn(spread)
            owed.addLast(Owed(event.entity, at, at + MAX_WAIT))
        }
        while (owed.size > MAX_OWED) {
            owed.removeFirst()
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        tick++
        while (sent.firstOrNull() != null && tick - sent.first() >= SECOND) {
            sent.removeFirst()
        }
        val me = mc.player ?: return@handler
        if (me.isSpectator || mc.gui.screen() != null) {
            return@handler
        }
        val iterator = owed.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (!next.entity.shouldBeAttacked() || next.deadline < tick) {
                // Died, logged out, or waited too long for a window that never opened.
                iterator.remove()
                dropped++
                continue
            }
            if (next.at > tick) {
                continue
            }
            if (sent.size >= maxCps) {
                continue
            }
            if (me.distanceTo(next.entity) > reach) {
                // Out of reach: attacking from here would be an attack the server measures as
                // impossible, which is its own flag and not a missed click.
                iterator.remove()
                dropped++
                continue
            }
            if (onlyWhenItLands && !canLand(me, next.entity)) {
                continue
            }
            if (pay(me, next.entity)) {
                iterator.remove()
            }
        }
    }

    /** True when the server would take this attack: the window is open and the weapon is charged. */
    private fun canLand(me: LocalPlayer, entity: Entity): Boolean {
        if (entity is LivingEntity && entity.hurtTime > 0) {
            return false
        }
        return me.getAttackStrengthScale(0.5f) >= minCooldown
    }

    /**
     * Sends one follow-up through LiquidBounce's own attack, the one the KillAura and the TriggerBot
     * use: it consumes the weapon cooldown and swings the hand like any other attack does.
     */
    private fun pay(me: LocalPlayer, entity: Entity): Boolean {
        paying = true
        val went = try {
            attackEntity(entity, swing)
        } finally {
            paying = false
        }
        sent.addLast(tick)
        // Cancelled (HitSelect drops attacks on purpose): the follow-up stays in the queue and is
        // tried again next tick, until its deadline.
        if (went) {
            extra++
            if (debug && extra % 10 == 0) {
                me.sendSystemMessage(Component.literal("§8[CpsBooster D] §7colpi extra $extra, " +
                    "scartati $dropped, in coda ${owed.size}"))
            }
        }
        return went
    }

    /** A random number of ticks inside [range]. */
    private fun nextIn(range: IntRange): Int =
        if (range.last <= range.first) range.first else Random.nextInt(range.first, range.last + 1)
}
