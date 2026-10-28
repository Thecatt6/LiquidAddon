package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.network.LocalPlayerFallDamageTracker
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import kotlin.random.Random

/**
 * JumpReset — the vanilla mechanic players call a **jump reset**, done for you.
 *
 * A hit gives you knockback: a push backwards and a little upwards, then you slide away on the
 * ground. If you jump on the **same tick** the hit lands, while sprinting, two things happen (and
 * only those two — nothing is written to any packet):
 *
 *  - the jump's Y velocity (0.42) replaces the knockback's upward push, so you are airborne for the
 *    usual, predictable jump time, land sooner, and on the ground friction stops you quickly — you
 *    fly less far;
 *  - a sprint jump adds a small forward impulse in the direction you are looking. You are looking
 *    at the opponent, so it points against the knockback and cancels part of it.
 *
 * The result is that you stay inside attack range instead of being pushed out of it. Jump too early
 * and the hit arrives while you are already in the air, where friction is lower, so you fly
 * *further*; jump too late and the knockback has already moved you.
 *
 * What the module does is exactly one thing: on the tick of the perfect window it reports the jump
 * key as pressed ([MovementInputEvent.jump] — the very flag `KeyboardInput` hands to the player).
 * Vanilla then performs the jump with its own sprint boost, its own timings, from the ground it
 * stands on, so the server receives an ordinary sprint jump. The module never moves you, never
 * cancels a packet and never touches the rotation.
 *
 * ## The timing
 *
 * Damage sets `hurtTime` and the game counts it down at the start of every entity tick, *before*
 * the input of that tick is read. The practical consequence — the same one LiquidBounce's own
 * `VelocityJumpReset` relies on — is that the first input tick after a hit already reads
 * `hurtTime == 9`, with the knockback in the delta movement: that tick **is** the perfect window,
 * and it is the tick a human presses space on.
 *
 *  - [chance] decides whether this hit is attempted at all;
 *  - [accuracy] decides whether the attempt is timed properly. A mistimed attempt does not become a
 *    random keypress: it either arrives [LATE_MIN]..[LATE_MAX] ticks late — the knockback already
 *    happened, which is the classic human error — or the hit is not jumped at all, because the
 *    player simply did not react. "Too early" would require knowing the hit before it lands, which
 *    only a prediction can do, so this module never guesses;
 *  - [onlyWhenTargeting] only resets hits with an opponent near the crosshair (see
 *    [opponentNearCrosshair]) — the direction the sprint boost pushes you in has to be the
 *    direction the knockback comes from, otherwise the jump is just a jump;
 *  - only a hit **dealt by a player** is ever reset: the server names the direct and the responsible
 *    source of every damage in `ClientboundDamageEventPacket`, and when neither of the two is another
 *    player - a mob, a fall, lava, your own arrow - the hit is ignored, because the jump reset is a
 *    PvP mechanic and nothing else;
 *  - a hit taken **in the air** is ignored as well: there is no ground to jump from, and a jump with
 *    no ground under the feet resets nothing and only desyncs the input;
 *  - [waterCheck] stays quiet in water and lava: there is no sprint jump there, and attempting one
 *    would only look odd;
 *  - fall damage is never reset (LiquidBounce's [LocalPlayerFallDamageTracker]): jumping does not
 *    cancel fall damage, it only burns the fall protection.
 *
 * Nothing is written while a GUI is open and nothing is written when there is no ground to jump
 * from; in both cases the attempt is dropped instead of queued, so the module can never fire a jump
 * out of context a second later.
 */
object ModuleJumpReset : ClientModule("JumpReset", Categories.LIQUIDADDON) {

    /** `hurtTime` on the tick the damage itself is applied (knockback not in the motion yet). */
    private const val HURT_ON_HIT = 10

    /** `hurtTime` of the first input tick after a hit: the knockback is live, the window is open. */
    private const val HURT_PERFECT = 9

    /** How many ticks a mistimed ("too late") attempt waits before it presses the key anyway. */
    private const val LATE_MIN = 1
    private const val LATE_MAX = 4

    /** Ticks within which a damage packet still counts as "the hit we are reacting to". */
    private const val HIT_SOURCE_WINDOW = 2

    /** Percentage chance of attempting a jump reset on a hit. */
    private val chance by int("Chance", 100, 0..100, "%")

    /**
     * If an attempt is made, the chance that it is timed correctly. The rest are deliberate misses:
     * the jump arrives a few ticks late, or it does not arrive at all — a player who sometimes gets
     * the timing right and sometimes does not, instead of a script that always does.
     */
    private val accuracy by int("Accuracy", 85, 0..100, "%")

    /** Only reset hits that come from an opponent near the crosshair — see [opponentNearCrosshair]. */
    private val onlyWhenTargeting by boolean("OnlyWhenTargeting", false)

    /** Never jump in water or lava: there is no sprint jump to reset a knockback with. */
    private val waterCheck by boolean("WaterCheck", true)

