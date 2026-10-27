package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.entity.box
import net.ccbluex.liquidbounce.utils.entity.lastPos
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.raytracing.isLookingAtEntity
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.ShulkerBullet
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * FireballDeflect — hits the fireballs that are coming **at you**, with the aim of an aimbot.
 *
 * A ghast fireball, a blaze fireball, a wind charge or a shulker bullet is an entity: hitting it
 * reflects or destroys it before it lands. A player does that by hand, once in a while, by keeping
 * the crosshair on the projectile while it is still a block away; this module does it every time, on
 * the tick the hit can land.
 *
 * ## What "the aim of an aimbot" means here
 *
 * The rotation is not written to the camera and it is not a nudge: it is a **silent rotation handed
 * to `RotationManager`** (the same one `AimbotAI` uses), quantized to whole mouse counts and stepped
 * at `AimSpeed` degrees per tick, so the packets look like a mouse that snapped onto a moving target.
 * The camera stays where the player is pointing it, and `MoveFix` keeps the movement keys meaning
 * what they meant before the rotation.
 *
 * ## The lead, and why the whole module is built around it
 *
 * A fireball moves about a block per tick, so aiming at where it *is* means aiming a block behind it.
 * The aim goes to `position + velocity * Predict` — the place it will be on the next tick — and the
 * **attack waits for the rotation the server already has** ([RotationManager.serverRotation]) to be
 * on it. That is not a delay, it is the lead: the rotation sent on one tick is aimed at the position
 * the fireball reaches on the next one, so by the time the server has that rotation the fireball is
 * exactly under it. Attacking on the same tick the rotation was computed is what makes a deflection
 * miss, or worse, get the attack rejected as "not looking at the entity".
 *
 * ## When it fires
 *
 * All of these, every tick:
 *
 *  - the projectile is one of the selected [Kind]s and it is moving;
 *  - with `OnlyIfHittingMe` it is really coming at you — either its velocity points at you (within
 *    60°) or its path cuts your (widened) hitbox — so a fireball flying past is ignored;
 *  - it is inside the real interaction range: `Range` is only the distance at which the module
 *    starts *aiming*, the attack always happens from inside the reach the server accepts;
 *  - the server rotation is on it ([attackTolerance] degrees **and** a real ray-trace hit, walls
 *    included), which is what keeps the attack packet justified by a rotation that arrived before it;
 *  - the weapon cooldown is over ([minCooldown]) and [attackInterval] ticks have passed since the
 *    last deflection.
 *
 * The order is the point: rotation first, attack second, never the two in the opposite order.
 */
object ModuleFireballDeflect : ClientModule("FireballDeflect", Categories.LIQUIDADDON) {

    /** What counts as a threat worth punching. */
    enum class Kind(override val tag: String) : Tagged {
        /** Ghast, blaze and dragon fireballs: all of them are [Fireball]s. */
        FIREBALL("Fireball"),

        /** Wind charges, breeze included. */
        WIND_CHARGE("WindCharge"),

        /** Shulker bullets. */
        SHULKER("Shulker")
    }

    /** Ticks the silent aim request stays alive after the last update (see `RotationTarget`). */
    private const val AIM_TICKS_UNTIL_RESET = 3

    /** How many ticks ahead the threat path is extrapolated when deciding whether it is coming. */
    private const val THREAT_WINDOW = 40.0

    private val kinds by multiEnumChoice("Deflect", Kind.FIREBALL, Kind.WIND_CHARGE)

    /**
     * Distance at which a projectile is picked up and aimed at. The attack itself only ever happens
     * from inside the real interaction range — this is the head start the aim needs.
     */
    private val range by float("Range", 4.5f, 1f..8f, "blocks")

    /** Only projectiles whose path actually reaches you are deflected. */
    private val onlyIfHittingMe by boolean("OnlyIfHittingMe", true)

    /** Ticks of lead: the aim goes to where the projectile will be in this many ticks. */
    private val predict by int("Predict", 1, 0..3, "ticks")

    /** Degrees of rotation per tick. 60 is a fast snap onto something one block away. */
    private val aimSpeed by float("AimSpeed", 60f, 5f..180f, "°/tick")

    /** How close the rotation the **server** has must be to the aim before the attack may leave. */
    private val attackTolerance by float("AttackTolerance", 2.5f, 0.5f..15f, "°")

    /** Whole mouse counts of noise on the aim step, so the snap is a hand and not a lerp. */
    private val noise by int("Noise", 1, 0..3, "counts")

    /** Only the server sees the aim; the camera stays where the player points it. */
    private val silent by boolean("Silent", true)

    /** How movement is corrected while the camera and the rotation disagree. */
    private val moveFix by enumChoice("MoveFix", MovementCorrection.SILENT)

    /** Probability that a deflected projectile is actually attacked. */
    private val chance by int("Chance", 100, 0..100, "%")

    /** Weapon cooldown needed before the hit goes out (0 = ignore, always 1.0 on 1.8 servers). */
    private val minCooldown by float("MinCooldown", 0.9f, 0f..1f)

