package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.aiming.AimassistCore
import com.liquidaddon.aiming.FireballValues
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.entity.box
import net.ccbluex.liquidbounce.utils.entity.lastPos
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.raytracing.isLookingAtEntity
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.ShulkerBullet
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

/**
 * FireballDeflect — hits the fireballs that are coming **at you**, with the aimbot's aim.
 *
 * ## The aim is not written here
 *
 * It is [AimassistCore], the same engine `Aimassist` and `AimbotAI` run, driven with
 * [FireballValues]: the target selection is replaced by the projectile this module picks
 * (`forcedTarget`), and everything else is the engine — `Engine = AI` with LiquidBounce's trained
 * model, the mouse-count lattice with its carried residual (`GcdFix`, `GcdDither`), the hand on top
 * of it (`MouseNoise`, `Overshoot`, `StrengthSpread`), the per-tick pitch limit, the frame
 * interpolation, and the silent rotation through `RotationManager` with `MoveFix`. There is no
 * second implementation of "how to turn" in this file.
 *
 * ## The lead, and why the module is built around it
 *
 * A fireball moves about a block per tick, so aiming at where it *is* means aiming a block behind
 * it. The engine's `Predict` (one tick by default) puts the aim where the fireball will be, and this
 * module only attacks when the rotation the **server already has** is on the projectile. The two
 * together are the lead: the rotation sent on one tick points at the position the fireball reaches
 * on the next, so when the attack leaves it is justified by a rotation that arrived before it — the
 * difference between a deflection that lands and one the server rejects as "not looking at that".
 *
 * ## When it fires
 *
 * Every tick, all of these:
 *
 *  - the projectile is one of the selected [Kind]s and it is moving;
 *  - with `OnlyIfHittingMe` it is really coming at you — its velocity points at you (within 60°) or
 *    its path cuts your (widened) hitbox — so a fireball flying past is ignored;
 *  - it is inside the real interaction range: the engine's `Range` is only where the *aim* starts,
 *    the attack always happens from the reach the server accepts;
 *  - the rotation the server has is on it ([attackTolerance] degrees **and** a real ray-trace hit,
 *    walls included);
 *  - the weapon cooldown is over ([minCooldown]) and [cooldownTicks] have passed since the last
 *    deflection.
 *
 * The order is the point: rotation first, attack second.
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

    /** How many ticks ahead the threat path is extrapolated when deciding whether it is coming. */
    private const val THREAT_WINDOW = 40.0

    private val kinds by multiEnumChoice("Deflect", Kind.FIREBALL, Kind.WIND_CHARGE)

    /** Only projectiles whose path actually reaches you are deflected. */
    private val onlyIfHittingMe by boolean("OnlyIfHittingMe", true)

    /** How close the rotation the **server** has must be to the projectile before the hit leaves. */
    private val attackTolerance by float("AttackTolerance", 2.5f, 0.5f..15f, "°")

    /** Probability that a projectile that is in position is actually attacked. */
    private val chance by int("Chance", 100, 0..100, "%")

    /** Weapon cooldown needed before the hit goes out (0 = ignore, always 1.0 on 1.8 servers). */
    private val minCooldown by float("MinCooldown", 0.9f, 0f..1f)

    /** Ticks between two deflections: a swing is not a stream. */
    private val cooldownTicks by int("CooldownTicks", 2, 0..10, "ticks")

    private val swingMode by enumChoice("SwingMode", SwingMode.DO_NOT_HIDE)

    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

    /** The engine: this module's settings, the projectile as target, and a defence's priority. */
    private val values = FireballValues(this)

    private val core = AimassistCore(
        owner = this,
        values = values,
        forcedTarget = { me -> findProjectile(me) },
        // Above an aim: a fireball on a collision course wins over a target that can wait.
        rotationPriority = Priority.IMPORTANT_FOR_USER_SAFETY,
    )

    private var lastAttackTick = -100
    private var deflected = 0
    private var lastNote = ""

    override val tag: String
        get() = if (deflected == 0) "" else deflected.toString()

    /** Friction is fixed to zero for this profile; the handler keeps the engine whole. */
    @Suppress("unused")
    private val mouseHandler = handler<MouseRotationEvent> { core.onMouseRotation(it) }

    /** The tick: the engine aims, then — and only then — the hit may leave. */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        core.tick()
        tryAttack()
    }

    /** Frame interpolation: the tick's step drawn slice by slice, when the camera is ours. */
    @Suppress("unused")
    private val renderHandler = handler<GameRenderEvent> { core.onRender() }

    override fun onEnabled() {
        core.onEnable()
        lastAttackTick = -100
        deflected = 0
        lastNote = ""
        super.onEnabled()
        chat(
            "§7[FireballDeflect] §aattivo §7- mira dell'aimbot (" + values.engine.tag +
                "), colpisce quando la rotazione del server è arrivata"
        )
    }

    override fun onDisabled() {
        // Dropping the target lets the rotation request expire on its own: the server goes back to
        // the camera without a packet of ours.
        core.onDisable()
        super.onDisabled()
    }

    // --- the projectile ---------------------------------------------------------------------

    /** The projectile to hit: the one that reaches us soonest, among those that reach us at all. */
    private fun findProjectile(me: LocalPlayer): Entity? {
        if (mc.gameMode == null || me.isSpectator || mc.gui.screen() != null) {
            return null
        }

        // Scanned a little past the aim range: the engine starts at `Range`, the hit happens closer.
        val limit = values.range.toDouble() + 1.5
        val limitSquared = limit * limit

        var best: Entity? = null
        var bestTicks = Double.MAX_VALUE

        for (entity in world.entitiesForRendering()) {
            if (!matchesKind(entity)) continue
            if (entity.squaredBoxedDistanceTo(me) > limitSquared) continue

            // One tick of its motion. A projectile standing still (in the ground, stuck) can neither
            // hit us nor be led, so it is not our business.
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

    // --- the hit ----------------------------------------------------------------------------

    /**
     * The attack, and every reason it may not happen yet.
     *
     * The gate that matters is [attackTolerance] together with the ray-trace: the rotation the
     * server has must already be on the projectile, so the attack packet arrives *after* the
     * rotation that justifies it. That is the lead (see the class documentation) and it is also
     * what keeps the packet looking like a player who simply aimed and clicked.
     */
    private fun tryAttack() {
        val me = mc.player ?: return
        val projectile = core.target ?: return

        if (me.tickCount - lastAttackTick < cooldownTicks) return

        if (minCooldown > 0f && me.getAttackStrengthScale(0.5f) < minCooldown) {
            note("arma in cooldown")
            return
        }

        val reach = me.entityInteractionRange()
        if (projectile.squaredBoxedDistanceTo(me) > (reach + 0.3) * (reach + 0.3)) {
            note("fuori portata (%.2f)".format(projectile.distanceTo(me)))
            return
        }

        // The aim point of this tick is where the fireball will be next tick; the rotation the
        // server has points there already, which is exactly the moment the hit lands.
        val server = RotationManager.serverRotation
        val aimPoint = Rotation.lookingAt(projectile.boundingBox.center, me.eyePosition)
        val offset = server.directionAngleTo(aimPoint)
        if (offset > attackTolerance) {
            note("rotazione a %.1f° dal proiettile".format(offset))
            return
        }

        // Degrees are not enough: a fireball is a small box, and the ray has to land on it.
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
        if (!debug || message == lastNote) return
        lastNote = message
        chat("§8[FireballDeflect D] §7$message")
    }
}
