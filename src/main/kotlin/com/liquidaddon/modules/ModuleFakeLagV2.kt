package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.BlinkPacketEvent
import net.ccbluex.liquidbounce.event.events.DisconnectEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.blink.PacketSnapshot
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleFakeLag
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.combat.getEntitiesBoxInRange
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.box
import net.ccbluex.liquidbounce.utils.entity.getBoundingBoxAt
import net.ccbluex.liquidbounce.utils.entity.ping
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.item.isConsumable
import net.ccbluex.liquidbounce.utils.kotlin.matchesAny
import net.ccbluex.liquidbounce.utils.network.isLocalPlayerDamage
import net.ccbluex.liquidbounce.utils.network.isLocalPlayerVelocity
import net.ccbluex.liquidbounce.utils.network.position
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.function.Predicate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * FakeLag V2 — holds your outgoing packets back like LiquidBounce's own FakeLag, but as a real
 * **stream delay** instead of an all-or-nothing burst, and with three ways of choosing how long.
 *
 * The built-in module keeps packets until a timer expires and then lets the whole queue out at
 * once, so the server sees your position freeze and jump. V2 releases the queue in **whole client
 * ticks**, once per tick: everything one of your ticks produced arrives together, one of your
 * ticks per server tick, exactly like a connection that is simply `Delay` milliseconds slower.
 *
 * That tick alignment is not cosmetics, it is the whole safety argument. A client tick goes out as
 * [dig/swing/interact ..., move]; every "post packet" check on every anticheat (Vulcan's
 * *BadPackets (Post BlockDig)* among them) knows that order and flags the moment a dig or an
 * interact of a *newer* tick lands behind the move packet of an older one, which is what happens as
 * soon as two of your ticks are delivered inside one server tick. Releasing per frame against a
 * millisecond cutoff — or emptying the queue in a burst — produces exactly that. Releasing whole
 * ticks, at most one tick of hold less per tick, cannot: the groups stay intact and stay spaced.
 *
 * Three modes ([Mode]):
 *  - **Latency** — a constant amount of delay added to your packets. Nothing is analysed, the
 *    connection is just `Delay` milliseconds slower than it is;
 *  - **Dynamic** — the delay follows the fight. A threat score (how close the nearest enemy is to
 *    where the server thinks you are, whether they are looking at you, whether you were just hit,
 *    whether you are hitting) is scaled onto `0..Delay` and *smoothed* over [adaptTime], so the
 *    connection speed drifts instead of flickering on and off. It also refuses to lag when lagging
 *    would be a disadvantage: the frozen position is closer to the enemy than the real one, or an
 *    enemy hitbox already intersects it;
 *  - **Repel** — treats the held ticks as a set of candidates and picks the one that keeps the
 *    opponent furthest away. The position each held tick carries is scored by how far the enemies
 *    (led by one tick of their own velocity) would be from it, minus a penalty for how long
 *    freezing there takes; the queue is then released up to the winner. The result is that you stay
 *    parked on the position that is hardest to reach, and only move forward when moving forward
 *    pushes them further away.
 *
 * [transmissionOffset] is the instability on top of whatever the mode decided: a damped random walk
 * plus the occasional drop-out or catch-up burst, both scaled by the setting. Zero gives a
 * metronomic delay, high values give a connection that comes and goes — which is a disadvantage on
 * a good connection and an advantage on an already bad one, because it hides in the noise. The
 * wobble is applied in whole ticks as well, so an unstable connection here still never merges two
 * of your ticks into one server tick.
 *
 * Why it is safer than the built-in one:
 *  - tick-aligned release, and the hold is never reduced by more than one tick per tick, so the
 *    queue drains the way a recovering connection does instead of in a burst;
 *  - it never answers a [BlinkPacketEvent] with `FLUSH`, so it cannot empty the packets that
 *    Backtrack, PingSpoof or Criticals are holding on the *other* side of the same queue;
 *  - the release order is strictly FIFO, so a packet can never overtake an older one;
 *  - the incoming side is really watched. The built-in module returns before its own
 *    "flush on knockback / damage / explosion" branches, so they never run; here they do, and a
 *    server teleport, respawn, death or disconnect drops the positions that have just become stale
 *    instead of sending them;
 *  - [MAX_HOLD_TICKS] and [MAX_QUEUE] cap the silence whatever a mode asks for, and your own
 *    interactions ([flushOn]) go out immediately, *with* the held ticks in front of them, so the
 *    thing that is never left behind is your hit.
 *
 * Everything runs on the client's shared [BlinkManager] queue, so the Blink ESP, the position line
 * and every other blink-aware module keep working exactly as they do with the built-in FakeLag.
 */