    /** Ticks between two deflections: a swing is not a stream. */
    private val attackInterval by int("CooldownTicks", 2, 0..10, "ticks")

    private val swingMode by enumChoice("SwingMode", SwingMode.DO_NOT_HIDE)

    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

    /** The projectile being aimed at, or `null`. */
    private var target: Entity? = null

    private var lastAttackTick = -100
    private var deflected = 0
    private var lastDebug = ""

    override val tag: String
        get() = if (deflected == 0) "" else deflected.toString()

    override fun onEnabled() {
        super.onEnabled()
        target = null
        lastAttackTick = -100
        deflected = 0
        lastDebug = ""
        chat("§7[FireballDeflect] §aattivo §7- mira silenziosa, colpisce solo quando la rotazione è arrivata")
    }

    override fun onDisabled() {
        super.onDisabled()
        // Dropping the target lets the silent request expire on its own (`ticksUntilReset`): the
        // rotation goes back to the camera without a packet of ours.
        target = null
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player ?: return@handler
        if (mc.gameMode == null || me.isSpectator || mc.gui.screen() != null) {
            target = null
            return@handler
        }

        val projectile = findProjectile(me)
        target = projectile
        if (projectile == null) {
            note("nessuna fireball in arrivo")
            return@handler
        }

        val aim = Rotation.lookingAt(aimPoint(projectile), me.eyePosition)

        // The rotation the packets are built from: what the server has in silent mode, the camera
        // otherwise. Stepping the delta (instead of assigning the goal) is what makes the aim travel
        // like a mouse and gives the attack a rotation to be justified by.
        val current = if (silent) RotationManager.serverRotation else Rotation(me.yRot, me.xRot)
        val next = step(current, aim)

        if (silent) {
            RotationManager.setRotationTarget(
                RotationTarget(
                    rotation = next,
                    entity = projectile,
                    ticksUntilReset = AIM_TICKS_UNTIL_RESET,
                    resetThreshold = attackTolerance,
                    considerInventory = true,
                    movementCorrection = moveFix,
                ),
                // Same priority a defence module deserves: it may override an aim, never a hit.
                Priority.IMPORTANT_FOR_USER_SAFETY,
                this
            )
        } else {
            writeCamera(me, next)
        }

        tryAttack(me, projectile, aim)
    }

    // --- the projectile ---------------------------------------------------------------------

    /** The projectile to hit: the one that reaches us soonest, among those that reach us at all. */
    private fun findProjectile(me: LocalPlayer): Entity? {
        val limit = range.toDouble() + 1.0
        val limitSquared = limit * limit

        var best: Entity? = null
        var bestTicks = Double.MAX_VALUE

        for (entity in world.entitiesForRendering()) {
            if (!matchesKind(entity)) continue
            if (entity.squaredBoxedDistanceTo(me) > limitSquared) continue

            // One tick of its motion. A projectile standing still (already in the ground, stuck)
            // can neither hit us nor be led, so it is not our business.
            val velocity = entity.position().subtract(entity.lastPos)
            if (velocity.lengthSqr() < 1.0e-4) continue

            if (onlyIfHittingMe && !comingAtMe(me, entity, velocity)) continue

            // How soon it is here: the smaller, the more urgent.
            val ticks = entity.position().distanceTo(me.eyePosition) / velocity.length()
            if (ticks < bestTicks) {
                bestTicks = ticks
                best = entity
            }
        }

        return best
    }

    private fun matchesKind(entity: Entity): Boolean = when (entity) {
        is Fireball -> kinds.contains(Kind.FIREBALL)
        is WindCharge -> kinds.contains(Kind.WIND_CHARGE)
        is ShulkerBullet -> kinds.contains(Kind.SHULKER)
        else -> false
    }

    /**
     * Whether this projectile is coming at us.
     *
     * Two tests, because one is not enough: the velocity pointing at us (within 60°) catches
     * everything flying in our direction, and the path cutting the hitbox catches the one aimed at
     * our feet, whose velocity is not close enough to the vector to our centre to pass the first.
     */
    private fun comingAtMe(me: LocalPlayer, entity: Entity, velocity: Vec3): Boolean {
        val toPlayer = me.boundingBox.center.subtract(entity.position())
        val dot = toPlayer.dot(velocity)
        val towards = dot > 0.5 * velocity.length() * toPlayer.length()

        val box = me.boundingBox.inflate(entity.box.xsize / 2.0 + 0.15)
        val path = box.clip(
            entity.position(),
            entity.position().add(velocity.scale(THREAT_WINDOW))
        ).isPresent

        return towards || path
    }

    /** Where the aim goes: the projectile's position [predict] ticks from now. */
    private fun aimPoint(projectile: Entity): Vec3 {
        val velocity = projectile.position().subtract(projectile.lastPos)
        // The middle of the box, not a corner: the hit is registered against the box, and the middle
        // is the point that survives the projectile moving inside its own box between two ticks.
        val centre = projectile.position().add(0.0, projectile.box.ysize / 2.0, 0.0)
        return centre.add(velocity.scale(predict.toDouble()))
    }

