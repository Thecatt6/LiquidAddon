package com.liquidaddon

import com.liquidaddon.commands.CommandExample
import com.liquidaddon.modules.ModuleExample
import com.liquidaddon.modules.ModuleHitSelect
import net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon

/**
 * Entry point dell'add-on, dichiarato in fabric.mod.json sotto liquidbounce entrypoint.
 */
class Addon : LiquidBounceAddon() {

    override val categories = listOf(Categories.EXAMPLE)

    override fun onInitialize() {
        logger.info("LiquidAddon starting up")

        registerModules(ModuleExample, ModuleHitSelect)
        registerCommand(CommandExample)
    }

    /**
     * Le impostazioni sono state ripristinate dal disco a questo punto.
     */
    override fun onStarted() {
        logger.info("Example module is ${if (ModuleExample.enabled) "enabled" else "disabled"}")
    }

    override fun onStopping() {
        logger.info("LiquidAddon shutting down")
    }

}
