package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.aiming.AimValues
import com.liquidaddon.aiming.AimassistCore
import net.ccbluex.liquidbounce.event.events.GameRenderEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.render.TargetRenderer

/**
 * AimbotAI — the aim bot, running the **Aimassist engine**.
 *
 * It used to have its own aiming implementation (and therefore its own, smaller, set of bypass
 * tricks); now the aiming logic *is* the assist's: this module is a second entry point over the
 * same [AimassistCore] and the same settings ([AimValues]). Same sliders, same ranges, same
 * presets, same defaults, same code path per tick — whatever the assist survives, this survives,
 * because nothing is duplicated.
 *
 * The engine: targets only inside the cone around where you already point (`Fov`), sticky while
 * valid; the pull is full while the crosshair is on the hitbox and fades to nothing at the edge of
 * the cone; `Strength` is the share of the remaining angle per tick, `MaxSpeed` the hard cap,
 * `Smoothing` the curve, so the aim converges instead of snapping. `Engine = AI` swaps the curve for
 * the combat model LiquidBounce already trained on recorded human fights (`AICorrection` finishes
 * the approach). Every step is snapped to whole mouse counts with the residual carried over
 * (`GcdFix`, `GcdDither`), `FrameInterpolation` spreads it over the frames of the tick, and
 * `TurnCheck`, `FadeIn`, `OnlyOnCrosshair`, `ReactionDelay`/`ReactionRandom`, `IdleChance`,
 * `DeadZone` and `Jitter*` keep the motion human.
 *
 * For an aimbot-like behaviour pick the `Blatant` preset (wide cone, high strength, silent) or `AI`
 * (the model, silent); for a profile built to pass rotation statistics, `Ghost` or `Closet`.
 *
 * `Silent = true` (the presets above) sends the rotation only to the server, through LiquidBounce's
 * `RotationManager` at `IMPORTANT_FOR_USAGE_1`: below KillAura/Aimbot, so a real aura always keeps
 * the rotation it wants. `Silent = false` aims the camera itself.
 *
 * The blue circle under the current target is LiquidBounce's own [TargetRenderer] (the one KillAura
 * draws), configured in the `TargetRendering` group.
 *
 * Do not run this and [ModuleAimassist] together: they are the same engine, and enabling one turns
 * the other off.
 */
object ModuleAimbotAI : ClientModule("AimbotAI", Categories.LIQUIDADDON) {

    /** The values and presets, shared with [ModuleAimassist] (see [AimValues]). */
    private val values = AimValues(this)

    /** The engine: the same one the assist runs (see [AimassistCore]). */
    private val core = AimassistCore(this, values)

    /**
     * The circle under the target, exactly what KillAura and Aimassist draw: LiquidBounce's own
     * [TargetRenderer] — the blue ring (`Circle` / `GlowingCircle`, outline `0x007CFF`) with its
     * radius, inner radius, height mode and colours configurable in the GUI. It follows the target
     * the engine is aiming at and draws nothing while there is none.
     *
     * It is only a renderer: it never touches the aim.
     */
    @Suppress("unused")
    private val targetRendering = tree(TargetRenderer(this) { core.target })

    /** Friction: damped cursor deltas while the cursor glides over the hitbox. */
    @Suppress("unused")
    private val mouseHandler = handler<MouseRotationEvent> { core.onMouseRotation(it) }

    /** The tick: pick the target, apply the gates, compute and hand out the rotation. */
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
