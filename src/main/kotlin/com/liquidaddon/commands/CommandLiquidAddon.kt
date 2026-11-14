package com.liquidaddon.commands

import com.liquidaddon.aiming.AiCoreRegistry
import com.liquidaddon.aiming.AiStats
import com.liquidaddon.aiming.ClientBuild
import com.liquidaddon.aiming.ClientBuildProbe
import com.liquidaddon.aiming.RotationBridge
import com.mojang.brigadier.CommandDispatcher
import net.ccbluex.liquidbounce.features.command.CommandRegistrar
import net.ccbluex.liquidbounce.features.command.brigadier.ClientCommandSource
import net.ccbluex.liquidbounce.features.command.brigadier.register
import net.ccbluex.liquidbounce.utils.client.chat

/**
 * `/liquidaddon ai` — the telemetry of the aim engine, readable at the end of a fight without
 * opening a GUI or a log.
 *
 * One line per engine that exists in this session, in the shape [AiStats.format] documents:
 *
 * `[Aimassist] ai 94% used | fb: init 0, model 0, nan 0, reach 6% | step 1.20° vs curve 0.85° | corr 0.71 | aiStr 100`
 *
 * The line answers, in order: is the model really driving (used), why it was not (the fallback
 * families), how often the situation was outside its training (reach), whether its steps are the
 * steps of a hand or a nudge on top of the curve (step vs curve), and whether it still agrees with
 * the curve on the same input (corr) — the number the drift guard watches.
 *
 * A session that flew through freezes of the client or the server carries one field more —
 * `| stalls 2, last 2410ms` — the count and the last length of the gaps [StallGuard] opened its
 * window on: the resume tick of a freeze carries a queued mouse jump of tens of degrees in one
 * packet, and a rotation flag that lands on such a tick is not a flag on a shaped step. Every line
 * then closes with `| load 84%, onset 341ms, grace 176ms, anchor 62%`: the hardest turn a target
 * demanded against the cap of a single tick (the tick that resumes a freeze does not count: its
 * demand is the queued jump of the mouse, not the target's), the fade-in duration and curve the
 * engagement drew, the exit fade of the crosshair gate, and where the goal's height sat between box
 * centre and eyes.
 */
object CommandLiquidAddon : CommandRegistrar {

    override fun register(dispatcher: CommandDispatcher<ClientCommandSource>) {
        dispatcher.register("liquidaddon") {
            literal("ai") {
                exec {
                    // Which client this jar is running inside, and whether silent rotations can
                    // reach it: the two facts a NoSuchMethodError turns into guesswork otherwise.
                    chat("§7[LiquidAddon] §f" + ClientBuild.describe(
                        ClientBuildProbe.read(),
                        when {
                            RotationBridge.bound -> ClientBuild.Door.OPEN
                            RotationBridge.failed -> ClientBuild.Door.SHUT
                            else -> ClientBuild.Door.UNTRIED
                        },
                        RotationBridge.state
                    ))
                    val cores = AiCoreRegistry.registered()
                    if (cores.isEmpty()) {
                        chat("§7[LiquidAddon] §7nessun motore di mira registrato in questa sessione")
                        return@exec 1
                    }
                    for (core in cores) {
                        // The stall suffix is the measurable half of the stall guard: a flag that
                        // lands on a session with freezes in it is a different fact from a flag
                        // that lands on a clean one.
                        val stalls = buildString {
                            if (core.stallCount > 0) {
                                append(" | stalls %d, last %dms".format(core.stallCount, core.lastStallMs))
                            }
                            // The chase load says where the session sat against the cap of one
                            // tick, the onset how long this engagement's warm-up was drawn: the
                            // two numbers the humanizing shapes of the assist report about
                            // themselves (ChaseLoad, Onset).
                            append(
                                " | load %d%%, onset %dms, grace %dms, anchor %d%%, drop %d".format(
                                    core.peakLoadPct, core.lastOnsetMs, core.lastGraceMs, core.anchorPct,
                                    core.frictionDropped
                                )
                            )
                            // The stutter half: how many times the help fell away, how often the
                            // limiter had to cut a rise, and the biggest rise the gates asked for.
                            // `jump` is that ask, measured before the limiter: above a hundred it
                            // carries the engagement's jitter (see Slew.worstAsk), and what was
                            // actually given on that tick is at most the drawn rate either way.
                            append(
                                ", slew %d, dips %d, jump %d%%".format(
                                    core.slewBites, core.dipCount, core.worstGateJumpPct
                                )
                            )
                        }
                        val line = AiStats.format(core.stats.snapshot(), core.aiStrengthNow) + stalls
                        chat("§7[${core.ownerName}] §f$line")
                    }
                    1
                }
            }
        }
    }
}
