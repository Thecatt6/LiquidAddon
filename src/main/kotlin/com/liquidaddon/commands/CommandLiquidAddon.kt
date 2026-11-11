package com.liquidaddon.commands

import com.liquidaddon.aiming.AiCoreRegistry
import com.liquidaddon.aiming.AiStats
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
 * packet, and a rotation flag that lands on such a tick is not a flag on a shaped step.
 */
object CommandLiquidAddon : CommandRegistrar {

    override fun register(dispatcher: CommandDispatcher<ClientCommandSource>) {
        dispatcher.register("liquidaddon") {
            literal("ai") {
                exec {
                    val cores = AiCoreRegistry.registered()
                    if (cores.isEmpty()) {
                        chat("§7[LiquidAddon] §7nessun motore di mira registrato in questa sessione")
                        return@exec 1
                    }
                    for (core in cores) {
                        // The stall suffix is the measurable half of the stall guard: a flag that
                        // lands on a session with freezes in it is a different fact from a flag
                        // that lands on a clean one.
                        val stalls = if (core.stallCount > 0) {
                            " | stalls %d, last %dms".format(core.stallCount, core.lastStallMs)
                        } else {
                            ""
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
