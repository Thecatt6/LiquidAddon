package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.aiming.AimbotValues
import com.liquidaddon.aiming.AimassistCore
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.render.TargetRenderer

/**
 * AimbotAI — aims by itself, with the Aimassist's engine.
 *
 * The aiming logic is not duplicated: this module drives the same [AimassistCore] the Aimassist
 * uses, so it inherits every bypass trick that engine has (the mouse-count lattice with its carried
 * residual, `GcdDither`, the frame interpolation of a tick's step, the silent rotation through
 * `RotationManager` with `MoveFix`, and LiquidBounce's already-trained combat model under
 * `Engine = AI`).
 *
 * What is different is that it aims **on its own**: the settings are not the assist's
 * ([AimbotValues]) and the engine is told to pick the target itself ([AimbotValues.autoAim]). The
 * cone around the crosshair stops filtering, the pull no longer fades with the distance from where
 * you are looking, and there is no reaction pause, no fade-in and no idle beat — while a target is
 * in `Range` and inside `Fov` (360° by default, i.e. all around you) the aim goes to it and stays
 * on it until it is dead or out of reach.
 *
 * The aimbot never clicks: it only aims. Use a KillAura/clicker for the hits, or click yourself —
 * and when you do, the click is synchronised with the aim: it is held until the rotation the server
 * has is on the entity you hit, then sent (see [AimassistCore.onAttack]). An aimbot rotates a lot,
 * so this is the difference between an attack that arrives *with* its rotation and the
 * attack-then-snap pattern Vulcan calls `Killaura (Post)`.
 *
 * How it gets there is still the assist's maths, so the numbers mean the same thing:
 *  - `AimSpeed` is the hard cap of one tick, `Strength` the share of the remaining angle,
 *    `Smoothing` the curve (`Linear`, `Bezier`, `Sigmoid`) — bigger error, faster correction, never
 *    a teleport;
 *  - `AimPoint` picks the part of the hitbox, `Predict`/`PredictSize` lead the target;
 *  - `Engine = AI` swaps the curve for the model LiquidBounce already trained on recorded human
 *    fights, completed by `AICorrection`;
 *  - `Silent = true` (default) sends the rotation only to the server, so the camera stays yours and
 *    the hits land anyway; `Silent = false` turns the camera with it, `FrameInterpolation` spreading
 *    the step over the frames of the tick.
 *
 * The blue circle under the current target is LiquidBounce's own [TargetRenderer] (the one KillAura
 * draws), configured in the `TargetRendering` group.
 *
 * Do not run this and [ModuleAimassist] together: they are the same engine, and enabling one turns
 * the other off.
 */
object ModuleAimbotAI : ClientModule("AimbotAI", Categories.LIQUIDADDON) {

    /** The aimbot's own settings (see [AimbotValues]): same engine, different list. */
    private val values = AimbotValues(this)

    /** The engine the Aimassist runs, told to aim on its own through [AimbotValues.autoAim]. */
    private val core = AimassistCore(this, values)

    /**
     * The circle under the target, exactly what KillAura and Aimassist draw: LiquidBounce's own
     * [TargetRenderer] — the blue ring (`Circle` / `GlowingCircle`, outline `0x007CFF`) with its
     * radius, inner radius, height mode and colours configurable in the GUI. It follows the target
     * the engine picked and draws nothing while there is none.
     *
     * It is only a renderer: it never touches the aim.
     */
    @Suppress("unused")
    private val targetRendering = tree(TargetRenderer(this) { core.target })

    /** Friction: unused by the aimbot (`Friction` is fixed to 0), kept for the shared engine. */
    @Suppress("unused")
    private val mouseHandler = handler<MouseRotationEvent> { core.onMouseRotation(it) }

    /** Attack synchronisation: a click is held until the rotation the server has is on the entity. */
    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { core.onAttack(it) }

    /** The tick: pick the target, aim at it, hand the rotation out. */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> { core.tick() }

    /** Frame interpolation: the tick's step drawn slice by slice. */
    @Suppress("unused")
    private val renderHandler = handler<GameRenderEvent> { core.onRender() }

    override val tag: String
        get() = core.targetTag

    override fun onEnabled() {
        // The two modules are the same engine: letting both write the rotation would double it.
        if (ModuleAimassist.enabled) ModuleAimassist.enabled = false
        core.onEnable()
        super.onEnabled()
    }

    override fun onDisabled() {
        core.onDisable()
        super.onDisabled()
    }
}
