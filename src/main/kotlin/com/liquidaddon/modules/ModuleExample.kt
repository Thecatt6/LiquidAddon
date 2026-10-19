package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat

/**
 * Un normale ClientModule: impostazioni, event handler e entry ClickGUI funzionano esattamente
 * come per un modulo built-in.
 */
object ModuleExample : ClientModule("Example", Categories.EXAMPLE) {

    private val interval by int("Interval", 100, 20..600, "ticks")
    private val greeting by text("Greeting", "Hello from LiquidAddon")

    @Suppress("unused")
    private val tickHandler = tickHandler {
        waitTicks(interval)
        chat(greeting)
    }

}
