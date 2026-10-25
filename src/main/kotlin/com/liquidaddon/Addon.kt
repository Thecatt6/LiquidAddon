package com.liquidaddon

import com.liquidaddon.modules.ModuleHitFlick
import com.liquidaddon.modules.ModuleHitSelect
import com.liquidaddon.modules.ModuleAimbotAI
import com.liquidaddon.modules.ModuleScaffoldLegit
import net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon

/**
 * Entry point dell'add-on, dichiarato in fabric.mod.json sotto liquidbounce entrypoint.
 */
class Addon : LiquidBounceAddon() {

    override val categories = listOf(Categories.LIQUIDADDON)

    override fun onInitialize() {
        logger.info("LiquidAddon starting up")

        // Registra i moduli. CommandExample è opzionale — se non c'è il file, commenta la riga sotto
        registerModules(ModuleHitSelect, ModuleHitFlick, ModuleAimbotAI, ModuleScaffoldLegit)

        // Prova a registrare il comando se esiste
        try {
            val cmdClass = Class.forName("com.liquidaddon.commands.CommandExample")
            val instance = cmdClass.getField("INSTANCE").get(null) as net.ccbluex.liquidbounce.features.command.CommandRegistrar
            registerCommand(instance)
        } catch (_: Exception) {
            // Nessun comando, ignora
            try {
                // fallback per chi ha ancora com.example.addon
                val fallback = Class.forName("com.example.addon.commands.CommandExample")
                val inst2 = fallback.getField("INSTANCE").get(null) as net.ccbluex.liquidbounce.features.command.CommandRegistrar
                registerCommand(inst2)
            } catch (_: Exception) {}
        }
    }

    /**
     * Le impostazioni sono state ripristinate dal disco a questo punto.
     */
    override fun onStarted() {
    }

    override fun onStopping() {
        logger.info("LiquidAddon shutting down")
    }

}