@Suppress("MagicNumber", "TooManyFunctions")
object ModuleFakeLagV2 : ClientModule("FakeLagV2", Categories.LIQUIDADDON) {

    /**
     * Nothing is ever held longer than this many ticks, whatever a mode asks for. A longer silence
     * is read as a timeout by the server, and the positions that arrive afterwards are read as a
     * teleport. 40 ticks = 2 seconds.
     */
    private const val MAX_HOLD_TICKS = 40

    /** How many ticks of hold the module may gain in one tick. Losing is capped at one, below. */
    private const val RAMP_UP_TICKS = 4

    /** Tick starts remembered, enough for the longest hold plus the longest Repel window. */
    private const val MAX_TICK_HISTORY = 128

    /** Safety net on the queue length, in packets. Two seconds of hold stay well below it. */
    private const val MAX_QUEUE = 120

    /** Repel: how much further away, in blocks, a position has to be to be worth freezing on. */
    private const val REPEL_MIN_GAIN = 0.15

    /** Repel: how many blocks of distance one full window of freezing costs. */
    private const val REPEL_AGE_PENALTY = 0.4

    /** One game tick in milliseconds; the unit the delay is finally expressed in. */
    private const val TICK_MS = 50f

    /** An enemy looking at us from within this many degrees counts as "facing us". */
    private const val LOOK_ANGLE = 60f

    enum class Mode(override val tag: String) : Tagged {
        /** Adds a constant amount of delay to your packets. */
        LATENCY("Latency"),

        /** Dynamically adjusts your connection speed in order to give you advantages in combat. */
        DYNAMIC("Dynamic"),

        /** Tunes FakeLag with the goal of keeping your opponent as far away from you as possible. */
        REPEL("Repel")
    }

    /**
     * Packets that make the module release everything it is holding, in order, right away, so your
     * own interactions and the server's answers to them are never the things left behind. The first
     * three are outgoing, the last two incoming - and those are the ones the built-in FakeLag never
     * manages to fire, because it returns before reaching them.
     */
    private enum class FlushOn(
        override val tag: String,
        private val testPacket: Predicate<Packet<*>?>
    ) : Tagged, Predicate<Packet<*>?> by testPacket {
        ENTITY_INTERACT("EntityInteract", {
            it is ServerboundInteractPacket || it is ServerboundAttackPacket ||
                it is ServerboundSpectatorActionPacket || it is ServerboundPunchPacket
        }),
        BLOCK_INTERACT("BlockInteract", {
            it is ServerboundUseItemOnPacket || it is ServerboundSignUpdatePacket
        }),
        ACTION("Action", {
            it is ServerboundPlayerActionPacket
        }),
        HURT("Hurt", {
            it.isLocalPlayerDamage()
        }),
        KNOCKBACK("Knockback", {
            it.isLocalPlayerVelocity()
        })
    }

    // --- the requested settings -------------------------------------------------------------

    private val modeValue = enumChoice("Mode", Mode.DYNAMIC)
    private val mode by modeValue

    /**
     * The amount of delay, in milliseconds, to wait for before sending any given packet to the
     * server. If you regularly have 50 ms to a server and this is set to 100 ms, then you are
     * effectively playing on 150 ms. The value is rounded to whole ticks on purpose: a partial tick
     * of delay is the one thing that merges two of your ticks inside one server tick. In
     * [Mode.REPEL] this is not a fixed delay but the window of held ticks the optimizer may choose
     * inside.
     */
    private val delayValue = int("Delay", 200, 0..2000, "ms")
    private val delay by delayValue

    /**
     * Higher values may make your connection more unstable, which may be a disadvantage or an
     * advantage based on your original connection to the server.
     */
    private val transmissionOffsetValue = int("TransmissionOffset", 25, 0..1000, "ms")
    private val transmissionOffset by transmissionOffsetValue

