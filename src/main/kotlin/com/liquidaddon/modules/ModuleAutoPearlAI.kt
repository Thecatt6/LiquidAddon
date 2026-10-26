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
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.airTicks
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.kotlin.Priority as RotationPriority
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfo
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfoRenderer
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryType
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Util
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.SupportType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AutoPearl — a pearl for TNT sumo: it throws an ender pearl at a spot that saves you.
 *
 * In TNT sumo you are knocked off a small platform over the void, and the only way back is an ender
 * pearl. A pearl is not "aim at the block": it is a ballistic throw, so this module fits the arc
 * against vanilla's own physics and verifies it with LiquidBounce's collision-aware trajectory
 * simulation *before* spending a pearl.
 *
 * Two modes:
 *  - [Mode.SAVE] — watches the last place you stood safely ([safeSpot]) and, as soon as you are
 *    falling with nothing but air below, throws back onto it. If that position is no longer usable
 *    (blocks broken, too far) it looks for another solid block top around it and lands there;
 *  - [Mode.ENEMY] — pearls at the enemy, using their movement prediction, so the pearl teleports you
 *    into them instead of in front of them.
 *
 * ### The aim
 *
 * Same pipeline as the Aimassist module, because the throw is a rotation like any other:
 *
 *  1. [throwCandidates] — the throw rotation is **fitted to the physics**, not computed with a
 *     closed-form solver: those assume you stand still, while a pearl inherits your own velocity
 *     (and while you are falling that is most of the throw). Both the pitch and the yaw are
 *     searched until the arc passes through the target, and only the *descending* part of the arc
 *     counts: a pearl that crosses the spot while still rising flies on and hits the side of the
 *     platform. That fit is only a guess — [verify] then judges it and a few variants of it with
 *     LiquidBounce's collision-aware simulation, which is the only thing that knows what is in
 *     between, and the first arc that lands on a block *top* wins;
 *  2. [humanize] — the rotation is stepped towards with [maxTurn] degrees per tick and quantized to
 *     whole mouse counts ([gcdFix] + [gcdDither]), so every throw is a rotation a real mouse could
 *     have produced;
 *  3. [RotationManager] — the rotation is handed over as a [RotationTarget] with the configured
 *     [MovementCorrection]. Silent means the camera never moves and only the `UseItem` packet
 *     carries it, which is exactly what matters for a pearl: the server throws along the rotation in
 *     that packet;
 *  4. the pearl is thrown only when the rotation the server has is within [aimTolerance] of the
 *     solved one, with [slotReset] ticks of silent hotbar swap and the same [swing] mode.
 *
 * ### Where the pearl comes from
 *
 * `OffhandWithHotbar`, i.e. the off hand or any hotbar slot, selected silently: your screen and your
 * selected slot stay where they are.
 *
 * Settings:
 *   Mode            Save (get back on the platform) or Enemy (pearl into a player)
 *   MinFall         fall distance (blocks) before a save is considered
 *   MinAirTicks     ticks in the air before a save is considered
 *   VoidScan        how many blocks below are scanned for ground: none = you are over the void
 *   SafeMemory      how long (ms) the remembered safe spot stays valid
 *   SafeSearch      radius (blocks) searched for another landing spot
 *   LandingTolerance  how far the arc may drift from the spot between two ticks
 *   EnemyRange      max distance of the target in Enemy mode
 *   EnemyTolerance  how close to the enemy the pearl has to land
 *   EnemyTrigger    Always, or only while you are in the air
 *   Rotate          turn to the solution instead of throwing immediately
 *   MaxTurn         degrees corrected per tick while turning
 *   AimTolerance    how close (degrees) the server rotation must be before throwing
 *   AimTicks        max ticks spent aiming before giving up
 *   Silent          only the UseItem packet carries the rotation, the camera stays still
 *   MoveFix         movement correction used with Silent
 *   GcdFix / GcdDither  put the throw rotation on the mouse lattice, with a human rounding boundary
 *   Simulate        judge every candidate with LiquidBounce's simulation (never throw into the void)
 *   WaitForPearl    do not throw while one of your pearls is still in flight
 *   Cooldown        minimum ms between two throws
 *   Chance          chance (%) that an attempt actually throws
 *   SlotReset       ticks the silent hotbar swap is held
 *   Swing           swing mode of the throw
 *   PauseOnScreen / PauseOnUse / PauseInVehicle / PauseWithAutoClicker
 *   ChatLog         print every throw with the distance of the landing
 *   Debug           print the plan, the solved rotation and the simulation result
 */