    // --- the aim ----------------------------------------------------------------------------

    /**
     * One tick of the snap: the remaining angle capped at [aimSpeed], then **quantized to whole
     * mouse counts** — a delta that is not a multiple of the mouse lattice is a rotation no mouse
     * could have produced, and it is the first thing an aim check looks for.
     */
    private fun step(from: Rotation, to: Rotation): Rotation {
        val deltaYaw = Mth.wrapDegrees(to.yaw - from.yaw)
        val deltaPitch = to.pitch - from.pitch

        val gcd = RotationUtil.gcd.toFloat()
        if (gcd <= 1.0e-6f) {
            return Rotation(
                from.yaw + deltaYaw.coerceIn(-aimSpeed, aimSpeed),
                (from.pitch + deltaPitch.coerceIn(-aimSpeed, aimSpeed)).coerceIn(-89f, 89f)
            )
        }

        var countsYaw = (deltaYaw.coerceIn(-aimSpeed, aimSpeed) / gcd).roundToInt()
        var countsPitch = (deltaPitch.coerceIn(-aimSpeed, aimSpeed) / gcd).roundToInt()

        // A remaining angle smaller than one count still takes a count: without this the aim stalls
        // a hair short of the fireball and the attack gate never opens.
        if (countsYaw == 0 && abs(deltaYaw) > gcd * 0.6f) {
            countsYaw = if (deltaYaw > 0f) 1 else -1
        }
        if (countsPitch == 0 && abs(deltaPitch) > gcd * 0.6f) {
            countsPitch = if (deltaPitch > 0f) 1 else -1
        }

        if (noise > 0) {
            countsYaw += noiseCounts()
            countsPitch += noiseCounts()
        }

        return Rotation(
            from.yaw + countsYaw * gcd,
            (from.pitch + countsPitch * gcd).coerceIn(-89f, 89f),
            isNormalized = true
        )
    }

    /** ±1 count most of the time, ±2 now and then, nothing at all otherwise. */
    private fun noiseCounts(): Int {
        val roll = Random.nextInt(100)
        val amplitude = when {
            noise >= 2 && roll >= 82 -> 2
            roll >= 28 -> 1
            else -> 0
        }
        if (amplitude == 0) return 0
        return if (Random.nextBoolean()) amplitude else -amplitude
    }

    /** `Silent = false`: the camera itself turns, by the same capped, quantized step. */
    private fun writeCamera(me: LocalPlayer, rotation: Rotation) {
        me.yRot += Mth.wrapDegrees(rotation.yaw - me.yRot)
        me.xRot = rotation.pitch.coerceIn(-89.9f, 89.9f)
    }

    // --- the hit ----------------------------------------------------------------------------

    /**
     * The attack, and every reason it may not happen yet.
     *
     * The gate that matters is [attackTolerance] together with the ray-trace: the rotation the
     * server has must already be on the projectile, so the attack packet arrives *after* the
     * rotation that justifies it. That is both what makes the deflection land and what keeps the
     * packet looking like a player who simply aimed and clicked.
     */
    private fun tryAttack(me: LocalPlayer, projectile: Entity, aim: Rotation) {
        if (me.tickCount - lastAttackTick < attackInterval) return

        if (minCooldown > 0f && me.getAttackStrengthScale(0.5f) < minCooldown) {
            note("arma in cooldown")
            return
        }

        val reach = me.entityInteractionRange()
        if (projectile.squaredBoxedDistanceTo(me) > (reach + 0.3) * (reach + 0.3)) {
            note("fuori portata (%.2f)".format(projectile.distanceTo(me)))
            return
        }

        val server = RotationManager.serverRotation
        val offset = server.directionAngleTo(aim)
        if (offset > attackTolerance) {
            note("rotazione a %.1f° dal bersaglio".format(offset))
            return
        }

        // Degrees are not enough: a fireball is a small box, and between two ticks it can be behind
        // a wall or past the crosshair. The ray has to land on it.
        if (isLookingAtEntity(me, projectile, server, reach + 0.3, 0.0) == null) {
            note("il mirino del server non è ancora sulla fireball")
            return
        }

        if (chance < 100 && Random.nextInt(100) >= chance) {
            note("chance")
            return
        }

        if (!attackEntity(projectile, swingMode)) {
            note("attacco annullato")
            return
        }

        lastAttackTick = me.tickCount
        deflected++
        report(projectile)
    }

    private fun report(projectile: Entity) {
        if (chatLog && deflected % 10 == 0) {
            chat("§7[FireballDeflect] §f$deflected fireball deviate")
        }
        if (debug) {
            note("colpita " + projectile.name.string + " (#$deflected)")
        }
    }

    /** One chat line per *change* of state: the same reason repeated is noise, not debugging. */
    private fun note(message: String) {
        if (!debug || message == lastDebug) return
        lastDebug = message
        chat("§8[FireballDeflect D] §7$message")
    }
}