    // --- what the modes look at -------------------------------------------------------------

    /** Inside [start] the threat is maximal, out to [endInclusive] enemies are still considered. */
    private val rangeValue = floatRange("Range", 2.5f..6f, 1f..12f, "blocks")
    private val range by rangeValue

    private val flushOn by multiEnumChoice("FlushOn", FlushOn.ENTITY_INTERACT, FlushOn.BLOCK_INTERACT)

    /** How fast [Mode.DYNAMIC] is allowed to change the connection speed, in milliseconds. */
    private val adaptTimeValue = int("AdaptTime", 250, 50..1000, "ms")
    private val adaptTime by adaptTimeValue

    /**
     * Two modules cannot own one packet queue: the built-in FakeLag answers `FLUSH` on every
     * packet it does not want to hold, which empties whatever this one is holding. Turning it off
     * is the only sane combination, so this does it for you.
     */
    private val supersedeFakeLag by boolean("SupersedeFakeLag", true)

    private val debug by boolean("Debug", false)

    // --- state ------------------------------------------------------------------------------

    /** Start timestamp of each client tick, oldest first; the grid the release is aligned to. */
    private val tickStarts = ArrayDeque<Long>(MAX_TICK_HISTORY)

    /** The delay the current mode asks for, in milliseconds, before the instability. */
    private var holdMs = 0f

    /** The delay actually in force, in whole ticks. Moves up fast and down one tick at a time. */
    private var heldTicks = 0

    /** Damped random walk around zero, bounded by [transmissionOffset]. */
    private var jitterMs = 0f

    /** A drop-out or a catch-up burst, i.e. [jitterMs] with a memory of a few hundred ms. */
    private var spikeMs = 0f
    private var spikeUntil = 0L

    /** Enemies found on the last tick, around both the real and the frozen position. */
    private var enemies: List<Entity> = emptyList()

    private var lastDamageAt = 0L
    private var lastAttackAt = 0L
    private var lastHurtTime = 0

    /** True while this module is the one holding the queue, so only it releases it. */
    private var holding = false
    private var queued = 0
    private var lastDebugAt = 0L

    /** How long the oldest packet we are holding has been held, which is the delay in practice. */
    private val heldMs: Long
        get() = oldestAge(System.currentTimeMillis())

    override val tag: String
        get() {
            val held = heldMs
            return if (held > 0L) "${mode.tag} ${held}ms" else mode.tag
        }

    // --- decisions and release, once per tick -----------------------------------------------

