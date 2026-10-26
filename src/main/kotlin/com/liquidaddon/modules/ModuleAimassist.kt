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
 * Aimassist — an aim **assist**, not an aimbot.
 *
 * The module is a thin entry point: everything that aims lives in [AimassistCore], the engine it
 * shares with [ModuleAimbotAI]. The two modules declare the same settings ([AimValues]: same
 * sliders, same ranges, same presets, same defaults) and run the same code, so a capability added
 * here is a capability of the aimbot and vice versa.
 *
 * The engine never clicks, never picks the target for you and never takes the view away from your
 * hand: it only helps while the crosshair is already near an enemy, and it only covers a *share* of
 * what is left to correct, so the aim converges like a hand that is being helped rather than a hand
 * that has been replaced. In short:
 *
 *  - targets only inside a narrow cone around where you already point (`Fov`), closest to the
 *    crosshair first, sticky while it stays valid (`LockTarget`);
 *  - the pull is strongest while the crosshair is on the hitbox and fades to nothing at the edge of
 *    the cone (the "aim assist bubble");
 *  - `Strength` is the share of the remaining angle covered per tick, `MaxSpeed` the hard cap of a
 *    single tick, `Smoothing` the curve, so a 10° error becomes 3.5° then 2.3° — never a flick;
 *  - `Engine = AI` replaces the curve with the combat model LiquidBounce already trained on
 *    recorded human fights, scaled by the same `Strength` (`AICorrection` finishes the approach);
 *  - every step is snapped to whole mouse counts (`GcdFix`, `GcdDither`) with the rounding residual
 *    carried over, and `FrameInterpolation` spreads a tick's step over the frames of that tick;
 *  - `TurnCheck`, `FadeIn`, `OnlyOnCrosshair`, `ReactionDelay`/`ReactionRandom`, `IdleChance`,
 *    `DeadZone` and the `Jitter*` knobs keep it looking like a hand;
 *  - `Mode = Friction` damps the cursor while it glides over a hitbox (`Both` does both).
 *
 * `Silent = false` helps the camera itself; `Silent = true` goes through LiquidBounce's
 * `RotationManager` with `MoveFix`, at `IMPORTANT_FOR_USAGE_1` — i.e. below KillAura/Aimbot, which
 * always keep the rotation to themselves.
 *
 * Presets (`Blatant`, `Legit`, `Ghost`, `Closet`, `AI`) are full profiles, not just a speed: picking
 * one writes every slider, so the ClickGUI always shows what the module is really doing.
 *
 * The blue circle under the current target is LiquidBounce's own [TargetRenderer], the same one
 * KillAura and Aimbot draw, configured in the `TargetRendering` group.
 *
 * Do not run this and [ModuleAimbotAI] together: they are the same engine, and enabling one turns
 * the other off.
 */
object ModuleAimassist : ClientModule("Aimassist", Categories.LIQUIDADDON) {

    /** The values and presets, shared with [ModuleAimbotAI] (see [AimValues]). */
    private val values = AimValues(this)

    /** The engine: target selection, the gates, the maths, the mouse lattice, the rotation. */
    private val core = AimassistCore(this, values)

    /**
     * The circle under the target, exactly the one KillAura draws: LiquidBounce's [TargetRenderer],
     * so the blue ring (`Circle` / `GlowingCircle`, outline `0x007CFF`), its radius, its inner
     * radius, its height mode and its colours are configurable in the GUI like there. It follows
     * whatever the engine is currently helping with and draws nothing while there is no target.
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
        if (ModuleAimbotAI.enabled) ModuleAimbotAI.enabled = false
        core.onEnable()
        super.onEnabled()
    }

    override fun onDisabled() {
        core.onDisable()
        super.onDisabled()
    }
}
