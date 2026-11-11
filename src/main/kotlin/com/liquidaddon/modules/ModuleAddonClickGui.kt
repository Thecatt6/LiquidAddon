package com.liquidaddon.modules

import com.liquidaddon.Categories
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen

/** PROBE: minimal screen shell to bisect a CI compile failure. */
object ModuleAddonClickGui : ClientModule("AddonClickGui", Categories.LIQUIDADDON) {

    private var screen: ProbeScreen? = null

    override fun onEnabled() {
        ModuleClickGui.enabled = false
        val opened = ProbeScreen()
        screen = opened
        mc.gui.setScreen(opened)
    }

    override fun onDisabled() {
        if (mc.gui.screen() === screen) {
            mc.gui.setScreen(null)
        }
        screen = null
    }

    private val exclusion = handler<ModuleToggleEvent> { event ->
        if (event.enabled && event.moduleName == ModuleClickGui.name && running) {
            enabled = false
        }
    }
}

internal class ProbeScreen : Screen("LiquidAddon".asPlainText()) {
    override fun isPauseScreen(): Boolean = false
    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, -1)
    }
    override fun onClose() {
        super.onClose()
        ModuleAddonClickGui.enabled = false
    }
}