object ModuleAutoPearlAI : ClientModule(
    "AutoPearl AI",
    Categories.LIQUIDADDON,
    aliases = listOf("PearlSave", "SumoPearl")
) {

    /** Longest simulated flight, in ticks. Vanilla removes a pearl after 100 ticks in the air. */
    private const val MAX_SIM_TICKS = 120

    /** How much the rounding boundary may wobble, in mouse counts (same value as the Aimassist). */
    private const val DITHER_WOBBLE = 0.18

    /** How many candidate landing spots are solved (each one costs a ballistic sweep). */
    private const val MAX_CANDIDATES = 16

    /**
     * How many candidates get a full solve, i.e. how many times a fit plus its verified variants are
     * tried per attempt. Every variant costs one real simulation, so this stays low.
     */
    private const val MAX_SOLVED = 4

    // --- vanilla physics of a thrown pearl, as LiquidBounce's simulation models it ---------------

    /** Speed a pearl leaves the hand with. */
    private const val PEARL_SPEED = 1.5

    /** Air drag applied every tick. */
    private const val PROJECTILE_DRAG = 0.99

    /** Gravity applied every tick. */
    private const val PROJECTILE_GRAVITY = 0.03

    /** First pass over the whole pitch circle, in degrees. */
    private const val COARSE_STEP = 3f

    /** Local refinement around the coarse winner (and the yaw), in degrees. */
    private const val FINE_STEP = 0.25f

    /** How far the refinement looks around the coarse winner. */
    private const val REFINE_SPAN = 3f

    /** How far the yaw may move away from the geometric one while the arc is being fitted. */
    private const val YAW_SPAN = 15f

    /** The arc is only followed while it stays this far below the throw point. */
    private const val ARC_DROP_LIMIT = 48.0

    /** How far the aim may wander while re-fitting a target that has not moved. */
    private const val YAW_TWEAK = 2f

    /**
     * Offsets (degrees) applied around a fit when it is checked against the real simulation. A cheap
     * ballistic fit can pass centimetres from the target and still clip the edge of the platform on
     * the way, which only the collision-aware simulation knows.
     */
    private val NUDGE_STEPS = floatArrayOf(1.5f, 3f)

    /** A fit worse than this is not worth a simulation: it is not even close to the target. */
    private const val MAX_FIT_ERROR = 3.0

    /**
     * How long (ms) to wait after a plan failed before planning again. Planning is the expensive part
     * — a dozen collision-aware simulations per candidate — and a failed plan would otherwise be
     * retried on every single tick.
     */
    private const val PLAN_RETRY = 150

    enum class Mode(override val tag: String) : Tagged {
        SAVE("Save"), ENEMY("Enemy")
    }

    enum class EnemyTrigger(override val tag: String) : Tagged {
        ALWAYS("Always"), FALLING("Falling")
    }

    enum class MoveFix(override val tag: String) : Tagged {
        OFF("Off"), STRICT("Strict"), SILENT("Silent")
    }

    /** A ready-to-throw solution: the rotation, where it lands, and a label for the logs. */
    private data class Solution(val rotation: Rotation, val label: String, val landing: Vec3)

    // --- mode ---------------------------------------------------------------------------------

    private val mode by enumChoice("Mode", Mode.SAVE)

    // --- save ---------------------------------------------------------------------------------

    private val minFallValue = float("MinFall", 1.0f, 0.2f..5.0f, "blocks")
    private val minFall by minFallValue
    private val minAirTicksValue = int("MinAirTicks", 4, 1..20, "ticks")
    private val minAirTicks by minAirTicksValue

    /** Air blocks below the player that still count as "there is ground": more than this = void. */
    private val voidScanValue = int("VoidScan", 6, 1..20, "blocks")
    private val voidScan by voidScanValue

    private val safeMemoryValue = int("SafeMemory", 15000, 1000..60000, "ms")
    private val safeMemory by safeMemoryValue
    private val safeSearchValue = float("SafeSearch", 3.0f, 0.5f..8.0f, "blocks")
    private val safeSearch by safeSearchValue
    private val landingToleranceValue = float("LandingTolerance", 0.6f, 0.2f..3.0f, "blocks")
    private val landingTolerance by landingToleranceValue

    // --- enemy --------------------------------------------------------------------------------

    /** How close to the enemy the pearl has to land to count as a hit on them. */
    private val enemyToleranceValue = float("EnemyTolerance", 1.5f, 0.5f..4f, "blocks")
    private val enemyTolerance by enemyToleranceValue

    private val enemyRangeValue = float("EnemyRange", 12f, 3f..30f, "blocks")
    private val enemyRange by enemyRangeValue
    private val enemyTrigger by enumChoice("EnemyTrigger", EnemyTrigger.FALLING)

    // --- aim (the Aimassist pipeline) ---------------------------------------------------------

    private val rotateValue = boolean("Rotate", true)
    private val rotate by rotateValue
    private val maxTurnValue = float("MaxTurn", 45f, 1f..180f, "°")
    private val maxTurn by maxTurnValue
    private val aimToleranceValue = float("AimTolerance", 1.0f, 0.1f..5f, "°")
    private val aimTolerance by aimToleranceValue
    private val aimTicksValue = int("AimTicks", 10, 1..20, "ticks")
    private val aimTicks by aimTicksValue

    private val silentValue = boolean("Silent", true)
    private val silent by silentValue
    private val moveFix by enumChoice("MoveFix", MoveFix.SILENT)

    private val gcdFixValue = boolean("GcdFix", true)
    private val gcdFix by gcdFixValue
    private val gcdDitherValue = boolean("GcdDither", true)
    private val gcdDither by gcdDitherValue

    // --- throw --------------------------------------------------------------------------------

    private val simulateValue = boolean("Simulate", true)
    private val simulate by simulateValue
    private val waitForPearl by boolean("WaitForPearl", true)

    private val cooldownValue = int("Cooldown", 1200, 0..5000, "ms")
    private val cooldown by cooldownValue
    private val chanceValue = int("Chance", 100, 0..100, "%")
    private val chance by chanceValue
    private val slotResetValue = int("SlotReset", 2, 1..20, "ticks")
    private val slotReset by slotResetValue
    private val swing by enumChoice("Swing", SwingMode.DO_NOT_HIDE)

    private val pauseOnScreen by boolean("PauseOnScreen", true)
    private val pauseOnUse by boolean("PauseOnUse", false)
    private val pauseInVehicle by boolean("PauseInVehicle", true)

    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

    // --- state --------------------------------------------------------------------------------

    /** The last position the player stood on safely. */
    private var safeSpot: Vec3? = null
    private var safeSpotAt = 0L

    /** The solution being executed, and how many ticks we already spent turning towards it. */
    private var planned: Solution? = null
    private var plannedTicks = 0

    private var lastThrow = 0L
    private var lastPlanFail = 0L
    private var thrown = 0

    /**
     * Result of the [chance] roll for the current episode. Rolled once per "danger" (or per enemy),
     * not once per tick: a per-tick roll would be a certainty within a fraction of a second.
     */
    private var rollPassed: Boolean? = null

    /** Mouse-lattice residual, carried between ticks exactly like the Aimassist does. */
    private var yawCarry = 0.0
    private var pitchCarry = 0.0

    override val tag: String
        get() = when {
            thrown == 0 -> ""
            planned != null -> "aim"
            else -> "$thrown"
        }

    override fun onEnabled() {
        cancelPlan()
        safeSpot = null
        safeSpotAt = 0L
        lastThrow = 0L
        lastPlanFail = 0L
        thrown = 0
        yawCarry = 0.0
        pitchCarry = 0.0
    }

    override fun onDisabled() {
        cancelPlan()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> { tick() }

    // --- one tick -----------------------------------------------------------------------------

    private fun tick() {
        val me = mc.player ?: return

        if (!passesPauses(me)) {
            cancelPlan()
            return
        }

        trackSafeSpot(me)

        // A solution in progress: keep aiming, and throw when the server has the rotation.
        val active = planned
        if (active != null) {
            advance(me, active)
            return
        }

        // Does this tick want to act at all? The chance is rolled once per episode, so a low value
        // actually means "sometimes it does not save me" instead of "it saves me one tick later".
        if (!wantsToAct(me)) {
            rollPassed = null
            return
        }

        if (rollPassed == null) {
            rollPassed = Random.nextInt(100) < chance
            if (rollPassed != true && debug) chat("§7[AutoPearl] §7chance roll failed for this episode")
        }
        if (rollPassed != true) return

        if (Util.getMillis() - lastThrow < cooldown) return
        if (Util.getMillis() - lastPlanFail < PLAN_RETRY) return
        if (!hasPearl()) return
        if (waitForPearl && pearlInFlight(me)) return

        val solution = when (mode) {
            Mode.SAVE -> solveSave(me)
            Mode.ENEMY -> solveEnemy(me)
        } ?: run {
            lastPlanFail = Util.getMillis()
            return
        }

        // No turning wanted: the throw happens right now, with the solved rotation (still put on the
        // mouse lattice, so it is a rotation a mouse could have produced) and with the same gate.
        if (!rotate) {
            val aim = humanize(currentRotation(me), solution.rotation, limitTurn = false)
            if (!landsWhereWanted(aim, solution.landing, tolerance())) return
            throwPearl(me, solution.copy(rotation = aim))
            return
        }

        planned = solution
        plannedTicks = aimTicks
        advance(me, solution)
    }

    /**
     * Turns towards the solution and throws as soon as the rotation the server sees is close
     * enough. Returns after consuming the tick: the throw is the last thing that happens.
     */
    private fun advance(me: LocalPlayer, solution: Solution) {
        if (plannedTicks-- <= 0) {
            if (debug) chat("§7[AutoPearl] §cgave up on ${solution.label} (aim timeout)")
            cancelPlan()
            return
        }

        // The target is taken from the plan, so it does not move between ticks: a new landing spot
        // every tick means a new rotation every tick and the aim never converges. In Enemy mode the
        // enemy is re-read, because that is the only thing that can move on purpose.
        val target = when (mode) {
            Mode.SAVE -> solution.landing
            Mode.ENEMY -> nearestEnemy(me)?.boundingBox?.center ?: run {
                if (debug) chat("§7[AutoPearl] §cthe target is gone")
                cancelPlan()
                return
            }
        }
        val tolerance = tolerance()
        val descending = mode == Mode.SAVE

        // The spawn point and the inherited fall velocity change every tick, so the arc still has to
        // be re-fitted — but locally, around a rotation that was already verified, not from scratch.
        val rotation = refineThrow(me, solution.rotation, target, tolerance, descending)
            ?: throwCandidates(me, target, descending, 1).firstOrNull()?.rotation
            ?: run {
                if (debug) chat("§7[AutoPearl] §cthe arc stopped reaching the target")
                cancelPlan()
                return
            }

        planned = solution.copy(rotation = rotation)

        val aim = humanize(currentRotation(me), rotation)
        handToManager(aim)

        // The aim gate is skipped on the last tick: at the ranges a pearl is thrown at, one or two
        // degrees is a handful of centimetres, while losing the throw means losing the round.
        if (plannedTicks > 0 && RotationManager.serverRotation.directionAngleTo(aim) > aimTolerance) return

        if (!landsWhereWanted(aim, target, tolerance)) return

        throwPearl(me, solution.copy(rotation = aim, landing = target))
    }

    /**
     * The last gate before a pearl is spent: LiquidBounce's collision-aware simulation has to end the
     * arc on a safe block (Save) or next to the enemy (Enemy). This is what keeps the module from
     * throwing pearls into the void when the ballistic fit looks right but a wall is in the way.
     */
    private fun landsWhereWanted(rotation: Rotation, target: Vec3, tolerance: Double): Boolean {
        if (!simulate) return true

        val hit = verify(rotation) ?: run {
            if (debug) chat("§7[AutoPearl] §cthe simulated arc hits nothing")
            return false
        }

        return when (mode) {
            Mode.SAVE -> isSafeLanding(hit) || run {
                if (debug) chat("§7[AutoPearl] §7would land at ${format(hit.location)}, not safe")
                false
            }

            Mode.ENEMY -> hit.location.distanceTo(target) <= tolerance || run {
                if (debug) {
                    chat(
                        "§7[AutoPearl] §7would land at ${format(hit.location)}, %.2f from the target"
                            .format(hit.location.distanceTo(target))
                    )
                }
                false
            }
        }
    }

    /** How close the pearl has to land, per mode. */
    private fun tolerance(): Double =
        if (mode == Mode.SAVE) landingTolerance.toDouble() else enemyTolerance.toDouble()

    // --- throwing -----------------------------------------------------------------------------

    private fun throwPearl(me: LocalPlayer, solution: Solution) {
        val slot = Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL)
        if (slot == null) {
            cancelPlan()
            return
        }

        if (waitForPearl && pearlInFlight(me)) {
            cancelPlan()
            return
        }

        // normalize() keeps the rotation on the mouse lattice but can push the yaw outside -180..180,
        // and a yaw beyond that range is exactly what Grim's AimModulo360 check reports.
        val normalized = solution.rotation.normalize()
        val yaw = normalizeYaw(normalized.yaw)
        val pitch = normalized.pitch

        SilentHotbar.selectSlotSilently(this, slot, slotReset)
        val result = useItem(slot.useHand, yaw, pitch, swing)

        // The cooldown starts either way: a refused use (no pearl client-side, or the server side
        // slot desynced) must not become a use every tick.
        lastThrow = Util.getMillis()
        cancelPlan()

        if (result !is InteractionResult.Success) {
            if (debug) chat("§7[AutoPearl] §cthe use was refused ($result)")
            return
        }

        thrown++

        if (chatLog) {
            chat(
                "§7[AutoPearl] §fthrown at §7${solution.label}§f, %.2f blocks away"
                    .format(solution.landing.distanceTo(me.position()))
            )
        }
        if (debug) {
            chat(
                "§7[AutoPearl] §fyaw=%.1f pitch=%.1f landing=%s".format(
                    yaw, pitch, format(solution.landing)
                )
            )
        }
    }

    private fun hasPearl(): Boolean = Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL) != null

    private fun pearlInFlight(me: LocalPlayer): Boolean = world.entitiesForRendering().any { entity ->
        entity is ThrownEnderpearl && entity.owner === me
    }

    // --- mode: save ---------------------------------------------------------------------------

    /**
     * The last place the player stood on solid ground, kept only while it is still standing and
     * still fresh. Being knocked off means being in the air, so the last *good* position is where
     * the platform was.
     */
    private fun trackSafeSpot(me: LocalPlayer) {
        if (!me.onGround() || me.isInWater || me.isInLava) return
        val below = me.blockPosition().below()
        if (!world.getBlockState(below).isFaceSturdy(world, below, Direction.UP, SupportType.CENTER)) return

        safeSpot = me.position()
        safeSpotAt = Util.getMillis()
    }

    /** Whether the current mode has something to do: a fall to save, or an enemy to pearl at. */
    private fun wantsToAct(me: LocalPlayer): Boolean = when (mode) {
        Mode.SAVE -> inDanger(me)
        Mode.ENEMY -> enemyTriggerOk(me) && nearestEnemy(me) != null
    }

    private fun enemyTriggerOk(me: LocalPlayer): Boolean =
        enemyTrigger == EnemyTrigger.ALWAYS || !me.onGround()

    /** Falling, and nothing but air below: the sumo situation. */
    private fun inDanger(me: LocalPlayer): Boolean {
        if (me.onGround()) return false
        if (me.airTicks < minAirTicks) return false
        if (me.fallDistance < minFall) return false

        val feet = me.blockPosition()
        for (depth in 1..voidScan) {
            val pos = feet.below(depth)
            val state = world.getBlockState(pos)
            if (!state.fluidState.isEmpty) return false
            if (state.isFaceSturdy(world, pos, Direction.UP, SupportType.CENTER)) return false
        }
        return true
    }

    private fun solveSave(me: LocalPlayer): Solution? {
        val candidates = landingCandidates(me)
        if (candidates.isEmpty()) {
            if (debug) chat("§7[AutoPearl] §cno safe landing found (no block tops around)")
            return null
        }

        // Solved one candidate at a time, stopping at the first arc the real simulation accepts: this
        // runs on the client thread while falling, so it must not solve all of them up front.
        var solved = 0
        var simulated = 0

        for (candidate in candidates) {
            if (solved >= MAX_SOLVED) break
            solved++

            // The arc has to come *down* onto the spot: one that passes through it while still
            // rising flies on and hits the side of the platform it was meant to land on. Two fits are
            // kept per spot, because the flat throw and the lob both reach it but only one may clear
            // the edge.
            val fits = throwCandidates(me, candidate, descending = true, limit = 2)
            if (fits.isEmpty()) continue

            if (!simulate) {
                if (debug) chat("§7[AutoPearl] §fplan §asave§f → ${format(candidate)}")
                return Solution(fits.first().rotation, "save", candidate)
            }

            simulated++
            for (fit in fits) {
                // the fit itself first, then small offsets: a cheap fit can pass centimetres from the
                // target and still clip the edge of the platform on the way
                for (rotation in variants(fit.rotation)) {
                    val hit = verify(rotation) ?: continue
                    if (!isSafeLanding(hit)) {
                        if (debug) chat("§7[AutoPearl] §7simulated landing at ${format(hit.location)} is not safe")
                        continue
                    }

                    if (debug) chat("§7[AutoPearl] §fplan §asave§f → ${format(hit.location)}")
                    return Solution(rotation, "save", hit.location)
                }
            }
        }

        if (debug) {
            val why = when {
                solved == 0 -> "no candidate could be solved"
                simulated > 0 -> "no arc survived the simulation"
                else -> "no arc reaches the available spots"
            }
            chat("§7[AutoPearl] §cno safe landing found ($why, ${solved}/${candidates.size} tried)")
        }
        return null
    }

    /**
     * Where the pearl should land, best first: the remembered safe spot, then solid block tops
     * around it (closest to the player first). A candidate is the point *above* a block, which is
     * where the pearl has to hit.
     */
    private fun landingCandidates(me: LocalPlayer): List<Vec3> {
        val result = ArrayList<Vec3>()

        val remembered = safeSpot
        if (remembered != null && Util.getMillis() - safeSpotAt <= safeMemory && isSafeSpot(remembered)) {
            result += remembered
        }

        // A sumo platform floats over the void, so the only thing that makes a spot safe is the
        // block itself: solid top, free space above it.
        val center = BlockPos.containing(remembered ?: me.position())
        val radius = safeSearch.toInt().coerceAtLeast(1)
        val around = ArrayList<Vec3>()

        for (pos in BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 2, radius))) {
            val top = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
            if (top.distanceTo(me.position()) < 0.8) continue
            if (!isSafeSpot(top)) continue
            around += top
        }

        around.sortBy { it.distanceToSqr(me.position()) }
        result += around.take(MAX_CANDIDATES)

        return result.distinct()
    }

    /**
     * A spot is usable when the block right under it has a solid top and the space it lands in is
     * free. That is all a floating sumo platform can offer.
     */
    private fun isSafeSpot(point: Vec3): Boolean {
        val block = BlockPos.containing(point.x, point.y - 0.05, point.z)
        val state = world.getBlockState(block)
        if (!state.isFaceSturdy(world, block, Direction.UP, SupportType.CENTER)) return false

        val aboveState = world.getBlockState(block.above())
        return aboveState.isAir || !aboveState.fluidState.isEmpty
    }

    /**
     * Whether a simulated impact is a landing at all. The face matters: a pearl that hits the side or
     * the underside of a block does not put you back on the platform, however solid that block is —
     * only the top face does, and that is exactly what the hit result says.
     */
    private fun isSafeLanding(hit: HitResult?): Boolean {
        if (hit !is BlockHitResult) return false
        if (hit.direction != Direction.UP) return false

        val block = hit.blockPos
        val state = world.getBlockState(block)
        if (!state.isFaceSturdy(world, block, Direction.UP, SupportType.CENTER)) return false

        val aboveState = world.getBlockState(block.above())
        return aboveState.isAir || !aboveState.fluidState.isEmpty
    }

    // --- mode: enemy --------------------------------------------------------------------------

    private fun solveEnemy(me: LocalPlayer): Solution? {
        val target = nearestEnemy(me)
        if (target == null) {
            if (debug) chat("§7[AutoPearl] §cno enemy in range")
            return null
        }

        val landing = target.boundingBox.center

        // Same fit as the save mode, so the fall velocity is taken into account here too. A pearl may
        // hit the enemy on the way up, so any part of the arc counts there.
        val fits = throwCandidates(me, landing, descending = false, limit = 2)
        if (fits.isEmpty()) {
            if (debug) chat("§7[AutoPearl] §cthe arc does not reach ${target.name.string}")
            return null
        }

        if (simulate) {
            for (fit in fits) {
                for (rotation in variants(fit.rotation)) {
                    val impact = verify(rotation) ?: continue
                    if (impact.location.distanceTo(landing) > enemyTolerance.toDouble()) continue

                    if (debug) chat("§7[AutoPearl] §fplan §cpearl §f→ §7${target.name.string}")
                    return Solution(rotation, target.name.string, landing)
                }
            }

            if (debug) chat("§7[AutoPearl] §cthe simulated arc misses ${target.name.string}")
            return null
        }

        if (debug) chat("§7[AutoPearl] §fplan §cpearl §f→ §7${target.name.string}")
        return Solution(fits.first().rotation, target.name.string, landing)
    }

    private fun nearestEnemy(me: LocalPlayer): LivingEntity? = world.entitiesForRendering()
        .asSequence()
        .filterIsInstance<LivingEntity>()
        .filter { it !== me && it.shouldBeAttacked() }
        .filter { it.squaredBoxedDistanceTo(me) <= enemyRange.toDouble() * enemyRange }
        .minByOrNull { it.squaredBoxedDistanceTo(me) }

    // --- ballistics: solved by sweeping the arc, not analytically -----------------------------

    /**
     * The velocity a pearl really leaves the hand with: the look direction times [PEARL_SPEED], plus
     * the player's own movement — and the vertical part of that only while airborne.
     *
     * This single line is what the closed-form projectiles solvers do not model, and it is why every
     * throw used to miss by a couple of blocks: while you are falling at ~1 block/tick, the pearl
     * inherits that fall, so the arc has to be aimed far higher than the geometry suggests.
     */
    private fun throwVelocity(me: LocalPlayer, rotation: Rotation): Vec3 {
        val look = rotation.directionVector.scale(PEARL_SPEED)
        val carriedY = if (me.onGround()) 0.0 else me.deltaMovement.y
        return Vec3(
            look.x + me.deltaMovement.x,
            look.y + carriedY,
            look.z + me.deltaMovement.z
        )
    }

    /** Where the pearl is spawned: the eye position, 0.1 lower, like the vanilla throw. */
    private fun throwStart(me: LocalPlayer): Vec3 = Vec3(me.x, me.eyeY - 0.1, me.z)

    /**
     * How close the collision-free arc of a pearl thrown with [rotation] passes to [point].
     *
     * The arc mirrors `TrajectoryInfoRenderer.runSimulation` tick for tick — one physics tick on the
     * velocity before the first move, then `pos += velocity` followed by `velocity *= 0.99` and
     * `velocity.y -= 0.03` — but without block collisions, because this is the evaluator the search
     * calls hundreds of times per tick. It is written with plain doubles on purpose: allocating a
     * list of positions here would show up in the frame time.
     *
     * Two things about the metric matter more than they look:
     *  - the distance is taken to the whole polyline, not to the point where the arc crosses the
     *    target's height: near the player that crossing happens within a tick or two and the error
     *    jumps around;
     *  - with [descending] only the part of the arc that is coming down counts. A pearl that passes
     *    through the spot while still rising does not land there — it flies on and hits the side of
     *    the platform it was supposed to land on.
     */
    private fun arcDistance(me: LocalPlayer, rotation: Rotation, point: Vec3, descending: Boolean): Double {
        val start = throwStart(me)
        val initial = throwVelocity(me, rotation)

        // initial tick correction: TrajectoryType.EnderPearl applies one before the first move
        var vx = initial.x * PROJECTILE_DRAG
        var vy = initial.y * PROJECTILE_DRAG - PROJECTILE_GRAVITY
        var vz = initial.z * PROJECTILE_DRAG

        var px = start.x
        var py = start.y
        var pz = start.z

        var best = Double.MAX_VALUE

        repeat(MAX_SIM_TICKS) {
            val nx = px + vx
            val ny = py + vy
            val nz = pz + vz

            // distance from the point to the segment (p → n)
            if (!descending || ny < py) {
                val abx = nx - px
                val aby = ny - py
                val abz = nz - pz
                val lengthSqr = abx * abx + aby * aby + abz * abz

                val along = (point.x - px) * abx + (point.y - py) * aby + (point.z - pz) * abz
                val t = if (lengthSqr < 1.0e-12) 0.0 else (along / lengthSqr).coerceIn(0.0, 1.0)

                val dx = px + abx * t - point.x
                val dy = py + aby * t - point.y
                val dz = pz + abz * t - point.z
                val distance = sqrt(dx * dx + dy * dy + dz * dz)
                if (distance < best) best = distance
            }

            px = nx
            py = ny
            pz = nz

            vx *= PROJECTILE_DRAG
            vy = vy * PROJECTILE_DRAG - PROJECTILE_GRAVITY
            vz *= PROJECTILE_DRAG

            if (py < start.y - ARC_DROP_LIMIT) return best
        }

        return best
    }

    /** A throw rotation, and how far its collision-free arc gets from the point it was fitted to. */
    private data class Throw(val rotation: Rotation, val error: Double)

    /**
     * The rotations whose arc passes closest to [point], best first, at most [limit] of them.
     *
     * Neither the yaw nor the pitch can be computed geometrically: the pearl inherits the player's
     * movement, so a throw made while falling is dragged down and sideways and the arc that reaches
     * the target is nowhere near the one that points at it. So the pitch is swept over its whole
     * range at the geometric yaw and the local minima are refined (pitch, then yaw, then pitch
     * again).
     *
     * More than one minimum is kept because this problem usually has several solutions — a flat
     * throw and a lob reach the same spot — and the collision-free arc cannot tell which one clears
     * what is in between. That is a question for the simulation, so it gets a handful of candidates
     * instead of a single answer.
     */
    private fun throwCandidates(
        me: LocalPlayer,
        point: Vec3,
        descending: Boolean,
        limit: Int
    ): List<Throw> {
        val geometric = Rotation.lookingAt(point, me.eyePosition)

        fun errorAt(yaw: Float, pitch: Float) = arcDistance(me, Rotation(yaw, pitch), point, descending)

        val count = ((89f - (-89f)) / COARSE_STEP).toInt() + 1
        val pitches = FloatArray(count) { -89f + it * COARSE_STEP }
        val errors = DoubleArray(count) { errorAt(geometric.yaw, pitches[it]) }

        val minima = ArrayList<Int>()
        for (index in 1 until count - 1) {
            if (errors[index] <= errors[index - 1] && errors[index] <= errors[index + 1]) minima += index
        }
        minima.sortBy { errors[it] }

        val result = ArrayList<Throw>()

        for (index in minima) {
            if (result.size >= limit) break

            var yaw = geometric.yaw
            var pitch = pitches[index]
            var best = errors[index]

            var candidate = pitch - REFINE_SPAN
            while (candidate <= pitch + REFINE_SPAN) {
                val error = errorAt(yaw, candidate)
                if (error < best) {
                    best = error
                    pitch = candidate
                }
                candidate += FINE_STEP
            }

            candidate = yaw - YAW_SPAN
            while (candidate <= yaw + YAW_SPAN) {
                val error = errorAt(candidate, pitch)
                if (error < best) {
                    best = error
                    yaw = candidate
                }
                candidate += COARSE_STEP
            }

            candidate = pitch - REFINE_SPAN
            while (candidate <= pitch + REFINE_SPAN) {
                val error = errorAt(yaw, candidate)
                if (error < best) {
                    best = error
                    pitch = candidate
                }
                candidate += FINE_STEP
            }

            if (best > MAX_FIT_ERROR) continue
            result += Throw(Rotation(normalizeYaw(yaw), pitch.coerceIn(-89f, 89f)), best)
        }

        result.sortBy { it.error }
        return result
    }

    /**
     * Re-fits [start] against a target that has not moved. The fall changes the required pitch by a
     * few degrees per tick, so a small window around the previous answer is enough — and it is what
     * makes the aim converge, because the answer does not jump around between ticks.
     */
    private fun refineThrow(
        me: LocalPlayer,
        start: Rotation,
        point: Vec3,
        tolerance: Double,
        descending: Boolean
    ): Rotation? {
        fun errorAt(yaw: Float, pitch: Float) = arcDistance(me, Rotation(yaw, pitch), point, descending)

        var yaw = start.yaw
        var pitch = start.pitch
        var best = errorAt(yaw, pitch)

        var candidate = pitch - REFINE_SPAN
        while (candidate <= pitch + REFINE_SPAN) {
            val error = errorAt(yaw, candidate)
            if (error < best) {
                best = error
                pitch = candidate
            }
            candidate += FINE_STEP
        }

        candidate = yaw - YAW_TWEAK
        while (candidate <= yaw + YAW_TWEAK) {
            val error = errorAt(candidate, pitch)
            if (error < best) {
                best = error
                yaw = candidate
            }
            candidate += FINE_STEP
        }

        if (best > tolerance) return null
        return Rotation(normalizeYaw(yaw), pitch.coerceIn(-89f, 89f))
    }

    /**
     * The rotations worth checking against the real simulation, nearest to [fit] first: the fit
     * itself, then small offsets in pitch and yaw. Only the collision-aware simulation knows whether
     * an arc that passes right through the target also clears whatever is in front of it.
     */
    private fun variants(fit: Rotation): List<Rotation> {
        val result = ArrayList<Rotation>()
        result += fit

        for (step in NUDGE_STEPS) {
            val up = (fit.pitch + step).coerceIn(-89f, 89f)
            val down = (fit.pitch - step).coerceIn(-89f, 89f)
            result += Rotation(normalizeYaw(fit.yaw), up)
            result += Rotation(normalizeYaw(fit.yaw), down)
            result += Rotation(normalizeYaw(fit.yaw + step), fit.pitch)
            result += Rotation(normalizeYaw(fit.yaw - step), fit.pitch)
        }

        return result
    }

    /** Brings a yaw back into -180..180, where every other rotation in the module lives. */
    private fun normalizeYaw(yaw: Float): Float {
        var wrapped = yaw % 360f
        if (wrapped <= -180f) wrapped += 360f
        if (wrapped > 180f) wrapped -= 360f
        return wrapped
    }

    /**
     * Runs LiquidBounce's own simulation (block collisions included) and returns what the pearl hits,
     * or null when it hits nothing. The hit result matters, not just its position: whether the pearl
     * landed on top of a block or on its side is the difference between being saved and not.
     */
    private fun verify(rotation: Rotation): HitResult? {
        val hit = TrajectoryInfoRenderer.getHypotheticalTrajectory(
            simulationOwner = player,
            trajectoryInfo = TrajectoryInfo.GENERIC,
            trajectoryType = TrajectoryType.EnderPearl,
            rotation = rotation,
            // keep the start position identical to [throwStart], no partial-tick interpolation
            partialTicks = 0f
        ).runSimulation(MAX_SIM_TICKS).hitResult ?: return null

        if (hit.type == HitResult.Type.MISS) return null
        return hit
    }

    /** The rotation the aim is at: what the server sees when silent, the camera otherwise. */
    private fun currentRotation(me: LocalPlayer): Rotation =
        if (silent) RotationManager.serverRotation else Rotation(me.yRot, me.xRot)

    /**
     * Steps [desired] towards the player one tick at a time ([maxTurn] degrees), then snaps it to
     * whole mouse counts, carrying the rounding residual — the same two rules the Aimassist uses, so
     * a pearl throw is never a rotation the mouse could not have produced.
     */
    private fun humanize(from: Rotation, desired: Rotation, limitTurn: Boolean = true): Rotation {
        var next = desired

        if (limitTurn && maxTurn > 0f) {
            val deltaYaw = RotationUtil.angleDifference(desired.yaw, from.yaw).coerceIn(-maxTurn, maxTurn)
            val deltaPitch = (desired.pitch - from.pitch).coerceIn(-maxTurn, maxTurn)
            next = Rotation(
                normalizeYaw(from.yaw + deltaYaw),
                (from.pitch + deltaPitch).coerceIn(-89.9f, 89.9f)
            )
        }

        return if (gcdFix) quantizeToMouseCounts(from, next) else next
    }

    private fun quantizeToMouseCounts(from: Rotation, desired: Rotation): Rotation {
        val step = RotationUtil.gcd
        if (step < 1.0e-6) return desired

        val deltaYaw = RotationUtil.angleDifference(desired.yaw, from.yaw).toDouble()
        val deltaPitch = (desired.pitch - from.pitch).toDouble()

        val rawYaw = deltaYaw / step + (if (gcdDither) yawCarry else 0.0)
        val rawPitch = deltaPitch / step + (if (gcdDither) pitchCarry else 0.0)

        val countsYaw: Int
        val countsPitch: Int

        if (gcdDither) {
            countsYaw = handRound(rawYaw)
            countsPitch = handRound(rawPitch)
            yawCarry = (rawYaw - countsYaw).coerceIn(-0.95, 0.95)
            pitchCarry = (rawPitch - countsPitch).coerceIn(-0.95, 0.95)
        } else {
            countsYaw = round(rawYaw).toInt()
            countsPitch = round(rawPitch).toInt()
        }

        // counted from the current rotation, so the result can overflow the -180..180 range: wrapping
        // it changes nothing about where the crosshair points, but keeps it a legal rotation
        val yaw = normalizeYaw(from.yaw + (countsYaw * step).toFloat())
        val pitch = (from.pitch + (countsPitch * step).toFloat()).coerceIn(-89.9f, 89.9f)
        return Rotation(yaw, pitch, isNormalized = true)
    }

    private fun handRound(counts: Double): Int {
        val wobble = Random.nextDouble(-DITHER_WOBBLE, DITHER_WOBBLE)
        return kotlin.math.floor(counts + 0.5 + wobble).toInt()
    }

    /**
     * The plan handed to LiquidBounce's [RotationManager]. Processors are intentionally empty: the
     * rotation is already shaped and quantized here, and an angle smooth on top of it would move the
     * throw off the lattice.
     */
    private fun handToManager(rotation: Rotation) {
        RotationManager.setRotationTarget(
            RotationTarget(
                rotation = rotation,
                entity = null,
                ticksUntilReset = slotReset + 1,
                resetThreshold = 1f,
                considerInventory = pauseOnScreen,
                movementCorrection = when {
                    !silent -> MovementCorrection.CHANGE_LOOK
                    moveFix == MoveFix.OFF -> MovementCorrection.OFF
                    moveFix == MoveFix.STRICT -> MovementCorrection.STRICT
                    else -> MovementCorrection.SILENT
                }
            ),
            RotationPriority.IMPORTANT_FOR_USAGE_3,
            this
        )
    }

    // --- gating / plumbing --------------------------------------------------------------------

    private fun passesPauses(me: LocalPlayer): Boolean {
        if (pauseOnScreen && mc.gui.screen() != null) return false
        if (pauseOnUse && me.isUsingItem) return false
        if (pauseInVehicle && me.vehicle != null) return false
        return true
    }

    private fun cancelPlan() {
        planned = null
        plannedTicks = 0
    }

    private fun format(point: Vec3): String =
        "(%.2f, %.2f, %.2f)".format(point.x, point.y, point.z)
}
