package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.config.autoconfig.AutoConfig
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoClicker
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleHitbox
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleReach
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.ping
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.item.WeaponType
import net.ccbluex.liquidbounce.utils.kotlin.matchesAny
import net.ccbluex.liquidbounce.utils.raytracing.findEntityInCrosshair
import net.ccbluex.liquidbounce.utils.raytracing.hasLineOfSight
import net.minecraft.client.KeyMapping
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Util
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.entity.LivingEntity
import kotlin.math.max
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
 * ### What "early" means here: the press, not the hit
 *
 * The first version of this module attacked the enemy from outside the reach. That is a Reach hack
 * and every anticheat that measures attack distance flags it — correctly.
 *
 * So the early part was moved **onto the button**: while the enemy is inside the acquisition ray but
 * still outside the attack distance, the module presses the left button ([prePress]) without
 * attacking anyone —
 *
 *  - [PrePress.SWING] swings the arm, exactly the packets vanilla sends when your click hits nothing;
 *  - [PrePress.KEY] queues a real attack-key press and lets vanilla resolve it on its own hit result,
 *    so the attack happens only once the enemy is already inside the vanilla reach.
 *
 * The attack itself then always comes from [attackLimit], i.e. from inside `reach - [safetyGap]`
 * (plus [overReach], 0 by default). Nothing is ever sent to an out-of-range entity, so there is
 * nothing for a Reach check to see, while the hand is already clicking the instant the enemy walks
 * in: no reaction time, no lost tick, no wasted swing.
 *
 * The distance is measured exactly like the server does it ([serverDistance]): from the eyes to the
 * hitbox, **without** the client pick-radius slack. Using the usual boxed distance (which inflates
 * the hitbox by 0.1) is enough on its own to push a "3.0 blocks" attack over the limit.
 *
 * ### Why a reach flag appears "every now and then"
 *
 * Three things move the distance the server computes away from the one the client sees, and none of
 * them shows up when you stand still — which is exactly why the flag looks random:
 *
 *  1. **Latency.** The server measures from your last received position against the position the
 *     enemy had when that packet was sent. If you are both closing in, that pair is farther apart
 *     than what you see on screen. [desync] subtracts `(my speed + their speed) × half the RTT` from
 *     the attack distance, read from the real [ping], so the click is only allowed when even the
 *     server's older view is inside the reach;
 *  2. **`Reach` and the item's range component.** `ENTITY_INTERACTION_RANGE` is what *your client*
 *     thinks it can hit; the server only knows the vanilla 3.0. That is why every attack is capped
 *     by [serverReach] (3.0 by default) instead of by the attribute;
 *  3. **`Hitbox`.** It inflates the vanilla hit result through the item's hitbox margin, so in
 *     [PrePress.KEY] mode a queued press could become an out-of-range attack. The press is filtered
 *     against the real distance before it is queued.
 *
 * With `ServerReach = 3.0`, `SafetyGap = 0.05`, `OverReach = 0` and [desync] on, the attack happens
 * at `2.95 - drift`, i.e. between ~2.4 and 2.95 blocks depending on how fast both sides are closing.
 * The margin is the price of not being flagged; the early press is what keeps the speed.
 *
 * Settings:
 *   Preset            ready-made configs (Custom = the sliders below)
 *   Range             base trigger distance, used while the enemy is still outside real reach
 *   MatchVanilla      use the weapon's real attack range (attribute + item component) as base
 *   Early             slider range `min..max`: how early the button starts being pressed
 *   CPS               clicks per second as a slider range, re-drawn after every click
 *   CpsJitter         extra random delay on top of the CPS interval, in %
 *   Fov               0 = the enemy must be under the crosshair ray, >0 = a cone around it
 *   EarlyChance       chance (%) that an encounter uses a cushion at all
 *   ServerSafe        never click beyond the reach the server measures (default on)
 *   SafetyGap         how much of the reach is left unused while ServerSafe is on
 *   OverReach         how far past the reach an *attack* may go (0 = never, keep it there)
 *   PrePress          Off / Swing / Key: what the early press does
 *   ServerReach       the reach the server measures (3.0), caps every attack
 *   DesyncCompensation / DesyncPercent   subtract the position drift caused by ping
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

    /** Upper bound on the latency allowance, in blocks. Beyond this it is a lag spike, not drift. */
    private const val MAX_DESYNC = 0.8

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
        val overReach: Float = 0.0f,
        val prePress: PrePress = PrePress.SWING,
        val serverReach: Float = 3.0f,
        val desync: Boolean = true,
        val desyncPercent: Int = 100
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
                serverSafe = true, safetyGap = 0.05f, overReach = 0.0f, prePress = PrePress.SWING, serverReach = 3.0f, desync = true, desyncPercent = 100
            )
        ),

        /** The sweet spot for normal servers. */
        CLOSET(
            "Closet",
            Profile(
                range = 3.0f, matchVanilla = true, early = 0.20f..0.55f, earlyChance = 75,
                predict = true, predictTicks = 2, reaction = 0..0, minCooldown = 1.0f,
                cps = 10..14, clickDelay = 0..0, throughWalls = false, keepSprint = false,
                serverSafe = true, safetyGap = 0.05f, overReach = 0.0f, prePress = PrePress.KEY, serverReach = 3.0f, desync = true, desyncPercent = 100
            )
        ),

        /** Always early, wider cushion, through walls. Only for testing on your own world. */
        AGGRESSIVE(
            "Aggressive",
            Profile(
                range = 3.2f, matchVanilla = false, early = 0.35f..0.90f, earlyChance = 100,
                predict = true, predictTicks = 2, reaction = 0..0, minCooldown = 0.90f,
                cps = 14..20, clickDelay = 0..0, throughWalls = true, keepSprint = false,
                serverSafe = false, safetyGap = 0.05f, overReach = 0.0f, prePress = PrePress.SWING, serverReach = 3.0f, desync = true, desyncPercent = 100
            )
        )
    }

    /** How the early click is delivered, i.e. what "pressing the left button" means here. */
    enum class PrePress(override val tag: String) : Tagged {
        /** No early click: the module only attacks, in range. */
        OFF("Off"),

        /**
         * Swing the arm exactly like vanilla does when you click at nothing: client animation plus
         * `ServerboundSwingPacket`. No attack packet, no cooldown consumed, nothing to flag.
         */
        SWING("Swing"),

        /**
         * Queue a real attack-key press, like pressing the button yourself. Vanilla resolves it
         * against its own hit result, so the attack only happens when the enemy is already inside the
         * vanilla reach. Held back while vanilla is still in its miss cooldown.
         */
        KEY("Key")
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

    /**
     * The random cushion: every encounter draws one value between these two handles. It is how many
     * blocks **before** the attack distance the module starts pressing the left button — the enemy is
     * still out of reach, so the press hits nothing, but the hand is already clicking when they
     * arrive.
     */
    private val earlyValue = floatRange("Early", 0.10f..0.35f, 0.0f..2.0f, "blocks")
    private val early by earlyValue

    /** Chance (%) that an encounter draws a cushion at all. 0 = never early, 100 = always. */
    private val earlyChanceValue = int("EarlyChance", 100, 0..100, "%")
    private val earlyChance by earlyChanceValue

    /**
     * 0 keeps the strict triggerbot behaviour: only the enemy the crosshair ray actually crosses is
     * a target. Anything above widens the acquisition to a cone of that many degrees around the
     * crosshair, so an enemy brushing the edge of the hitbox can also be pre-triggered.
     */
    private val fovValue = float("Fov", 0f, 0f..30f, "°")
    private val fov by fovValue

    /**
     * Keep every **attack** inside the reach the server measures: the hit happens at
     * `min(base, reach - SafetyGap + OverReach)`. The early part of the module does not need this,
     * because it lives on the press ([prePress]), which never attacks.
     *
     * Turning it off adds the cushion to the attack distance as it is drawn, past the reach: the
     * attack itself happens out of range, which is exactly what Grim's Reach check flags.
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
     * How far past the reach an **attack** may go while [serverSafe] is on. Keep it at `0`: the early
     * click is the press now, so an attack has no reason to leave the reach. Anything above zero is
     * paid for with reach flags, and only makes sense together with [serverSafe] off.
     */
    private val overReachValue = float("OverReach", 0.0f, 0.0f..0.50f, "blocks")
    private val overReach by overReachValue

    /**
     * The reach the **server** measures, which is not the one the client uses: the vanilla attribute
     * is 3.0, while `Reach` (or a long item) raises only the client side. Attacks are gated against
     * `min(base, ServerReach)`, so turning Reach up cannot drag this module into a flag.
     */
    private val serverReachValue = float("ServerReach", 3.0f, 2.0f..6.0f, "blocks")
    private val serverReach by serverReachValue

    /**
     * Compensate the position desync: the server evaluates the attack against *older* positions, so
     * while you and the enemy close in on each other the distance it computes is larger than yours.
     * The allowance is subtracted from the attack distance, which only ever makes the click later.
     */
    private val desyncValue = boolean("DesyncCompensation", true)
    private val desync by desyncValue

    /** How much of the estimated desync to take into account, in percent of half the round trip. */
    private val desyncPercentValue = int("DesyncPercent", 100, 0..200, "%")
    private val desyncPercent by desyncPercentValue

    /**
     * What the early click actually is. [PrePress.SWING] and [PrePress.KEY] both press the left
     * button while the enemy is still out of range **without attacking them** — the hit is left to
     * the moment they come in range, which is why nothing here can be a Reach flag.
     */
    private val prePressValue = enumChoice("PrePress", PrePress.SWING)
    private val prePress by prePressValue

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

    private val holdAttackValue = boolean("HoldAttack", false)
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
    private var presses = 0
    private var lastMargin = 0.0

    /**
     * HUD tag: `~N` while the early presses are the only thing happening (the enemy is still out of
     * reach), `+x.xx` once an attack landed earlier than the base distance.
     */
    override val tag: String
        get() = when {
            preTriggers > 0 -> "+%.2f".format(lastMargin)
            presses > 0 -> "~$presses"
            else -> ""
        }

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
        presses = 0
        lastMargin = 0.0
        clickDelay = 0
        nextClickAt = 0L

        if (!serverSafe) {
            chat(
                "§7[TriggerBotPlus] §cServerSafe is off: the pre-trigger clicks beyond your reach, " +
                    "so Grim's Reach check will flag it. That is the feature, not a bug."
            )
        }

        if (ModuleReach.enabled) {
            chat(
                "§7[TriggerBotPlus] §eReach is on: it raises your client range, never the server's. " +
                    "ServerReach (§f${serverReach}§e) keeps the attacks of this module legal, but the " +
                    "rest of your clicks are still on you."
            )
        }

        if (prePress == PrePress.KEY && ModuleHitbox.enabled) {
            chat(
                "§7[TriggerBotPlus] §eHitbox is on: it inflates the vanilla hit result through the item " +
                    "margin, so the Key press is filtered against the real distance and some presses " +
                    "are skipped on purpose."
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
        val attackAt = attackLimit(me, base, target)
        val distance = if (serverSafe) measured else predictedDistance(me, target, measured)

        // The early click: the enemy is still beyond the attack distance, so we only press the left
        // button. Nothing is attacked here — the press hits air, exactly like a player clicking at
        // nothing while the target walks in.
        if (distance > attackAt) {
            if (measured <= attackAt + earlyMargin) {
                pressEarly(me, target, measured, attackAt)
            }
            return
        }

        if (!weapon.matchesAny(me.mainHandItem)) return
        if (skipInvulnerable && target.hurtTime > 0) return
        if (!isCooldownReady(me)) return

        // A pre-trigger is an attack that happened past the base distance because the enemy walked
        // into a press that was already going.
        val isEarly = measured > base

        if (!attackEntity(target, swing, keepSprint)) {
            // Cancelled (e.g. by HitSelect) — try again next tick instead of spinning on it.
            clickDelay = 1
            return
        }

        triggers++
        if (isEarly) {
            preTriggers++
            lastMargin = measured - base
        }
        clickDelay = nextIn(clickDelayRange)
        nextClickAt = Util.getMillis() + nextIntervalMillis()

        report(me, target, measured, base, isEarly)
    }

    // --- the early click ----------------------------------------------------------------------

    /**
     * Presses the left button while the enemy is outside the attack distance: [PrePress.SWING] swings
     * the arm (client animation + swing packet, like vanilla does when the click hits nothing),
     * [PrePress.KEY] queues a real attack-key press so vanilla resolves it on its own hit result.
     *
     * Either way **no attack is sent to an out-of-range entity**, which is what a Reach check looks
     * for. The only thing this buys is time: the button is already down when the enemy steps in.
     */
    private fun pressEarly(me: LocalPlayer, target: LivingEntity, measured: Double, attackAt: Double) {
        if (prePress == PrePress.OFF || earlyMargin <= 0.0) return
        if (!weapon.matchesAny(me.mainHandItem)) return

        when (prePress) {
            PrePress.OFF -> return
            PrePress.SWING -> swing.swing(InteractionHand.MAIN_HAND)
            PrePress.KEY -> {
                // Vanilla ignores clicks for a few ticks after a missed swing; pressing now would be
                // swallowed and the real click would come too late.
                if (mc.missTime > 0) return

                // Vanilla resolves the press against its own hit result. If that hit result is an
                // entity we would not be allowed to attack, the press would become an out-of-range
                // attack — which is a flag, not an early click. (`Hitbox` inflates that hit result
                // through the item's hitbox margin, so this guard is not theoretical.)
                val vanillaTarget = (mc.hitResult as? EntityHitResult)?.entity as? LivingEntity
                if (vanillaTarget != null && serverDistance(me, vanillaTarget) > attackAt) return

                KeyMapping.click(mc.options.keyAttack.key)
            }
        }

        presses++
        clickDelay = nextIn(clickDelayRange)
        nextClickAt = Util.getMillis() + nextIntervalMillis()

        if (debug) {
            chat(
                "§7[TriggerBotPlus] §fearly press §7${target.name.string} §fat %.2f§7 (attack at %.2f, in %.2f)"
                    .format(measured, attackAt, measured - attackAt)
            )
        }
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

    /** How far the acquisition ray looks: far enough to see the enemy before the press begins. */
    private fun acquisitionRange(me: LocalPlayer): Double =
        max(baseDistance(me), me.entityInteractionRange()) + early.endInclusive + overReach + RAY_SLACK

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
     * The distance at which the module is allowed to **attack**. The early part of the module lives
     * on the press ([pressEarly]), never here, so this stays inside the reach:
     *
     *  - [serverSafe] on: `min(base, ServerReach, interaction range) + OverReach - SafetyGap - drift`,
     *    with `OverReach = 0` by default. The `ServerReach` clamp is what makes `Reach` harmless here,
     *    the drift is [desyncAllowance];
     *  - [serverSafe] off: `base + cushion`, i.e. the attack itself happens out of range — reach hack,
     *    and exactly what Grim flags.
     */
    private fun attackLimit(me: LocalPlayer, base: Double, target: LivingEntity): Double {
        if (!serverSafe) {
            return (base + earlyMargin).coerceAtLeast(MIN_TRIGGER)
        }

        // Never above what the server itself accepts: ServerReach is the honest 3.0, and the
        // interaction range is capped by it so the Reach module cannot push the attack farther.
        val ceiling = minOf(base, serverReach.toDouble(), me.entityInteractionRange()) + overReach - safetyGap
        return (ceiling - desyncAllowance(me, target)).coerceAtLeast(MIN_TRIGGER)
    }

    /**
     * How many blocks the client is "ahead" of the server when both sides are closing in.
     *
     * A server does not measure the attack from where you are now: it measures it from the position
     * it last received, and against the position the enemy had when that packet was sent. If you are
     * sprinting into each other, that pair of positions is farther apart than the pair you see, and
     * the difference is `(my speed + their speed) × half the round trip`. Attacking at exactly the
     * limit is what produces a flag "every now and then": when nobody is moving there is no error,
     * and the moment both of you walk in it appears.
     */
    private fun desyncAllowance(me: LocalPlayer, target: LivingEntity): Double {
        if (!desync) return 0.0

        val latency = me.ping.coerceIn(0, 500)
        if (latency <= 0) return 0.0

        val toTarget = target.boundingBox.center.subtract(me.eyePosition)
        if (toTarget.lengthSqr() < 1.0e-5) return 0.0

        val direction = toTarget.normalize()
        val ownClosing = me.deltaMovement.dot(direction).coerceAtLeast(0.0)
        val theirClosing = (-target.deltaMovement.dot(direction)).coerceAtLeast(0.0)

        // half the round trip, in ticks (one tick = 50 ms)
        val ticks = latency / 100.0 * (desyncPercent / 100.0)
        return ((ownClosing + theirClosing) * ticks).coerceIn(0.0, MAX_DESYNC)
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
        prePressValue.set(p.prePress)
        serverReachValue.set(p.serverReach)
        desyncValue.set(p.desync)
        desyncPercentValue.set(p.desyncPercent)

        if (debug || chatLog) {
            chat("§7[TriggerBotPlus] §fpreset §7${preset.tag}§f applied")
        }
    }

    private fun report(me: LocalPlayer, target: LivingEntity, measured: Double, base: Double, isEarly: Boolean) {
        val reach = me.entityInteractionRange()

        if (chatLog && isEarly) {
            chat(
                "§7[TriggerBotPlus] §fpre-trigger §7${target.name.string} §fat %.2f§7 blocks (+%.2f on base %.2f)"
                    .format(measured, measured - base, base)
            )
            if (measured > reach) {
                chat(
                    "§7[TriggerBotPlus] §c%.2f > real reach %.2f — the server may reject this hit"
                        .format(measured, reach)
                )
            }
        }

        if (debug) {
            chat(
                "§7[TriggerBotPlus] §ftrigger #$triggers §7(pre $preTriggers, presses $presses) §fdist=%.2f base=%.2f attackAt=%.2f cps=%d%s"
                    .format(measured, base, attackLimit(me, base, target), nextIn(cps), if (isEarly) " §6early" else "")
            )
        }
    }
}