    /** How close the opponent has to be to count as "the one hitting you" for [onlyWhenTargeting]. */
    private val targetRange by float("TargetRange", 4f, 2f..6f, "blocks")

    /** How wide around the crosshair that opponent may be, in degrees. */
    private val targetFov by float("TargetFov", 60f, 10f..180f, "°")

    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

    /**
     * The attempt in flight. `-1` = nothing planned, `0` = press the key this tick, `n > 0` = in `n`
     * ticks. It is a tick countdown and not a timer on purpose: the jump has to land on a specific
     * `hurtTime` value, and that value only changes once per tick.
     */
    private var pendingJump = -1

    /** Whether the attempt in flight is the accurate one (perfect timing) or a deliberate miss. */
    private var pendingPerfect = false

    /** What the attempt in flight is, for [debug] and for the counters. */
    private var pendingLabel = ""

    /** `tickCount` of the hit we last made a decision for, so one hit is never rolled twice. */
    private var decidedTick = -1

    /** `tickCount` of the last damage the server told us came from another player. */
    private var lastPlayerHitTick = -1000

    private var attempts = 0
    private var perfect = 0
    private var lastSkip = "idle"

    override val tag: String
        get() = if (attempts == 0) "" else "$perfect/$attempts"

    override fun onEnabled() {
        reset()
        super.onEnabled()
    }

    override fun onDisabled() {
        reset()
        super.onDisabled()
    }

    private fun reset() {
        pendingJump = -1
        pendingPerfect = false
        pendingLabel = ""
        decidedTick = -1
        lastPlayerHitTick = -1000
        attempts = 0
        perfect = 0
        lastSkip = "idle"
    }