    @Suppress("unused", "ComplexMethod")
    private val tickHandler = handler<GameTickEvent> {
        val now = System.currentTimeMillis()

        tickStarts.addLast(now)
        while (tickStarts.size > MAX_TICK_HISTORY) {
            tickStarts.removeFirst()
        }

        // hurtTime counts down from 10, so a rise is a new hit: no packet handler needed.
        val hurtTime = player.hurtTime
        if (hurtTime > lastHurtTime) {
            lastDamageAt = now
        }
        lastHurtTime = hurtTime

        updateTransmission(now)

        val serverPos = serverPosition()

        when (mode) {
            Mode.LATENCY -> {
                enemies = emptyList()
                holdMs = delay.toFloat()
            }

            Mode.DYNAMIC -> {
                val found = scanEnemies(serverPos)
                enemies = found
                holdMs = smooth(dynamicHold(now, serverPos, found))
            }

            Mode.REPEL -> {
                val found = scanEnemies(serverPos)
                enemies = found
            }
        }

        val desired = when {
            !shouldHold() -> 0
            mode == Mode.REPEL -> chooseRepelHold(serverPos, enemies)
            else -> ticksFor(holdMs + jitterMs + spikeMs)
        }

        // Up fast, down one tick per tick: losing two ticks at once would deliver two of our ticks
        // inside one server tick, which is the pattern every "post packet" check is looking for.
        heldTicks = when {
            desired > heldTicks -> min(desired, heldTicks + RAMP_UP_TICKS)
            desired < heldTicks -> heldTicks - 1
            else -> heldTicks
        }.coerceIn(0, MAX_HOLD_TICKS)

        release()
        debugLine(now, serverPos)
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> {
        lastAttackAt = System.currentTimeMillis()
    }

    // --- what goes into the queue -----------------------------------------------------------

    @Suppress("unused", "ComplexMethod")
    private val blinkHandler = handler<BlinkPacketEvent> { event ->
        val packet = event.packet

        // BlinkManager asks "should I flush?" with a null packet, once per frame and once per tick.
        // We release on our own schedule and never answer FLUSH: that answer would empty the
        // packets other modules are holding on the same queue.
        if (packet == null) {
            event.action = BlinkManager.Action.PASS
            return@handler
        }

        if (event.origin == TransferOrigin.INCOMING) {
            when (packet) {
                // The server moved us, killed us or dropped us: every held position is stale.
                is ClientboundPlayerPositionPacket,
                is ClientboundRespawnPacket -> dropHeldPositions()

                is ClientboundDisconnectPacket -> clearHeld()

                is ClientboundSetHealthPacket -> if (packet.health <= 0f) dropHeldPositions()
            }

            if (flushOn.matchesAny(packet)) {
                releaseAll()
            }

            event.action = BlinkManager.Action.PASS
            return@handler
        }

        if (!shouldHold()) {
            event.action = BlinkManager.Action.PASS
            return@handler
        }

        // Your own interactions go out at once, with everything held in front of them: a hit is
        // never the packet that gets left behind. This is the one burst the module allows - one
        // merged server tick per interaction is the price of an instant hit, and it is the same
        // trade the built-in FakeLag makes. Moving and mining never burst.
        if (flushOn.matchesAny(packet)) {
            releaseAll()
            event.action = BlinkManager.Action.PASS
            return@handler
        }

        holding = true
        queued++
        event.action = BlinkManager.Action.QUEUE
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { event ->
        if (event.world == null) {
            resetState()
        }
    }

    @Suppress("unused")
    private val disconnectHandler = handler<DisconnectEvent> {
        clearHeld()
        resetState()
    }

    override fun onEnabled() {
        resetState()

        if (!supersedeFakeLag || !ModuleFakeLag.enabled) {
            return
        }

        ModuleFakeLag.enabled = false
        notification(
            "FakeLagV2",
            "Turned off LiquidBounce's FakeLag: two modules cannot share one packet queue.",
            NotificationEvent.Severity.INFO
        )
    }

    override fun onDisabled() {
        // Never strand packets in the queue we share with the rest of the client.
        runCatching { BlinkManager.flush(TransferOrigin.OUTGOING) }
        resetState()
    }

    // --- the modes --------------------------------------------------------------------------

    /**
     * The threat of the current fight, scaled onto `0..Delay`.
     *
     * Distance is measured to where the *server* thinks we are, because that is the position the
     * enemy is aiming at. Two checks then decide whether lagging is an advantage at all: if the
     * frozen position is closer to the enemy than the real one, holding it pins us next to them,
     * and if an enemy hitbox already intersects it, the server will push us out of it.
     */
    private fun dynamicHold(now: Long, serverPos: Vec3, found: List<Entity>): Float {
        if (found.isEmpty()) {
            return 0f
        }

        val inner = range.start.toDouble()
        val outer = max(range.endInclusive.toDouble(), inner + 0.5)
        val serverDistance = sqrt(found.minOf { it.squaredBoxedDistanceTo(serverPos) })
        val closeness = (1.0 - (serverDistance - inner) / (outer - inner)).coerceIn(0.0, 1.0).toFloat()
        val facing = found.maxOf { lookingAtUs(it, serverPos) }
        val hurt = decay(now - lastDamageAt, 1500L)
        val attacking = decay(now - lastAttackAt, 1000L)

        var threat = 0.45f * closeness + 0.25f * facing + 0.20f * hurt + 0.10f * attacking

        val clientDistance = sqrt(found.minOf { it.squaredBoxedDistanceTo(player.position()) })
        if (serverDistance < clientDistance) {
            threat *= 0.35f
        }

        // getBoundingBoxAt, not dimensions.makeBoundingBox: Entity.dimensions is private and
        // LiquidBounce's access widener does not propagate to add-ons.
        val frozenBox = player.getBoundingBoxAt(serverPos)
        if (found.any { it.box.intersects(frozenBox) }) {
            threat = 0f
        }

        return threat.coerceIn(0f, 1f) * delay
    }

    /** How much of [enemy]'s view is on the position the server believes we are at, 0..1. */
    private fun lookingAtUs(enemy: Entity, serverPos: Vec3): Float {
        val chest = serverPos.add(0.0, (player.bbHeight * 0.6).toDouble(), 0.0)
        val angle = enemy.rotation.directionAngleTo(Rotation.lookingAt(chest, enemy.eyePosition))
        return (1f - angle / LOOK_ANGLE).coerceIn(0f, 1f)
    }

    /**
     * Picks how many ticks to keep held: the freeze that keeps the opponents furthest away.
     *
     * Holding `h` ticks means the last tick the server has heard of is `current - 1 - h`, so the
     * position it believes we are at is the one that tick carried. The baseline is the longest
     * freeze [delay] allows; every smaller hold is a candidate to advance to, and it wins only if
     * the enemies would be [REPEL_MIN_GAIN] blocks further from its position — the age penalty makes
     * a candidate pay for how long freezing there takes, so the choice drifts forward instead of
     * parking forever.
     *
     * @return the number of ticks to keep held
     */
    private fun chooseRepelHold(serverPos: Vec3, found: List<Entity>): Int {
        val window = ticksFor(delay.toFloat())
        if (found.isEmpty() || window <= 0 || tickStarts.size < 2) {
            return 0
        }

        // Where the fight will be when the held positions land: one tick of lead per enemy.
        val predicted = found.map { it.position().add(it.deltaMovement) }

        // The position each held tick carried, oldest tick first.
        val positionsByTick = LinkedHashMap<Int, Vec3>()
        for (snapshot in outgoingSnapshots()) {
            val position = positionOf(snapshot) ?: continue
            val tick = tickOf(snapshot.timestamp) ?: continue
            positionsByTick[tick] = position
        }

        val current = tickStarts.size - 1
        val baselinePos = positionAtOrBefore(positionsByTick, current - 1 - window) ?: serverPos
        var bestScore = repelScore(baselinePos, predicted, window, window)
        var bestHold = window

        for (hold in 0 until window) {
            val position = positionsByTick[current - 1 - hold] ?: continue
            val score = repelScore(position, predicted, hold, window)
            if (score > bestScore + REPEL_MIN_GAIN) {
                bestScore = score
                bestHold = hold
            }
        }

        return bestHold
    }

    /** Distance to the nearest opponent, minus what freezing for [ageTicks] costs. */
    private fun repelScore(candidate: Vec3, predicted: List<Vec3>, ageTicks: Int, window: Int): Double {
        val distance = predicted.minOf { it.distanceTo(candidate) }
        val age = (ageTicks.toDouble() / max(window.toDouble(), 1.0)).coerceIn(0.0, 1.0)
        return distance - REPEL_AGE_PENALTY * age
    }

    // --- transmission offset ----------------------------------------------------------------

    /**
     * The instability on top of the delay: a random walk that is pulled back to zero, so it wobbles
     * instead of drifting, plus the occasional drop-out (hold longer) or catch-up burst (release
     * sooner). Both are scaled by [transmissionOffset]; at zero the delay is metronomic. The wobble
     * is applied to the delay in milliseconds and only survives into the release as whole ticks,
     * which is what keeps an unstable connection here from ever merging two ticks.
     */
    private fun updateTransmission(now: Long) {
        val offset = transmissionOffset.toFloat()
        if (offset <= 0f) {
            jitterMs = 0f
            spikeMs = 0f
            return
        }

        jitterMs += (Random.nextFloat() - 0.5f) * offset * 0.6f
        jitterMs -= jitterMs * 0.15f
        jitterMs = jitterMs.coerceIn(-offset, offset)

        if (now >= spikeUntil) {
            spikeMs = 0f

            if (Random.nextFloat() < offset / 5000f) {
                spikeUntil = now + 100L + Random.nextLong(400L)
                spikeMs = if (Random.nextBoolean()) {
                    offset * (0.6f + Random.nextFloat())
                } else {
                    -offset * (0.5f + Random.nextFloat() * 0.5f)
                }
            }
        }
    }

    /** Milliseconds of delay to whole ticks, which is the only granularity the server can see. */
    private fun ticksFor(ms: Float): Int = (ms / TICK_MS).roundToInt()

    /** Moves [holdMs] a step towards [target], reaching it in about [adaptTime] milliseconds. */
    private fun smooth(target: Float): Float {
        val factor = (TICK_MS / adaptTime.coerceAtLeast(1)).coerceIn(0.05f, 1f)
        return holdMs + (target - holdMs) * factor
    }

    private fun decay(ageMs: Long, window: Long): Float =
        if (ageMs >= window) 0f else 1f - ageMs.toFloat() / window

    // --- release ----------------------------------------------------------------------------

    /**
     * Lets out every tick that is due, which is every tick that started before
     * `current - heldTicks`. Because the cutoff is a tick start, a tick's packets always leave
     * together and two ticks never share a server tick.
     */
    private fun release() {
        if (outgoingCount() == 0) {
            holding = false
            return
        }

        // The queue is not ours: another module is holding it on purpose, so we do not touch it.
        if (!holding) {
            return
        }

        val index = tickStarts.size - 1 - heldTicks
        if (index >= 0) {
            flushBefore(tickStarts[index])
        }

        trimQueue()
    }

    // --- guards -----------------------------------------------------------------------------

    /**
     * Whether packets may be held at all right now. Everything here is a situation in which a
     * delayed position is only a disadvantage: we are dead, swimming, gliding, eating, or sitting
     * in a menu where nothing is fighting us.
     */
    private fun shouldHold(): Boolean {
        if (player.isDeadOrDying || player.isInWater || player.isInLava || player.isFallFlying) {
            return false
        }

        if (mc.gui.screen() != null) {
            return false
        }

        if (player.isUsingItem && player.useItem.isConsumable) {
            return false
        }

        return when (mode) {
            Mode.LATENCY -> delay > 0
            Mode.DYNAMIC -> holdMs > 0.5f
            Mode.REPEL -> enemies.isNotEmpty()
        }
    }

    // --- queue access -----------------------------------------------------------------------

    /**
     * Where the server currently believes we are: the oldest position still held, or our own when
     * nothing is held. Read through [positionOf], not through `BlinkManager.positions`, because
     * that one trusts the `hasPos` flag other modules flip on rotation-only packets.
     */
    private fun serverPosition(): Vec3 =
        outgoingSnapshots().firstNotNullOfOrNull { positionOf(it) } ?: player.position()

    /** Enemies around both the real and the frozen position, either of which is where the fight is. */
    private fun scanEnemies(serverPos: Vec3): List<Entity> {
        val radius = range.endInclusive.toDouble()

        val found = LinkedHashSet<Entity>()
        found += world.getEntitiesBoxInRange(player.position(), radius) {
            it !== player && it.shouldBeAttacked()
        }
        found += world.getEntitiesBoxInRange(serverPos, radius) {
            it !== player && it.shouldBeAttacked()
        }
        return found.toList()
    }

    /**
     * The position a held packet carries, or null when it carries none.
     *
     * By class, not by the `hasPos` flag: other modules flip that flag on rotation-only packets,
     * whose coordinates are zero — and a position at the world origin is exactly the one Repel
     * would happily freeze on.
     */
    private fun positionOf(snapshot: PacketSnapshot): Vec3? {
        val packet = snapshot.packet
        return if (packet is ServerboundMovePlayerPacket.Pos || packet is ServerboundMovePlayerPacket.PosRot) {
            packet.position
        } else {
            null
        }
    }

    /** The index, in [tickStarts], of the tick a packet was queued in. */
    private fun tickOf(timestamp: Long): Int? {
        var found = -1
        var index = 0
        for (start in tickStarts) {
            if (start <= timestamp) {
                found = index
            } else {
                break
            }
            index++
        }
        return if (found < 0) null else found
    }

    /** The newest remembered position of tick [tick] or of an older one. */
    private fun positionAtOrBefore(positionsByTick: LinkedHashMap<Int, Vec3>, tick: Int): Vec3? =
        positionsByTick.entries.lastOrNull { it.key <= tick }?.value

    private fun outgoingSnapshots(): List<PacketSnapshot> =
        BlinkManager.packetQueue.filter { it.origin == TransferOrigin.OUTGOING }

    private fun outgoingCount(): Int =
        BlinkManager.packetQueue.count { it.origin == TransferOrigin.OUTGOING }

    private fun oldestAge(now: Long): Long {
        val oldest = BlinkManager.packetQueue.firstOrNull { it.origin == TransferOrigin.OUTGOING }
        return if (oldest == null) 0L else now - oldest.timestamp
    }

    /** Releases the front of the queue, everything queued before [cutoff], keeping order intact. */
    private fun flushBefore(cutoff: Long) {
        BlinkManager.flush { snapshot ->
            snapshot.origin == TransferOrigin.OUTGOING && snapshot.timestamp < cutoff
        }
    }

    private fun releaseAll() {
        BlinkManager.flush(TransferOrigin.OUTGOING)
    }

    /**
     * Throws away the positions we are holding and lets the rest out. Used when the server has just
     * moved us: those positions are stale, and sending them is what turns a teleport into a
     * rubberband.
     */
    private fun dropHeldPositions() {
        BlinkManager.packetQueue.removeIf { snapshot ->
            snapshot.origin == TransferOrigin.OUTGOING && snapshot.packet is ServerboundMovePlayerPacket
        }
        releaseAll()
        holdMs = 0f
        heldTicks = 0
    }

    /** Drops everything we hold without sending it, for when the connection itself is gone. */
    private fun clearHeld() {
        runCatching {
            BlinkManager.packetQueue.removeIf { it.origin == TransferOrigin.OUTGOING }
        }
        holding = false
    }

    /** Never let the queue grow past [MAX_QUEUE]: the oldest packets go out first. */
    private fun trimQueue() {
        val snapshots = outgoingSnapshots()
        if (snapshots.size <= MAX_QUEUE) {
            return
        }

        flushBefore(snapshots[snapshots.size - MAX_QUEUE].timestamp + 1)
    }

    private fun resetState() {
        tickStarts.clear()
        holdMs = 0f
        heldTicks = 0
        jitterMs = 0f
        spikeMs = 0f
        spikeUntil = 0L
        enemies = emptyList()
        lastDamageAt = 0L
        lastAttackAt = 0L
        lastHurtTime = 0
        holding = false
        queued = 0
    }

    // --- debug ------------------------------------------------------------------------------

    /** One line per second with everything the module is doing, when [Debug] is on. */
    private fun debugLine(now: Long, serverPos: Vec3) {
        if (!debug || now - lastDebugAt < 1000L) {
            return
        }
        lastDebugAt = now

        val held = oldestAge(now)
        val ping = player.ping

        chat(
            "§7[FakeLagV2] §f%s held=%dms/%dticks target=%dms jitter=%+dms queue=%d queued-total=%d enemies=%d ping=%dms→%dms".format(
                mode.tag, held, heldTicks, holdMs.roundToInt(), (jitterMs + spikeMs).roundToInt(),
                outgoingCount(), queued, enemies.size, ping, ping + held
            ) + " §7server=(%.1f, %.1f, %.1f)".format(serverPos.x, serverPos.y, serverPos.z) + warnings()
        )
    }

    /**
     * Configurations that defeat the module, reported next to the debug line so a wrong setup is
     * visible instead of being guessed from the anticheat log.
     */
    private fun warnings(): String = buildString {
        if (mode == Mode.LATENCY && delay > 500) {
            append(" §cDelay>500 (this is a lag switch, not a connection)")
        }
        if (mode != Mode.LATENCY && delay <= 0) {
            append(" §cDelay=0 (${mode.tag} has no window to work in)")
        }
        if (transmissionOffset > delay && mode == Mode.LATENCY) {
            append(" §cTransmissionOffset>Delay (the delay dips to zero)")
        }
        if (flushOn.isEmpty()) {
            append(" §6FlushOn empty (your hits are delayed too)")
        }
        if (ModuleFakeLag.enabled) {
            append(" §cLiquidBounce FakeLag is on as well (it flushes this queue)")
        }
    }
}
