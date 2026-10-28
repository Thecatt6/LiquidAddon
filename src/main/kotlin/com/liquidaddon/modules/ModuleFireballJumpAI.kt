package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.aiming.AimassistCore
import com.liquidaddon.aiming.FireballJumpValues
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.doPlacement
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.entity.FallingPlayer
import net.ccbluex.liquidbounce.utils.entity.getMovementDirectionOfInput
import net.ccbluex.liquidbounce.utils.entity.isCloseToEdge
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.findClosestSlot
import net.ccbluex.liquidbounce.utils.kotlin.Priority as RotationPriority
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfo
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfoRenderer
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryType
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.util.Util
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * FireballJumpAI — the BedWars **fireball jump**, done for you, with the aimbot's aim.
 *
 * The whole trick is one explosion: you throw a fireball at your own feet, it goes off under you,
 * and the blast lifts you — and if you throw it *behind* you, pushes you forward as well. It is how
 * you get on top of a base or a high island in one move, how you cross a gap without bridging, how
 * you leave a fight you are losing, and how you arrive next to a bed nobody expected you at.
 *
 * ## What the module does, and what it never does
 *
 * Three things, in this order, and nothing else:
 *
 *  1. it turns the aim down — [Style.UP] is straight at the feet, [Style.LONG] is down *and* to the
 *     side, so the blast is not under you but behind (or in front of) you;
 *  2. it jumps ([jump], on the tick the aim has arrived, so the jump is not spent on a look that is
 *     still on its way down);
 *  3. it throws [throwDelay] ticks after the feet left the ground.
 *
 * It never moves you, never writes a velocity and never cancels a packet. The lift is the server's
 * own explosion knockback — which is also why the height changes from server to server: every one of
 * them can configure how hard a fireball pushes. Hypixel's Bed Wars Practice map is where the timing
 * is worth feeling by hand at least once.
 *
 * You do take the damage and the knockback of your own explosion, and the blast breaks the weak
 * blocks around it (wool, glass). That is the vanilla mechanic, not something this module adds.
 *
 * ## The aim is not written here
 *
 * It is [AimassistCore], the same engine `Aimassist`, `AimbotAI` and `FireballDeflect` run, driven
 * with [FireballJumpValues]: `Engine = AI` with LiquidBounce's trained combat model (and the maths
 * curve under it as the fallback), the mouse-count lattice with its carried residual (`GcdFix`,
 * `GcdDither`), the hand on top of it — the noise, the overshoot, the tendency to land short, the
 * profile that changes every half minute (`MouseNoise`, `Overshoot`, `StrengthSpread`) — the frame
 * interpolation, and the silent rotation through `RotationManager` with `MoveFix`. The module only
 * tells the engine *where* to look ([AimassistCore.forcedGoal], because a throw aims at a spot and
 * not at a body); *how* it looks there is the engine's, so the flick down is built out of the same
 * mouse movements a hit is built out of. There is no second implementation of "how to turn" here.
 *
 * The throw waits for that aim, and waits for the *server* to have it: the item is spent only once
 * `RotationManager.serverRotation` — the look the movement packets already delivered — is within
 * [aimTolerance] of the goal, and the `UseItem` packet is handed that very rotation. A use packet
 * carries a yaw and a pitch of its own and the server throws along them, so handing it a rotation
 * the server has not been told about yet would introduce an angle of up to a whole step at the
 * moment of the throw — the "post rotation" every anticheat is built around. This is the same gate
 * `AutoPearl` uses here and LiquidBounce's own `AutoWindCharge` uses upstream.
 *
 * ## The timing, which is the whole trick
 *
 * Throw too early and the fireball explodes while you are still on the ground, where the ground eats
 * the lift. Throw too late and the blast is far below you, where its knockback has already fallen
 * off: a nudge instead of a jump. The window is the first ticks of the flight, which is why the
 * module aims *before* it jumps and then throws a fixed [throwDelay] ticks after the feet leave the
 * ground (1 or 2 on most servers; the higher the value, the weaker the lift and the more of it is
 * simply your own momentum carrying on).
 *
 * With [Trigger.ENABLE] the module owns the whole sequence and the timing is exact. With
 * [Trigger.JUMP] and [Trigger.EDGE] the jump comes first and the aim arrives a few ticks into the
 * flight: still a jump, a weaker one — the flick takes three or four ticks and the explosion cannot
 * happen before it does.
 *
 * ## The variants
 *
 *  - **double fireball** ([doubleFireball]): a second throw [doubleDelay] ticks after the first,
 *    while you are still in the air. It extends the jump — the usual way to reach a height one
 *    fireball does not give, or to cross a gap that is one blast too wide;
 *  - **fireball + clutch** ([clutch]): after the throw, while falling, if LiquidBounce's own fall
 *    simulation ([FallingPlayer]) finds no ground at all in the next [clutchScan] ticks, the module
 *    puts a block under your feet — the "place something or die in the void" half of the technique.
 *    It is a best effort: a block needs a face to be placed against, so over a gap with nothing
 *    within reach there is simply nothing to do, and the attempt is not repeated more often than
 *    every [CLUTCH_COOLDOWN] ticks.
 *
 * ## The spot it aims at
 *
 * [Style.UP] keeps the yaw exactly where the player left it and only drops the pitch to [pitch]: at
 * 88° the fireball goes straight down and the blast is under your feet, so you go up — and whatever
 * forward speed you had, you keep. It is enough for most jumps and it is the discreet one, because
 * no yaw moves at all and the only rotation the server sees is a pitch flick.
 *
 * [Style.LONG] drops the pitch to [longPitch] and throws to the opposite of where you are *going*
 * ([getMovementDirectionOfInput], not where you happen to be looking, so strafing does not put the
 * blast sideways). With [backwards] on, the blast is behind and below you and pushes you forward and
 * up — the gap crosser. With it off, the blast is in front and below you and pushes you *back* and
 * up — the escape: the enemy stays on your crosshair and you leave anyway.
 *
 * Settings:
 *   Item            Fireball (fire charge), WindCharge, or both
 *   Style           Up (lift) or Long (lift and push)
 *   Pitch           pitch of the Up throw
 *   LongPitch       pitch of the Long throw: lower reaches further behind, higher lifts more
 *   Backwards       Long: throw behind you (forward jump) instead of in front of you (escape)
 *   YawOffset       degrees added to the throw yaw, to steer the blast sideways
 *   AimTolerance    how close the rotation must be before the item is spent
 *   AimTicks        ticks the flick may take before the episode is given up
 *   RequireImpact   simulate the flight first: never spend a fireball that would not explode
 *   Trigger         Enable (the bind is the button), Jump (your jumps), Edge (running off an edge)
 *   EdgeDistance    how close to an edge the Edge trigger fires
 *   Jump            press the jump key itself instead of waiting for yours
 *   JumpDelay       ticks between the aim arriving and the jump
 *   ThrowDelay      ticks between leaving the ground and the throw
 *   AutoSprint      sprint for the jump, so it is a sprint jump and keeps its speed in the air
 *   DoubleFireball  a second throw in the air, to extend the jump
 *   DoubleDelay     ticks between the two throws
 *   Clutch          place a block under the feet when the fall has no ground in it
 *   ClutchFallDistance  fall distance before the clutch starts looking
 *   ClutchScan      ticks of fall the clutch scans for ground: none found = the void
 *   Cooldown        minimum ms between two episodes
 *   AutoDisable     turn the module off after a jump (Trigger = Enable)
 *   SlotReset       ticks the silent hotbar swap is held
 *   Swing           swing mode of the throw
 *   PauseOnScreen / PauseInVehicle
 *   ChatLog         print every jump
 *   Debug           print the phase, the aim, the throw and the simulation
 */