    /**
     * The server names the source of every damage it deals: the direct entity (the sword, the arrow)
     * and the one responsible for it (the player who swung, the player who shot). When either of the
     * two is another player, this is a PvP hit and the tick is remembered; every other source - mobs,
     * falls, lava, your own arrow bouncing back - leaves the module deaf, which is the whole point of
     * a jump reset.
     */
    @Suppress("unused")
    private val damageHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (event.origin != TransferOrigin.INCOMING || packet !is ClientboundDamageEventPacket) {
            return@handler
        }
        val me = mc.player ?: return@handler
        if (packet.entityId != me.id) {
            return@handler
        }
        val level = mc.level ?: return@handler
        val direct = level.getEntity(packet.sourceDirectId)
        val cause = level.getEntity(packet.sourceCauseId)
        if ((direct is Player && direct !== me) || (cause is Player && cause !== me)) {
            lastPlayerHitTick = me.tickCount
        }
    }

    /**
     * The whole module runs here, because this is where vanilla reads the jump key for the tick:
     * `KeyboardInput.tick` fires [MovementInputEvent] and then builds the `Input` the player moves
     * with, so a `jump = true` set here is indistinguishable from the player pressing space.
     */
    @Suppress("unused")
    private val movementHandler = handler<MovementInputEvent> { event ->
        val me = mc.player ?: return@handler

        // An attempt is in flight: it fires on one specific tick, whatever happens around it.
        if (pendingJump >= 0) {
            if (pendingJump > 0) {
                pendingJump--
                // Reaching zero now means this is the tick the attempt was scheduled for.
                if (pendingJump > 0) return@handler
            }
            firePending(event, me)
            return@handler
        }

        // A new hit. Both values are accepted so the module works whichever of the two the client
        // manages to observe first; `HURT_PERFECT` is the usual case (see the class documentation).
        val hurt = me.hurtTime
        if (decidedTick == me.tickCount) return@handler
        val perfectOffset = when (hurt) {
            HURT_ON_HIT -> 1
            HURT_PERFECT -> 0
            else -> return@handler
        }
        decidedTick = me.tickCount
        decide(me, perfectOffset)

        // The perfect window may be *this* tick (the client usually sees the hit at `hurtTime == 9`):
        // press the key in the same input read that discovered it, or the jump would be one tick
        // late and reset nothing.
        if (pendingJump == 0) firePending(event, me)
    }

    /** Fires the scheduled attempt (if any) and forgets it. */
    private fun firePending(event: MovementInputEvent, me: LocalPlayer) {
        val label = pendingLabel
        val wasPerfect = pendingPerfect
        clearPending()
        fire(event, me, wasPerfect, label)
    }

    /**
     * Rolls the attempt for the hit that was just applied, and schedules it:
     *
     *  - accurate → the perfect window, [perfectOffset] ticks from now;
     *  - inaccurate → either the same jump [LATE_MIN]..[LATE_MAX] ticks late (the knockback has
     *    already moved you, so it resets nothing), or no jump at all for this hit.
     */
    private fun decide(me: LocalPlayer, perfectOffset: Int) {
        if (me.tickCount - lastPlayerHitTick > HIT_SOURCE_WINDOW) {
            note("skipped (the hit is not from a player)")
            return
        }
        if (!me.onGround()) {
            note("skipped (hit in aria: non c'è terra da cui saltare)")
            return
        }
        if (!canOperate(me)) {
            note("skipped (${lastSkip})")
            return
        }
        if (chance <= 0 || Random.nextInt(100) >= chance) {
            note("chance roll failed")
            return
        }

        if (Random.nextInt(100) < accuracy) {
            pendingJump = perfectOffset
            pendingPerfect = true
            pendingLabel = "perfect"
            note("perfect jump planned (in $perfectOffset ticks, hurtTime=$HURT_PERFECT)")
            return
        }

        // A miss, and a real one: either the hand is late, or the hand does not move at all.
        pendingPerfect = false
        if (Random.nextBoolean()) {
            pendingJump = perfectOffset + LATE_MIN + Random.nextInt(LATE_MAX - LATE_MIN + 1)
            pendingLabel = "late"
            note("mistimed jump: too late (in $pendingJump ticks)")
        } else {
            pendingJump = -1
            pendingLabel = ""
            note("mistimed jump: no reaction")
        }
    }

    /**
     * Presses the jump key for this tick — or drops the attempt if the world changed in between.
     *
     * The conditions are checked here and not in [decide] on purpose: between the hit and the tick
     * the jump belongs to, the player may have left the ground, entered water or opened the
     * inventory, and a jump pressed then would be exactly the out-of-context input this module is
     * not supposed to produce.
     */
    private fun fire(event: MovementInputEvent, me: LocalPlayer, wasPerfect: Boolean, label: String) {
        if (!canOperate(me)) {
            note("dropped $label jump (${lastSkip})")
            return
        }
        if (!me.onGround()) {
            // No ground, no jump: pressing the key anyway would only desync the input.
            note("dropped $label jump (not on ground)")
            return
        }

        event.jump = true
        attempts++

        // A perfect jump needs the sprint boost to push against the knockback. Without sprint it is
        // still a jump, it just does not reset anything, so it must not be counted as accurate.
        val sprintBoost = me.isSprinting
        if (wasPerfect && sprintBoost) perfect++

        if (debug) {
            chat(
                "§7[JumpReset] §f$label (hurtTime=${me.hurtTime}, sprint=$sprintBoost) " +
                    "§7total=$perfect/$attempts"
            )
        } else if (chatLog && attempts % 10 == 0) {
            chat("§7[JumpReset] §f$perfect perfect out of $attempts jumps")
        }
    }

    /**
     * Everything that has to hold for the module to touch the jump key at all: in game, no screen
     * open, not currently falling to damage (a jump does not cancel a fall, it only burns the fall
     * protection), not in a liquid when [waterCheck] is on, and — when [onlyWhenTargeting] is on —
     * an opponent near the crosshair to have a direction to jump against.
     */
    private fun canOperate(me: LocalPlayer): Boolean {
        if (mc.gameMode == null) {
            lastSkip = "not in game"
            return false
        }
        if (mc.gui.screen() != null) {
            lastSkip = "screen open"
            return false
        }
        if (LocalPlayerFallDamageTracker.isCurrentFallDamage) {
            lastSkip = "fall damage"
            return false
        }
        if (waterCheck && inLiquid(me)) {
            lastSkip = "in liquid"
            return false
        }
        if (onlyWhenTargeting && !opponentNearCrosshair(me)) {
            lastSkip = "no opponent on the crosshair"
            return false
        }
        return true
    }

    private fun inLiquid(me: LocalPlayer): Boolean =
        me.isInWater || me.isInLava || me.isSwimming || me.isUnderWater

    /**
     * True when an opponent is close and roughly in front of you.
     *
     * The client is never told which entity dealt the hit, so "the opponent hitting you" is taken
     * as "an enemy inside [targetRange] near the crosshair" ([targetFov] wide). That is the only
     * case where the mechanic makes sense anyway: the sprint boost pushes you in the direction you
     * look, so if nobody is in front of you there is nothing for the knockback to be cancelled
     * against. Friends and teams follow LiquidBounce's combat settings through `shouldBeAttacked()`.
     */
    private fun opponentNearCrosshair(me: LocalPlayer): Boolean {
        val level = mc.level ?: return false
        val box = me.boundingBox.inflate(targetRange.toDouble())
        val candidates: List<LivingEntity> = try {
            level.getEntitiesOfClass(LivingEntity::class.java, box) { true }
        } catch (_: Exception) {
            // Fallback for a client where getEntitiesOfClass is not available.
            level.getEntities(null, box).filterIsInstance<LivingEntity>()
        }

        val view = Rotation(me.yRot, me.xRot)
        return candidates.any { entity ->
            entity !== me && entity.isAlive && entity.shouldBeAttacked() &&
                view.directionAngleTo(
                    Rotation.lookingAt(entity.boundingBox.center, me.eyePosition)
                ) <= targetFov / 2f
        }
    }

    private fun clearPending() {
        pendingJump = -1
        pendingPerfect = false
        pendingLabel = ""
    }

    private fun note(message: String) {
        if (debug) chat("§7[JumpReset] §f$message")
    }
}
