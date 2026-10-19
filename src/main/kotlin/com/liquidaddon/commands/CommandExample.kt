package com.liquidaddon.commands

import com.mojang.brigadier.CommandDispatcher
import net.ccbluex.liquidbounce.features.command.CommandRegistrar
import net.ccbluex.liquidbounce.features.command.brigadier.ClientCommandSource
import net.ccbluex.liquidbounce.features.command.brigadier.register
import net.ccbluex.liquidbounce.utils.client.chat

/**
 * Aggiunge `.example` all'albero dei comandi del client.
 */
object CommandExample : CommandRegistrar {

    override fun register(dispatcher: CommandDispatcher<ClientCommandSource>) {
        dispatcher.register("example") {
            exec {
                chat("LiquidAddon is loaded!")
                1
            }
        }
    }

}
