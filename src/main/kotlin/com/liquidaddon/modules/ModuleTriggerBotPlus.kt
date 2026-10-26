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
import net.minecraft.util.Util
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
 * ### Reach, and why anticheats flag pre-triggers
 *
 * A pre-trigger is a click on an enemy that is **not in range yet**, so a server that validates the
 * distance of an attack (Grim's `Reach` check, for example) flags it — that is the feature working,
 * not a bug in it. Two consequences:
 *
 *  - [serverSafe] (default on) keeps the randomness but keeps it **inside the legal window**
 *    `base .. reach + [overReach] - SafetyGap`, and the cushion decides where inside it the click
 *    lands. [overReach] is the only part of a pre-trigger a server lets through, because your own
 *    latency hands it back — the enemy keeps walking while the packet travels. `0` = a strictly
 *    legal triggerbot that never fires past the reach; `0.15..0.25` = a real early click that most
 *    servers still accept for a target that is closing;
 *  - with [serverSafe] off the module does what the name says — clicks while the enemy is still
 *    outside the reach. That is what a Grim `Reach` flag is made of. Every pre-trigger of this kind
 *    is validated server-side, so a rejected one still burns the client attack cooldown.
 *
 * The distance is measured exactly like the server does it ([serverDistance]): from the eyes to the
 * hitbox, **without** the client pick-radius slack. Using the usual boxed distance (which inflates
 * the hitbox by 0.1) is enough on its own to push a "3.0 blocks" click over the limit.
 *
 * Settings:
 *   Preset            ready-made configs (Custom = the sliders below)
 *   Range             base trigger distance, used while the enemy is still outside real reach
 *   MatchVanilla      use the weapon's real attack range (attribute + item component) as base
 *   Early             slider range `min..max` of the random cushion, in blocks
 *   CPS               clicks per second as a slider range, re-drawn after every click
 *   CpsJitter         extra random delay on top of the CPS interval, in %
 *   Fov               0 = the enemy must be under the crosshair ray, >0 = a cone around it
 *   EarlyChance       chance (%) that an encounter uses a cushion at all
 *   ServerSafe        never click beyond the reach the server measures (default on)
 *   SafetyGap         how much of the reach is left unused while ServerSafe is on
 *   OverReach         how far past the reach the cushion may push (the real "early" knob)
 *   CPS / CpsJitter   click rate as a slider range, plus a random stretch per click
 *   MinCooldown       minimum attack strength, so the rate never nerfs the damage
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

    /** Never trigger closer than this, a cushion larger than the reach would make no sense. */
    private const val MIN_TRIGGER = 0.4

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
        val cps: IntRange,
        val clickDelay: IntRange,
        val throughWalls: Boolean,
        val keepSprint: Boolean,
        val serverSafe: Boolean = true,
        val safetyGap: Float = 0.05f,
        val overReach: Float = 0.10f
    )

    enum class Preset(override val tag: String, val profile: Profile?) : Tagged {
        CUSTOM("Custom", null),

        /** Barely anything to see: real reach, a very small cushion, slow reaction. */
        LEGIT(
            "Legit",
            Profile(
                range = 3.0f, matchVanilla = true, early = 0.10f..0.30f, earlyChance = 40,
                predict = true, predictTicks = 1, reaction = 0..1, minCooldown = 1.0f,
                cps = 8..11, clickDelay = 0..1, throughWalls = false, keepSprint = false,
                serverSafe = true, safetyGap = 0.05f, overReach = 0.05f
            )
        ),

        /** The sweet spot for normal servers. */
        CLOSET(
            "Closet",
            Profile(
                range = 3.0f, matchVanilla = true, early = 0.20f..0.55f, earlyChance = 75,
                predict = true, predictTicks = 2, reaction = 0..0, minCooldown = 1.0f,
                cps = 10..14, clickDelay = 0..0, throughWalls = false, keepSprint = false,
                serverSafe = true, safetyGap = 0.05f, overReach = 0.10f
            )
        ),

        /** Always early, wider cushion, through walls. Only for testing on your own world. */
        AGGRESSIVE(
            "Aggressive",
            Profile(
                range = 3.2f, matchVanilla = false, early = 0.35f..0.90f, earlyChance = 100,
                predict = true, predictTicks = 2, reaction = 0..0, minCooldown = 0.90f,
                cps = 14..20, clickDelay = 0..0, throughWalls = true, keepSprint = false,
                serverSafe = false, safetyGap = 0.05f, overReach = 0.0f
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
    private val matchVanillaValue = boolean("MatchVanilla", true)
    private val matchVanilla by matchVanillaValue

    /** The random cushion: every encounter draws one value between these two handles. */
    private val earlyValue = floatRange("Early", 0.05f..0.25f, 0.0f..2.0f, "blocks")
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

    /**
     * Keep the pre-trigger inside the window the server still accepts: the click lands between the
     * base distance and `reach + [overReach] - [safetyGap]`, with the cushion deciding where in that
     * window, so the randomness survives instead of every encounter piling up on the ceiling.
     *
     * Turning it off adds the cushion as it is drawn, past the reach: the raw "click before the
     * enemy is in range" behaviour, and what Grim's Reach check flags.
     */
    private val serverSafeValue = boolean("ServerSafe", true)
    private val serverSafe by serverSafeValue

    /**
     * How much of the reach is left unused while [serverSafe] is on. Grim tolerates a small latency
     * drift; 0.05 is the value that survives a normal connection, 0.10 a bad one.
     */
    private val safetyGapValue = float("SafetyGap", 0.05f, 0.0f..0.30f, "blocks")
    private val safetyGap by safetyGapValue

    /**
     * How far past the reach the module may pre-trigger while [serverSafe] is on. This is the knob
     * that actually buys the early click: to be early you have to click while the enemy is still a
     * little outside your reach, and the only part of that which a server lets through is the part
     * your own latency covers — the enemy keeps walking while the packet travels, so by the time it
     * is evaluated they are closer again. `0.10` is roughly one tick of a sprinting target at ~70ms
     * ping; `0.0` is the strictly legal triggerbot (never early, never flagged).
     */
    private val overReachValue = float("OverReach", 0.15f, 0.0f..0.50f, "blocks")
    private val overReach by overReachValue

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

    /**
     * Click rate, as a slider range: every click draws a new value between the two handles, so the
     * rate is not a constant. 20 is one click per tick, the maximum a client can send.
     */
    private val cpsValue = intRange("CPS", 9..13, 1..20, "clicks")
    private val cps by cpsValue

    /**
     * Extra random delay on top of the CPS interval, in percent. A constant interval is what a macro
     * looks like; this only ever makes a click later, never earlier.
     */
    private val cpsJitterValue = int("CpsJitter", 10, 0..40, "%")
    private val cpsJitter by cpsJitterValue

    /** Random pause between two clicks, in ticks, on top of [cps]. */
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

    /** Wall-clock time of the next allowed click, from [cps] and [cpsJitter]. */
    private var nextClickAt = 0L

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
        nextClickAt = 0L

        if (!serverSafe) {
            chat(
                "§7[TriggerBotPlus] §cServerSafe is off: the pre-trigger clicks beyond your reach, " +
                    "so Grim's Reach check will flag it. That is the feature, not a bug."
            )
        }

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
        nextClickAt = 0L
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

        if (Util.getMillis() < nextClickAt) return

        // Acquisition uses the widest distance the module can ever trigger at, otherwise an
        // encounter whose cushion was not drawn would be lost on every tick.
        val target = findTarget(me, acquisitionRange(me))
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

        val base = baseDistance(me)
        val measured = serverDistance(me, target)
        val limit = triggerLimit(me, base)
        val distance = if (serverSafe) measured else predictedDistance(me, target, measured)
        if (distance > limit) return

        if (!weapon.matchesAny(me.mainHandItem)) return
        if (skipInvulnerable && target.hurtTime > 0) return
        if (!isCooldownReady(me)) return

        // A pre-trigger is a click that really happened past the base distance because of the
        // cushion of this encounter — not merely "a cushion existed".
        val isEarly = earlyMargin > 0.0 && measured > base

        if (!attackEntity(target, swing, keepSprint)) {
            // Cancelled (e.g. by HitSelect) — try again next tick instead of spinning on it.
            clickDelay = 1
            return
        }

        triggers++
        if (isEarly) {
            preTriggers++
            // how much earlier than the base distance the click really was, in blocks
            lastMargin = measured - base
        }
        clickDelay = nextIn(clickDelayRange)
        nextClickAt = Util.getMillis() + nextIntervalMillis()

        report(me, target, measured, base, isEarly)
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
            .filter { serverDistance(me, it) <= range }
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

    /**
     * The reference distance: the weapon's real reach when [matchVanilla] is on, otherwise [range].
     * With [serverSafe] the cushion is carved out of it, so this is also the point at which a plain
     * triggerbot would click.
     */
    private fun baseDistance(me: LocalPlayer): Double =
        if (matchVanilla) me.entityInteractionRange() else range.toDouble()

    /** How far the acquisition ray looks: far enough to see the enemy before the trigger distance. */
    private fun acquisitionRange(me: LocalPlayer): Double =
        baseDistance(me) + early.endInclusive + overReach + RAY_SLACK

    /**
     * The distance an attack packet is measured with on the server side: attacker's eyes to the
     * target's hitbox, with no extra slack.
     *
     * [squaredBoxedDistanceTo] must not be used for this: it measures from `boundingBox.inflate(pickRadius)`,
     * i.e. 0.1 blocks short. Clicks that look like "3.0 blocks" would land at 3.1 and be a Reach flag
     * on their own.
     */
    private fun serverDistance(me: LocalPlayer, target: LivingEntity): Double =
        sqrt(target.boundingBox.distanceToSqr(me.eyePosition))

    /**
     * The distance the click actually happens at.
     *
     * Without [serverSafe] it is plain `base + cushion`: the drawn cushion is added as it is, so a
     * cushion of `0.25` clicks a quarter of a block before the reach — and past it.
     *
     * With [serverSafe] the draw is **mapped onto the legal window** instead of being clamped to it.
     * The window is `base .. reach + OverReach - SafetyGap` — the part of the pre-trigger your own
     * latency hands back — and the cushion decides *where* inside it the click lands, so the
     * randomness survives instead of piling every encounter on the ceiling:
     *
     *   distance = base + (cushion / Early.max) * window
     *
     * `OverReach = 0` therefore means "click at the reach, never past it", and the largest cushion
     * always means "as early as the server still accepts".
     */
    private fun triggerLimit(me: LocalPlayer, base: Double): Double {
        if (!serverSafe) return (base + earlyMargin).coerceAtLeast(MIN_TRIGGER)

        val ceiling = me.entityInteractionRange() + overReach - safetyGap
        val window = (ceiling - base).coerceAtLeast(0.0)
        val top = early.endInclusive.toDouble()
        val share = if (top <= 0.0) 0.0 else (earlyMargin / top).coerceIn(0.0, 1.0)
        return (base + window * share).coerceAtLeast(MIN_TRIGGER)
    }

    /**
     * [serverDistance] minus the part of the relative movement that closes it within [predictTicks]
     * ticks. Only the closing part counts: an enemy running away is never clicked earlier than the
     * base distance. This is the out-of-reach lead, so it is only used with [serverSafe] off.
     */
    private fun predictedDistance(me: LocalPlayer, target: LivingEntity, measured: Double): Double {
        if (!predict || predictTicks <= 0) return measured

        val toTarget = target.boundingBox.center.subtract(me.eyePosition)
        if (toTarget.lengthSqr() < 1.0e-5) return measured

        val relative = target.deltaMovement.subtract(me.deltaMovement)
        val closing = -relative.dot(toTarget.normalize()) // + while the enemy comes towards us
        val lead = (closing * predictTicks).coerceIn(0.0, MAX_LEAD)

        return (measured - lead).coerceAtLeast(0.0)
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

    /** Milliseconds until the next click, drawn from [cps] and stretched by [cpsJitter]. */
    private fun nextIntervalMillis(): Long {
        val perSecond = nextIn(cps).coerceIn(1, 20)
        val base = 1000.0 / perSecond
        val jitter = if (cpsJitter <= 0) 0.0 else Random.nextDouble(0.0, cpsJitter / 100.0)
        return (base * (1.0 + jitter)).toLong()
    }

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
        cpsValue.set(p.cps)
        clickDelayValue.set(p.clickDelay)
        throughWallsValue.set(p.throughWalls)
        keepSprintValue.set(p.keepSprint)
        serverSafeValue.set(p.serverSafe)
        safetyGapValue.set(p.safetyGap)
        overReachValue.set(p.overReach)

        if (debug || chatLog) {
            chat("§7[TriggerBotPlus] §fpreset §7${preset.tag}§f applied")
        }
    }

    private fun report(me: LocalPlayer, target: LivingEntity, distance: Double, base: Double, isEarly: Boolean) {
        if (chatLog && isEarly) {
            chat(
                "§7[TriggerBotPlus] §fpre-trigger §7${target.name.string} §fat %.2f§7 blocks (+%.2f on base %.2f)"
                    .format(measured, measured - base, base)
            )
            val reach = me.entityInteractionRange()
            if (!serverSafe && distance > reach) {
                chat(
                    "§7[TriggerBotPlus] §c%.2f > real reach %.2f — the server may reject this hit"
                        .format(distance, reach)
                )
            }
        }

        if (debug) {
            chat(
                "§7[TriggerBotPlus] §ftrigger #$triggers §7(pre $preTriggers) §fdist=%.2f base=%.2f limit=%.2f cps=%d%s"
                    .format(measured, base, triggerLimit(me, base), nextIn(cps), if (isEarly) " §6early" else "")
            )
        }
    }
}
