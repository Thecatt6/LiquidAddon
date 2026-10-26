package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.autoconfig.AutoConfig
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoClicker
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.item.WeaponType
import net.ccbluex.liquidbounce.utils.kotlin.matchesAny
import net.ccbluex.liquidbounce.utils.raytracing.findEntityInCrosshair
import net.ccbluex.liquidbounce.utils.raytracing.hasLineOfSight
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.LivingEntity
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * TriggerBotPlus — a TriggerBot that can fire **before** the enemy is inside your attack range.
 *
 * A normal triggerbot (LiquidBounce's AutoClicker) only clicks once `mc.hitResult` is the enemy,
 * i.e. once the enemy is already inside the vanilla interaction range — by then the tick is gone
 * and, with any latency, the hit is late. This module owns its own crosshair ray
 * ([findEntityInCrosshair]) with a **longer** reach, so it can already see the enemy while they are
 * still walking towards you, and it clicks in the tick in which they will be reachable on the
 * server.
 *
 * The early part is deliberately not a constant:
 *
 *  - [early] is a slider **range** (two handles, `min..max`). Every encounter with a target draws
 *    its own cushion inside that range ([beginEncounter]), so two fights never have the same
 *    trigger point;
 *  - [earlyChance] decides, again per encounter, whether that cushion is used at all. With `100`
 *    the module always fires early, with `0` it is a plain triggerbot and only [range] counts;
 *  - [reaction] adds a random hesitation at the start of the encounter (a hand does not react on
 *    the first tick) and [clickDelay] a random pause between two clicks;
 *  - [predict] subtracts the distance the enemy will have covered in [predictTicks] ticks, but only
 *    the part of their movement that comes **towards** you: an enemy running away is never clicked
 *    earlier than [range].
 *
 * Trigger distance = [range] (or the real reach when [matchVanilla] is on) + the random cushion.
 * The click itself is LiquidBounce's `attackEntity`, gated by the item cooldown ([minCooldown]) so
 * the module can never click faster than the weapon allows, and the camera is never touched: this
 * module does not aim, it only decides *when* to swing.
 *
 * **The click is still validated by the server.** Anything beyond the server-side reach is thrown
 * away there, and the client has already reset the attack cooldown — that is why [early] should be
 * kept small, and why [chatLog] prints the distance and the cushion of every pre-trigger, so a
 * value that is too greedy is visible instead of being guessed. Combining it with `Reach`
 * (`Entity.RangeIncrease`) is the way to make the extra distance count.
 *
 * Settings:
 *   Preset            ready-made configs (Custom = the sliders below)
 *   Range             base trigger distance, used while the enemy is still outside real reach
 *   MatchVanilla      use the weapon's real attack range (attribute + item component) as base
 *   Early             slider range `min..max` of the random cushion, in blocks
 *   Fov               0 = the enemy must be under the crosshair ray, >0 = a cone around it
 *   EarlyChance       chance (%) that an encounter uses a cushion at all
 *   Predict           lead the enemy's movement
 *   PredictTicks      how many ticks ahead the distance is evaluated
 *   Reaction          random hesitation at the start of an encounter, in ticks
 *   MinCooldown       minimum attack-strength before a click, 0..1
 *   ClickDelay        random pause between two clicks, in ticks
 *   HoldAttack        only click while the attack key is held (triggerbot behaviour)
 *   ThroughWalls      allow the pre-trigger without line of sight
 *   Swing             swing mode
 *   KeepSprint        keep sprint on the attack (extra knockback)
 *   Weapon            which held items may attack
 *   PauseOnUse        do not click while using an item
 *   PauseOnScreen     do not click while a screen is open
 *   PauseWithAutoClicker  stand down while LiquidBounce's AutoClicker is enabled
 *   ChatLog           print every pre-trigger
 *   Debug             print every trigger
 */
object ModuleTriggerBotPlus : ClientModule(
    "TriggerBotPlus",
    Categories.LIQUIDADDON,
    aliases = listOf("EarlyTrigger", "TriggerPlus")
) {

    /**
     * Hard cap on the movement lead, in blocks. A teleporting / lagging enemy must not make the
     * module swing from the other side of the map.
     */
    private const val MAX_LEAD = 1.0

    /** Slack added to the acquisition ray, so the hitbox edge is never cut by a rounding error. */
    private const val RAY_SLACK = 0.1

    /**
     * How long a lost target keeps its drawn cushion. The crosses of a mouse sitting on the edge of
     * a hitbox are not stable for a single tick; without this grace every flicker would re-roll the
     * random cushion and turn [earlyChance] into "always early".
     */
    private const val LOST_GRACE = 8

    /** The values a [Preset] writes; `null` for [Preset.CUSTOM], which leaves the sliders alone. */
    data class Profile(
        val range: Float,
        val matchVanilla: Boolean,
        val early: ClosedFloatingPointRange<Float>,
        val earlyChance: Int,
        val predict: Boolean,
        val predictTicks: Int,
        val reaction: IntRange,
        val minCooldown: Float,
        val clickDelay: IntRange,
        val throughWalls: Boolean,
        val keepSprint: Boolean
    )

    enum class Preset(override val tag: String, val profile: Profile?) : Tagged {
        CUSTOM("Custom", null),

        /** Barely anything to see: real reach, a very small cushion, slow reaction. */
        LEGIT(
            "Legit",
            Profile(
                range = 3.0f, matchVanilla = true, early = 0.10f..0.30f, earlyChance = 40,
                predict = true, predictTicks = 1, reaction = 0..1, minCooldown = 1.0f,
                clickDelay = 0..1, throughWalls = false, keepSprint = false
            )
        ),

        /** The sweet spot for normal servers. */
        CLOSET(
            "Closet",
            Profile(
                range = 3.0f, matchVanilla = true, early = 0.20f..0.55f, earlyChance = 75,
                predict = true, predictTicks = 2, reaction = 0..0, minCooldown = 1.0f,
                clickDelay = 0..0, throughWalls = false, keepSprint = false
            )
        ),

        /** Always early, wider cushion, through walls. Only for testing on your own world. */
        AGGRESSIVE(
            "Aggressive",
            Profile(
                range = 3.2f, matchVanilla = false, early = 0.35f..0.90f, earlyChance = 100,
                predict = true, predictTicks = 2, reaction = 0..0, minCooldown = 0.90f,
                clickDelay = 0..0, throughWalls = true, keepSprint = false
            )
        )
    }

    // --- preset -------------------------------------------------------------------------------

    private val presetValue = enumChoice("Preset", Preset.CUSTOM)

    // --- trigger distance ---------------------------------------------------------------------

    /** Base trigger distance. Ignored while [matchVanilla] is on. */
    private val rangeValue = float("Range", 3.0f, 1.0f..6.0f, "blocks")
    private val range by rangeValue

    /**
     * When on, the base is the weapon's real attack range: `ENTITY_INTERACTION_RANGE` (which already
     * contains the `Reach` module) plus the item's own range component, which is why a spear has a
     * longer one.
     */
    private val matchVanillaValue = boolean("MatchVanilla", false)
    private val matchVanilla by matchVanillaValue

    /** The random cushion: every encounter draws one value between these two handles. */
    private val earlyValue = floatRange("Early", 0.15f..0.45f, 0.0f..2.0f, "blocks")
    private val early by earlyValue

    /** Chance (%) that an encounter draws a cushion at all. 0 = never early, 100 = always. */
    private val earlyChanceValue = int("EarlyChance", 65, 0..100, "%")
    private val earlyChance by earlyChanceValue

    /**
     * 0 keeps the strict triggerbot behaviour: only the enemy the crosshair ray actually crosses is
     * a target. Anything above widens the acquisition to a cone of that many degrees around the
     * crosshair, so an enemy brushing the edge of the hitbox can also be pre-triggered.
     */
    private val fovValue = float("Fov", 0f, 0f..30f, "°")
    private val fov by fovValue

    // --- timing / humanisation ----------------------------------------------------------------

    private val predictValue = boolean("Predict", true)
    private val predict by predictValue

    /** How many ticks ahead the enemy's movement is evaluated. */
    private val predictTicksValue = int("PredictTicks", 1, 0..3, "ticks")
    private val predictTicks by predictTicksValue

    /** Random hesitation before a freshly acquired target may trigger. */
    private val reactionValue = intRange("Reaction", 0..1, 0..5, "ticks")
    private val reaction by reactionValue

    /**
     * Minimum attack-strength (0..1) before a click. 1 = only with a fully charged weapon, which is
     * also what LiquidBounce's Clicker does; lower values click sooner and hit softer.
     */
    private val minCooldownValue = float("MinCooldown", 1.0f, 0.0f..1.0f)
    private val minCooldown by minCooldownValue

    /** Random pause between two clicks, in ticks. Keeps the click rate from being tick-perfect. */
    private val clickDelayValue = intRange("ClickDelay", 0..1, 0..5, "ticks")
    private val clickDelayRange by clickDelayValue

    // --- behaviour ----------------------------------------------------------------------------

    private val holdAttackValue = boolean("HoldAttack", true)
    private val holdAttack by holdAttackValue
    private val throughWallsValue = boolean("ThroughWalls", false)
    private val throughWalls by throughWallsValue
    private val swing by enumChoice("Swing", SwingMode.DO_NOT_HIDE)
    private val keepSprintValue = boolean("KeepSprint", false)
    private val keepSprint by keepSprintValue

    /**
     * Do not click while the target is still in its hurt animation: vanilla ignores that hit, but
     * the attack cooldown is reset anyway, so it would silently eat the next real hit.
     */
    private val skipInvulnerableValue = boolean("SkipInvulnerable", true)
    private val skipInvulnerable by skipInvulnerableValue
    private val weapon by multiEnumChoice("Weapon", WeaponType.ANY, canBeNone = false)

    private val pauseOnUse by boolean("PauseOnUse", true)
    private val pauseOnScreen by boolean("PauseOnScreen", true)
    private val pauseWithAutoClicker by boolean("PauseWithAutoClicker", true)

    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

    // --- state --------------------------------------------------------------------------------

    /** The target of the current encounter. A new one re-draws the random cushion. */
    private var trackedTarget: LivingEntity? = null

    /** True when this encounter uses a pre-trigger at all ([earlyChance]). */
    private var earlyArmed = false

    /** The cushion drawn for this encounter, 0 while [earlyArmed] is false. */
    private var earlyMargin = 0.0

    private var reactionTicks = 0
    private var clickDelay = 0

    /** Ticks without a target under the crosshair, used by [LOST_GRACE]. */
    private var lostTicks = 0

    private var triggers = 0
    private var preTriggers = 0
    private var lastMargin = 0.0

    /** HUD tag: the cushion of the last pre-trigger, so the randomness is visible in game. */
    override val tag: String
        get() = if (preTriggers == 0) "" else "+%.2f".format(lastMargin)

    init {
        // A preset rewrites the sliders, so the GUI always shows what is really used. Nothing is
        // applied while the config loads, otherwise a restored preset would overwrite the sliders
        // restored right next to it.
        presetValue.onChanged { if (!AutoConfig.loadingNow) applyPreset(it) }
    }

    override fun onEnabled() {
        resetEncounter()
        triggers = 0
        preTriggers = 0
        lastMargin = 0.0
        clickDelay = 0

        if (pauseWithAutoClicker && ModuleAutoClicker.enabled) {
            chat(
                "§7[TriggerBotPlus] §cAutoClicker is enabled — this module stays paused. " +
                    "Disable it (or PauseWithAutoClicker) to let TriggerBotPlus click."
            )
        }
    }

    override fun onDisabled() {
        resetEncounter()
        clickDelay = 0
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> { tick() }

    // --- one tick -----------------------------------------------------------------------------

    private fun tick() {
        val me = mc.player ?: return

        if (!passesPauses(me)) {
            resetEncounter()
            return
        }

        if (clickDelay > 0) {
            clickDelay--
            return
        }

        // Acquisition uses the widest distance the module can ever trigger at, otherwise an
        // encounter whose cushion was not drawn would be lost on every tick.
        val acquisitionRange = baseDistance(me) + early.endInclusive + RAY_SLACK
        val target = findTarget(me, acquisitionRange)
        if (target == null) {
            if (++lostTicks > LOST_GRACE) {
                resetEncounter()
            }
            return
        }

        lostTicks = 0
        if (target !== trackedTarget) {
            beginEncounter(target)
        }

        // Hesitation of a fresh encounter, spent before anything else is looked at.
        if (reactionTicks > 0) {
            reactionTicks--
            return
        }

        if (!throughWalls && !hasLineOfSight(me.eyePosition, target.boundingBox.center, me)) return

        val distance = triggerDistance(me, target)
        val base = baseDistance(me)
        if (distance > base + earlyMargin) return

        if (!weapon.matchesAny(me.mainHandItem)) return
        if (skipInvulnerable && target.hurtTime > 0) return
        if (!isCooldownReady(me)) return

        val isEarly = distance > base

        if (!attackEntity(target, swing, keepSprint)) {
            // Cancelled (e.g. by HitSelect) — try again next tick instead of spinning on it.
            clickDelay = 1
            return
        }

        triggers++
        if (isEarly) {
            preTriggers++
            lastMargin = earlyMargin
        }
        clickDelay = nextIn(clickDelayRange)

        report(me, target, distance, base, isEarly)
    }

    // --- target acquisition -------------------------------------------------------------------

    /**
     * The enemy the module may fight with. With [fov] at 0 this is exactly the crosshair ray of a
     * triggerbot, only longer; above 0 the ray is replaced by a cone around the crosshair and the
     * closest enemy inside it wins.
     */
    private fun findTarget(me: LocalPlayer, range: Double): LivingEntity? {
        if (fov <= 0f) {
            val hit = findEntityInCrosshair(range, Rotation(me.yRot, me.xRot)) { entity ->
                entity is LivingEntity && entity.shouldBeAttacked()
            }
            return hit?.entity as? LivingEntity
        }

        return world.entitiesForRendering()
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it !== me && it.shouldBeAttacked() }
            .filter { sqrt(it.squaredBoxedDistanceTo(me)) <= range }
            .filter { RotationUtil.crosshairAngleToEntity(it) <= fov }
            .minByOrNull { it.squaredBoxedDistanceTo(me) }
    }

    // --- gating -------------------------------------------------------------------------------

    private fun passesPauses(me: LocalPlayer): Boolean {
        // In this Minecraft version the screen lives on the Gui, not on Minecraft itself.
        if (pauseOnScreen && mc.gui.screen() != null) return false
        if (pauseOnUse && me.isUsingItem) return false
        if (interaction.isDestroying) return false
        if (pauseWithAutoClicker && ModuleAutoClicker.enabled) return false
        if (holdAttack && !mc.options.keyAttack.isPressedOnAny) return false
        return true
    }

    /** The attack cooldown, as a share of a fully charged weapon. */
    private fun isCooldownReady(me: LocalPlayer): Boolean {
        if (minCooldown <= 0f) return true
        return me.getAttackStrengthScale(0.5f) >= minCooldown
    }

    // --- distance -----------------------------------------------------------------------------

    /** The distance the click is compared against: [range], or the weapon's real reach. */
    private fun baseDistance(me: LocalPlayer): Double =
        if (matchVanilla) me.entityInteractionRange() else range.toDouble()

    /**
     * Distance from the player's eyes to the hitbox, minus the part of the relative movement that
     * closes it within [predictTicks] ticks. Only the closing part counts: an enemy running away is
     * never clicked earlier than the base distance.
     */
    private fun triggerDistance(me: LocalPlayer, target: LivingEntity): Double {
        val raw = sqrt(target.squaredBoxedDistanceTo(me))
        if (!predict || predictTicks <= 0) return raw

        val toTarget = target.boundingBox.center.subtract(me.eyePosition)
        if (toTarget.lengthSqr() < 1.0e-5) return raw

        val relative = target.deltaMovement.subtract(me.deltaMovement)
        val closing = -relative.dot(toTarget.normalize()) // + while the enemy comes towards us
        val lead = (closing * predictTicks).coerceIn(0.0, MAX_LEAD)

        return (raw - lead).coerceAtLeast(0.0)
    }

    // --- encounter ----------------------------------------------------------------------------

    /**
     * Starts a new encounter: draws the random cushion of this fight and the random hesitation.
     * Called on every target switch, and after a tick without any target under the crosshair.
     */
    private fun beginEncounter(target: LivingEntity) {
        trackedTarget = target
        earlyArmed = Random.nextInt(100) < earlyChance
        earlyMargin = if (earlyArmed) nextMargin() else 0.0
        reactionTicks = nextIn(reaction)

        if (debug) {
            chat(
                "§7[TriggerBotPlus] §fnew target §7${target.name.string}§f, " +
                    if (earlyArmed) "+%.2f blocks early".format(earlyMargin) else "no cushion this time"
            )
        }
    }

    private fun resetEncounter() {
        trackedTarget = null
        earlyArmed = false
        earlyMargin = 0.0
        reactionTicks = 0
        lostTicks = 0
    }

    private fun nextMargin(): Double {
        val min = early.start.toDouble()
        val max = early.endInclusive.toDouble()
        return if (max <= min) min else Random.nextDouble(min, max)
    }

    private fun nextIn(range: IntRange): Int =
        if (range.last <= range.first) range.first else Random.nextInt(range.first, range.last + 1)

    // --- preset / feedback --------------------------------------------------------------------

    private fun applyPreset(preset: Preset) {
        val p = preset.profile ?: return

        rangeValue.set(p.range)
        matchVanillaValue.set(p.matchVanilla)
        earlyValue.set(p.early)
        earlyChanceValue.set(p.earlyChance)
        predictValue.set(p.predict)
        predictTicksValue.set(p.predictTicks)
        reactionValue.set(p.reaction)
        minCooldownValue.set(p.minCooldown)
        clickDelayValue.set(p.clickDelay)
        throughWallsValue.set(p.throughWalls)
        keepSprintValue.set(p.keepSprint)

        if (debug || chatLog) {
            chat("§7[TriggerBotPlus] §fpreset §7${preset.tag}§f applied")
        }
    }

    private fun report(me: LocalPlayer, target: LivingEntity, distance: Double, base: Double, isEarly: Boolean) {
        if (chatLog && isEarly) {
            chat(
                "§7[TriggerBotPlus] §fpre-trigger §7${target.name.string} §fat %.2f§7 blocks (+%.2f on %.2f)"
                    .format(distance, earlyMargin, base)
            )
            val reach = me.entityInteractionRange()
            if (distance > reach) {
                chat(
                    "§7[TriggerBotPlus] §c%.2f > real reach %.2f — the server may reject this hit"
                        .format(distance, reach)
                )
            }
        }

        if (debug) {
            chat(
                "§7[TriggerBotPlus] §ftrigger #$triggers §7(pre $preTriggers) §fdist=%.2f base=%.2f cushion=%.2f%s"
                    .format(distance, base, earlyMargin, if (isEarly) " §6early" else "")
            )
        }
    }
}