object ModuleFireballJumpAI : ClientModule(
    "FireballJumpAI",
    Categories.LIQUIDADDON,
    aliases = listOf("FireballJump", "FbJump")
) {

    /**
     * Longest simulated flight, in ticks. A fireball that has hit nothing by then is falling into a
     * gap with no bottom, and no bottom is exactly where the blast — the whole lift — never happens.
     */
    private const val MAX_SIM_TICKS = 40

    /** Ticks an episode may spend on the ground waiting for a jump that has to come from the player. */
    private const val READY_TIMEOUT = 40

    /** Ticks of flight an episode may spend waiting for a throw the aim keeps refusing. */
    private const val AIR_TIMEOUT = 30

    /** Ticks of air after the last throw during which the clutch is still on duty. */
    private const val CLUTCH_WINDOW = 80

    /** Ticks between two clutch placements: a fall is not a stream of block packets. */
    private const val CLUTCH_COOLDOWN = 3

    /** How far under the feet the clutch looks for the cell to fill (outside the hitbox). */
    private const val CLUTCH_BELOW = 0.6

    /**
     * Slack on the aim at the moment of the throw, on top of [aimTolerance]: the mouse may have moved
     * between the jump and the throw, and losing the fireball over two degrees is worse than jumping
     * a little off line. Past it the throw waits a tick instead of going somewhere else.
     */
    private const val THROW_SLACK = 12f

    /** What gets thrown. */
    enum class Kind(override val tag: String) : Tagged {
        /** The BedWars fireball: a fire charge. It explodes on impact, and the blast is the lift. */
        FIREBALL("Fireball"),

        /** Wind charge: no explosion, a bubble of knockback. Softer, and it breaks no blocks. */
        WIND_CHARGE("WindCharge");

        val item: Item
            get() = if (this == FIREBALL) Items.FIRE_CHARGE else Items.WIND_CHARGE

        /** The physics LiquidBounce's own simulation models this item with. */
        val trajectoryInfo: TrajectoryInfo
            get() = if (this == FIREBALL) TrajectoryInfo.FIREBALL else TrajectoryInfo.WIND_CHARGE

        val trajectoryType: TrajectoryType
            get() = if (this == FIREBALL) TrajectoryType.Fireball else TrajectoryType.WindCharge
    }

    /** Where the blast has to end up, relative to the player. */
    enum class Style(override val tag: String) : Tagged {
        /** Under the feet: the lift, and the yaw stays exactly where the player left it. */
        UP("Up"),

        /** To the side and below: the lift plus a push, forward ([backwards]) or back. */
        LONG("Long")
    }

    /** When an episode starts. */
    enum class Trigger(override val tag: String) : Tagged {
        /** The bind is the button: one jump as soon as the module is turned on. */
        ENABLE("Enable"),

        /** Every jump the player makes becomes a fireball jump. */
        JUMP("Jump"),

        /** Only when running off an edge, which is when there is a gap to cross. */
        EDGE("Edge")
    }

    /** Where the episode is. The label is what the module tag shows while it runs. */
    private enum class Phase(val label: String) {
        IDLE("idle"), AIM("aim"), READY("jump"), AIR("air")
    }

    // --- what to throw -------------------------------------------------------------------------

    private val kinds by multiEnumChoice("Item", Kind.FIREBALL)
    private val style by enumChoice("Style", Style.UP)

    // --- the aim -------------------------------------------------------------------------------

    /** Pitch of the [Style.UP] throw. 88 is straight down; lower it to keep a little of the yaw. */
    private val pitch by float("Pitch", 88f, 40f..90f, "°")

    /** Pitch of the [Style.LONG] throw: lower reaches further behind, higher lifts more. */
    private val longPitch by float("LongPitch", 74f, 30f..89f, "°")

    /**
     * [Style.LONG]: throw behind the movement (the blast pushes you forward — the gap crosser) or in
     * front of it (the blast pushes you back — the escape, enemy still on the crosshair).
     */
    private val backwards by boolean("Backwards", true)

    /** Degrees added to the throw yaw, to steer the blast sideways. */
    private val yawOffset by float("YawOffset", 0f, -180f..180f, "°")

    /** How close the rotation must be before the item is spent. */
    private val aimTolerance by float("AimTolerance", 3f, 0.5f..20f, "°")

    /** Ticks the flick may take. Past it the episode is given up: no jump, no fireball wasted. */
    private val aimTicks by int("AimTicks", 12, 1..40, "ticks")

    /** Simulate the flight first: a fireball that hits nothing explodes nowhere and lifts nothing. */
    private val requireImpact by boolean("RequireImpact", true)

    // --- the jump ------------------------------------------------------------------------------

    private val trigger by enumChoice("Trigger", Trigger.ENABLE)

    /** How close to an edge the [Trigger.EDGE] trigger fires. */
    private val edgeDistance by float("EdgeDistance", 0.1f, 0.01f..0.6f, "blocks")

    /** Press the jump key itself. Off: the module aims and throws around *your* jump. */
    private val jump by boolean("Jump", true)

    /**
     * Ticks on the ground before the jump key is pressed, counted from the first tick after the aim
     * arrived: `1` is that very tick. The aim and the jump are never in the same packet, because the
     * look the server has is the one the previous tick delivered.
     */
    private val jumpDelay by int("JumpDelay", 1, 1..10, "ticks")

    /**
     * Ticks of flight before the throw, counted from the first tick the feet are off the ground:
     * `1` is that very tick, and it is the one that lifts best. The higher the value, the further
     * below you the blast is and the more of the "jump" is simply your own momentum carrying on.
     */
    private val throwDelay by int("ThrowDelay", 1, 1..6, "ticks")

    /** Sprint for the jump, so it is a *sprint* jump: the forward push is what turns lift into distance. */
    private val autoSprint by boolean("AutoSprint", true)

    // --- the variants --------------------------------------------------------------------------

    /** A second throw in the air: the double fireball, which extends the jump. */
    private val doubleFireball by boolean("DoubleFireball", false)

    /** Ticks between the two throws of a double. */
    private val doubleDelay by int("DoubleDelay", 5, 1..20, "ticks")

    /** Place a block under the feet when the fall has no ground in it. */
    private val clutch by boolean("Clutch", false)

    /** Fall distance (blocks) before the clutch starts looking for a cell to fill. */
    private val clutchFallDistance by float("ClutchFallDistance", 3f, 0.5f..30f, "blocks")

    /** Ticks of fall scanned for ground. Nothing found in that time is the void. */
    private val clutchScan by int("ClutchScan", 20, 4..80, "ticks")

    // --- plumbing ------------------------------------------------------------------------------

    /** Minimum time between two episodes. */
    private val cooldown by int("Cooldown", 500, 0..5000, "ms")

    /** Turn the module off after a jump: the bind becomes a button that fires once. */
    private val autoDisable by boolean("AutoDisable", true)

    /** Ticks the silent hotbar swap is held. */
    private val slotReset by int("SlotReset", 2, 1..20, "ticks")

    private val swing by enumChoice("Swing", SwingMode.DO_NOT_HIDE)

    private val pauseOnScreen by boolean("PauseOnScreen", true)
    private val pauseInVehicle by boolean("PauseInVehicle", true)

    private val chatLog by boolean("ChatLog", false)
    private val debug by boolean("Debug", false)

    // --- the aim engine ------------------------------------------------------------------------

    /** This module's settings: the aimbot's engine, tuned for one flick (see [FireballJumpValues]). */
    private val values = FireballJumpValues(this)

    /**
     * The engine `Aimassist`, `AimbotAI` and `FireballDeflect` run.
     *
     * The target is the player itself, and only while an episode is armed: there is no body to aim
     * at — the goal is a direction — and the player is the one entity that is certainly there for
     * everything the engine still needs an entity for (the model's sample, the debug line). Letting
     * go, i.e. returning `null`, is what walks the rotation back to the camera after the throw
     * instead of holding a look at the floor for the rest of the flight.
     */
    private val core = AimassistCore(
        owner = this,
        values = values,
        forcedTarget = { me -> if (phase == Phase.IDLE) null else me },
        forcedGoal = { me -> throwRotation(me) },
        // A jump is worth more than an aim, and less than a fireball somebody else threw at you.
        rotationPriority = RotationPriority.IMPORTANT_FOR_PLAYER_LIFE,
    )

    // --- state ---------------------------------------------------------------------------------

    private var phase = Phase.IDLE

    /** Ticks spent in the current phase. */
    private var phaseTicks = 0

    /** Fireballs spent in the current episode: one, or two with [doubleFireball]. */
    private var thrownInAir = 0

    /** Set by the tick handler, consumed by the input handler of the same tick. */
    private var jumpRequested = false

    /** [LocalPlayer.tickCount] up to which the clutch is on duty, and when it last placed. */
    private var clutchUntilTick = -1
    private var lastClutchTick = -100

    private var lastEpisode = 0L
    private var jumps = 0
    private var doubles = 0
    private var lastNote = ""

    override val tag: String
        get() = when {
            phase != Phase.IDLE -> phase.label
            jumps == 0 -> ""
            else -> "$jumps"
        }

    override fun onEnabled() {
        reset()
        core.onEnable()
        super.onEnabled()
        chat(
            "§7[FireballJump] §aattivo §7- " + style.tag + ", trigger " + trigger.tag +
                ", mira dell'aimbot (" + values.engine.tag + "): lancia quando la rotazione del server è arrivata"
        )
    }

    override fun onDisabled() {
        // Dropping the target lets the rotation request expire on its own: the camera takes the view
        // back without a packet of ours, and the silent hotbar swap is not left behind either.
        core.onDisable()
        SilentHotbar.resetSlot(this)
        reset()
        super.onDisabled()
    }

    private fun reset() {
        phase = Phase.IDLE
        phaseTicks = 0
        thrownInAir = 0
        jumpRequested = false
        clutchUntilTick = -1
        lastClutchTick = -100
        lastEpisode = 0L
        jumps = 0
        doubles = 0
        lastNote = ""
    }

    /** Friction is fixed to zero for this profile; the handler keeps the engine whole. */
    @Suppress("unused")
    private val mouseHandler = handler<MouseRotationEvent> { core.onMouseRotation(it) }

    /** Frame interpolation: the tick's step drawn slice by slice, when the camera is ours. */
    @Suppress("unused")
    private val renderHandler = handler<GameRenderEvent> { core.onRender() }

    /**
     * The jump key, and the sprint that makes it a sprint jump.
     *
     * Written here and not in the tick: `KeyboardInput.tick` fires [MovementInputEvent] and then
     * builds the input the player moves with, so a `jump = true` set here is indistinguishable from
     * the player pressing space — vanilla performs the jump itself, with its own timings and its own
     * sprint boost, and the server receives an ordinary jump. Nothing is written to a packet.
     */
    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> { event ->
        val me = mc.player ?: return@handler

        if (autoSprint && phase != Phase.IDLE) {
            // On the ground it is what makes the jump a sprint jump, i.e. one with a forward push;
            // in the air it is what keeps the speed the blast gave, which vanilla drops as soon as
            // the forward key is not held — and a jump that stops moving is a short one.
            me.isSprinting = true
        }

        if (!jumpRequested) return@handler
        jumpRequested = false
        event.jump = true
    }

    /** The whole module: the episode first, the aim second, the throw last. */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val me = mc.player ?: return@handler

        if (!canOperate(me)) {
            abort("not in a state to jump")
            return@handler
        }

        // The phase machine decides, the engine turns, and only then the item is spent: the throw
        // goes out on the rotation the engine has already delivered to the server, never on one that
        // is still on its way there (see [throwCharge]).
        var wantThrow = false
        advance(me) { wantThrow = true }
        if (!enabled) return@handler

        core.tick()

        if (wantThrow && throwCharge(me)) thrownInAir++

        clutchTick(me)
    }

    // --- the episode ---------------------------------------------------------------------------

    /**
     * One step of the episode. [onThrow] is called on the tick the item has to be spent, and only
     * marks the tick: the throw itself happens after [core] has run, on the rotation the server
     * already has (see [throwCharge]).
     */
    private fun advance(me: LocalPlayer, onThrow: () -> Unit) {
        phaseTicks++

        when (phase) {
            Phase.IDLE -> if (wantsToJump(me)) arm(me)

            Phase.AIM -> {
                val goal = throwRotation(me)
                // The rotation the server has, not the one the engine asked for on this tick: the
                // aim counts as arrived when the *server* is looking down, because that is the only
                // look the throw is allowed to spend the item on (see [throwCharge]).
                val sent = RotationManager.serverRotation
                val off = sent.directionAngleTo(goal)
                // [aimTicks] is not a hard give-up: the last couple of degrees may never arrive (the
                // model under-shoots, the hand lands short, a count of noise keeps it off), and a
                // look that is two degrees off still throws where it should. Past that it is not a
                // flick down anymore and the episode is over before anything is spent.
                val wanted = if (phaseTicks > aimTicks) aimTolerance + THROW_SLACK else aimTolerance
                if (off > wanted) {
                    if (phaseTicks > aimTicks) {
                        abort("the aim is still %.1f° off after $aimTicks ticks".format(off))
                    }
                    return
                }

                note(
                    if (off > aimTolerance) {
                        "the aim is %.1f° off after $aimTicks ticks, going anyway".format(off)
                    } else {
                        "the aim is down, at %.1f°/%.1f°".format(sent.yaw, sent.pitch)
                    }
                )
                // Already in the air (Trigger = Jump, Edge): there is no jump left to press, and this
                // tick is the first one of the flight. On the ground, the jump still has to come.
                if (me.onGround()) {
                    phase = Phase.READY
                    phaseTicks = 0
                } else {
                    phase = Phase.AIR
                    phaseTicks = 1
                }
            }

            Phase.READY -> {
                if (me.onGround()) {
                    if (phaseTicks > READY_TIMEOUT) {
                        abort("never left the ground")
                        return
                    }
                    // The key is pressed with the input of this very tick, so the feet leave the
                    // ground before the next tick comes to look.
                    if (jump && phaseTicks >= jumpDelay) jumpRequested = true
                    return
                }
                phase = Phase.AIR
                // This is already the first tick of the flight, and [airTick] does not count it
                // again: [advance] is the only place a tick is added to [phaseTicks].
                phaseTicks = 1
                airTick(me, onThrow)
            }

            Phase.AIR -> airTick(me, onThrow)
        }
    }

    /**
     * One tick of flight: the throw, the double, the landing.
     *
     * [phaseTicks] is already this tick's count: [advance] adds one per tick, and the tick that
     * enters the phase counts itself as the first one — so a tick of flight is never counted twice
     * and `ThrowDelay` means the ticks it says it means.
     */
    private fun airTick(me: LocalPlayer, onThrow: () -> Unit) {
        if (me.onGround()) {
            finish(me, thrownInAir > 0)
            return
        }

        when {
            // The first throw: the tick the window opens on, and the one that decides the height.
            thrownInAir == 0 -> if (phaseTicks >= throwDelay) onThrow()

            // The double: it extends a flight that is already under way, so it waits its own delay
            // after the first throw went.
            thrownInAir == 1 && doubleFireball ->
                if (phaseTicks >= throwDelay + doubleDelay) onThrow()

            // Nothing left to spend. Let the rotation walk back to the camera now instead of holding
            // a look at the floor for the rest of the flight.
            thrownInAir >= 2 || !doubleFireball -> finish(me, thrownInAir > 0)
        }

        if (phase == Phase.AIR && phaseTicks > AIR_TIMEOUT) {
            // A throw the aim kept refusing (the mouse moved, the arc stopped hitting anything): the
            // episode ends with whatever was spent, and the fireball that was not stays in the hand.
            finish(me, thrownInAir > 0)
        }
    }

    /** Starts an episode: from this tick on the aim goes down. */
    private fun arm(me: LocalPlayer) {
        if (Util.getMillis() - lastEpisode < cooldown) return

        // Both refusals take the cooldown with them: asking every tick for an item that is not there,
        // or for a blast that has nothing to hit, is a scan and not a jump.
        lastEpisode = Util.getMillis()

        val kind = kindInHand()
        if (kind == null) {
            note("nothing to throw in the off hand or the hotbar")
            return
        }

        val goal = throwRotation(me)
        if (requireImpact && simulate(me, kind, goal) == null) {
            note("the ${kind.tag} would not hit anything from here")
            return
        }

        phase = Phase.AIM
        phaseTicks = 0
        thrownInAir = 0
        note("armed: ${kind.tag} ${style.tag} at %.1f°/%.1f°".format(goal.yaw, goal.pitch))
    }

    /** Whether this tick wants a new episode. */
    private fun wantsToJump(me: LocalPlayer): Boolean = when (trigger) {
        // The bind is the button: the first tick the module runs is the first tick of the jump, and
        // with AutoDisable the last one too.
        Trigger.ENABLE -> true

        // Your jump, boosted. The aim starts when the jump does, so the throw lands a few ticks into
        // the flight (see the class documentation): the lift is smaller, the timing stays yours.
        Trigger.JUMP -> me.onGround() && mc.options.keyJump.isDown

        // Running off an edge: a gap is what the fireball is for. Sneaking is a player standing on
        // that edge on purpose, and that player is not about to jump.
        Trigger.EDGE -> me.onGround() && !me.isShiftKeyDown &&
            me.isCloseToEdge(distance = edgeDistance.toDouble())
    }

    /** Ends an episode: the counters, the clutch window, the log, and the auto-disable. */
    private fun finish(me: LocalPlayer, thrown: Boolean) {
        if (thrown) {
            jumps++
            if (thrownInAir > 1) doubles++
            clutchUntilTick = me.tickCount + CLUTCH_WINDOW
        }

        if (chatLog && thrown) {
            chat(
                "§7[FireballJump] §f" + style.tag + " §7salto §f$jumps" +
                    (if (doubles == 0) "" else " §7(doppio $doubles)") +
                    " §7aria ${phaseTicks}t, caduta %.1f".format(me.fallDistance)
            )
        }

        phase = Phase.IDLE
        phaseTicks = 0
        thrownInAir = 0
        jumpRequested = false

        if (thrown && autoDisable && trigger == Trigger.ENABLE) {
            enabled = false
        }
    }

    /** Ends an episode that did not happen: nothing was spent, so nothing is counted. */
    private fun abort(reason: String) {
        if (phase == Phase.IDLE) return
        note("gave up: $reason")
        phase = Phase.IDLE
        phaseTicks = 0
        thrownInAir = 0
        jumpRequested = false
    }

    // --- the throw -----------------------------------------------------------------------------

    /**
     * Spends the item, on the rotation the server already has.
     *
     * That rotation is the point. The `UseItem` packet carries a yaw and a pitch of its own and the
     * server throws along them, so the fireball goes exactly where the aim says — but a use packet
     * that introduces an angle the movement packets have not delivered is the "post rotation"
     * pattern every anticheat is built around. `RotationManager.serverRotation` is the look the last
     * movement packet carried, so handing *that* to the use makes the throw a consequence of a look
     * instead of a look invented by it, and the gate below is what makes that look the goal.
     *
     * @return whether the item really left the hand. A refusal leaves [thrownInAir] alone, so the
     *   next tick tries again — the window is a few ticks wide, not one.
     */
    private fun throwCharge(me: LocalPlayer): Boolean {
        val kind = kindInHand() ?: run {
            note("nothing left to throw")
            return false
        }

        val goal = throwRotation(me)
        val aim = RotationManager.serverRotation
        val off = aim.directionAngleTo(goal)
        if (off > aimTolerance + THROW_SLACK) {
            note("the aim is %.1f° off the throw, waiting".format(off))
            return false
        }

        val impact = if (requireImpact) simulate(me, kind, aim) else null
        if (requireImpact && impact == null) {
            note("the ${kind.tag} would not explode anywhere")
            return false
        }

        val slot = Slots.OffhandWithHotbar.findSlot(kind.item) ?: return false

        SilentHotbar.selectSlotSilently(this, slot, slotReset)
        val result = useItem(slot.useHand, normalizeYaw(aim.yaw), aim.pitch, swing)

        if (result !is InteractionResult.Success) {
            note("the use was refused ($result)")
            return false
        }

        if (debug) {
            chat(
                "§8[FireballJump D] §7throw ${thrownInAir + 1} (${kind.tag}) at " +
                    "%.1f°/%.1f°".format(aim.yaw, aim.pitch) +
                    ", %.2f° off, air ${phaseTicks}t, vy %.2f".format(off, me.deltaMovement.y) +
                    (if (impact == null) "" else ", impact ${format(impact.location)}")
            )
        }
        return true
    }

    // --- the clutch ----------------------------------------------------------------------------

    /**
     * The "fireball + clutch" half of the technique: after the throw, while falling, put a block
     * under the feet when the fall has no ground in it.
     *
     * The scan is LiquidBounce's own [FallingPlayer] — the same fall simulation `NoFall (MLG)` and
     * `AutoWindCharge` plan with — so "there is nothing to land on" means what the client's own
     * physics says and not what a block scan guesses. The placement is LiquidBounce's own
     * [doPlacement], which finds a face to place against, swaps to the block silently and clicks it.
     * Over a gap with nothing within reach there is no face and it returns `false`: the module does
     * not keep sending packets at it, it waits [CLUTCH_COOLDOWN] ticks and tries the next cell down.
     */
    private fun clutchTick(me: LocalPlayer) {
        if (!clutch || me.tickCount > clutchUntilTick) return
        if (me.onGround() || me.deltaMovement.y >= 0.0) return
        if (me.fallDistance < clutchFallDistance) return
        if (me.tickCount - lastClutchTick < CLUTCH_COOLDOWN) return

        // Ground inside the scanned fall: this is a landing, not a clutch.
        if (FallingPlayer.fromPlayer(me).findCollision(clutchScan) != null) {
            note("the fall has ground in it, no clutch")
            return
        }

        lastClutchTick = me.tickCount

        val level = mc.level ?: return
        val cell = BlockPos.containing(me.x, me.y - CLUTCH_BELOW, me.z)
        if (!level.getBlockState(cell).canBeReplaced()) return

        val slot = Slots.OffhandWithHotbar.findClosestSlot { stack -> stack.item is BlockItem }
        if (slot == null) {
            note("no block to clutch with")
            return
        }

        if (!SilentHotbar.selectSlotSilently(this, slot, slotReset)) return
        if (!doPlacement(cell, hand = slot.useHand, swingMode = swing)) {
            note("nothing to place the clutch block against")
            return
        }

        // One block is not a floor and the fall is not over: keep the window open.
        clutchUntilTick = me.tickCount + CLUTCH_WINDOW
        if (debug) chat("§8[FireballJump D] §7clutch block at ${cell.x} ${cell.y} ${cell.z}")
    }

    // --- the aim -------------------------------------------------------------------------------

    /**
     * Where the fireball has to be thrown: a yaw and a pitch, and nothing else. This is the goal the
     * engine turns towards ([AimassistCore.forcedGoal]); the turn itself is the engine's.
     *
     * The yaw is the player's own for [Style.UP]: at 88° of pitch the horizontal component is almost
     * nothing, so keeping it means the only rotation the server ever sees is the pitch flick, and a
     * yaw that does not move is a yaw no rotation check has anything to say about. For [Style.LONG]
     * the throw goes to the opposite of the *movement* direction ([getMovementDirectionOfInput]) —
     * where you are going, not where you happen to be looking, so strafing across a gap does not put
     * the blast sideways.
     */
    private fun throwRotation(me: LocalPlayer): Rotation {
        val down: Float
        val yaw: Float

        if (style == Style.UP) {
            down = pitch
            yaw = me.yRot
        } else {
            down = longPitch
            val movement = me.getMovementDirectionOfInput()
            yaw = if (backwards) movement + 180f else movement
        }

        return Rotation(normalizeYaw(yaw + yawOffset), down.coerceIn(-89.9f, 89.9f))
    }

    /**
     * What LiquidBounce's own collision-aware simulation says about the throw: what the fireball
     * hits, or `null` when it hits nothing — which over a gap with no bottom is the usual answer, and
     * the reason [requireImpact] exists. A fireball that never explodes never lifts anybody.
     */
    private fun simulate(me: LocalPlayer, kind: Kind, rotation: Rotation): HitResult? {
        val simulation = TrajectoryInfoRenderer.getHypotheticalTrajectory(
            simulationOwner = me,
            trajectoryInfo = kind.trajectoryInfo,
            trajectoryType = kind.trajectoryType,
            rotation = rotation,
            // no partial-tick interpolation: the throw is planned from the position of this tick
            partialTicks = 0f
        ).runSimulation(MAX_SIM_TICKS)

        val hit = simulation.hitResult ?: return null
        if (hit.type == HitResult.Type.MISS) return null
        return hit
    }

    // --- gating / plumbing ---------------------------------------------------------------------

    private fun canOperate(me: LocalPlayer): Boolean {
        if (mc.gameMode == null || me.isSpectator) return false
        if (pauseOnScreen && mc.gui.screen() != null) return false
        if (pauseInVehicle && me.vehicle != null) return false
        // No jump in water, in lava or on an elytra: the blast pushes, but nothing to jump from.
        return !me.isFallFlying && !me.isSwimming && !me.isInWater && !me.isInLava
    }

    /** The first kind the settings allow that is really in the off hand or the hotbar. */
    private fun kindInHand(): Kind? =
        Kind.entries.firstOrNull { kind ->
            kinds.contains(kind) && Slots.OffhandWithHotbar.findSlot(kind.item) != null
        }

    /** Brings a yaw back into -180..180: same direction, and no 360° jump for the packets. */
    private fun normalizeYaw(yaw: Float): Float {
        var wrapped = yaw % 360f
        if (wrapped <= -180f) wrapped += 360f
        if (wrapped > 180f) wrapped -= 360f
        return wrapped
    }

    private fun format(point: Vec3): String = "(%.1f, %.1f, %.1f)".format(point.x, point.y, point.z)

    /** One chat line per *change* of state: the same reason repeated is noise, not debugging. */
    private fun note(message: String) {
        if (!debug || message == lastNote) return
        lastNote = message
        chat("§8[FireballJump D] §7$message")
    }
}
