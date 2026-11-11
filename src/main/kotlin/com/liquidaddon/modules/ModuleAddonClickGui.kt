package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.ui.VapeLayout
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/** PROBE 3: interactions without any value plumbing, render minimal. */
object ModuleAddonClickGui : ClientModule("AddonClickGui", Categories.LIQUIDADDON) {

    private var screen: AddonClickGuiScreen? = null

    override fun onEnabled() {
        ModuleClickGui.enabled = false
        val opened = AddonClickGuiScreen()
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

internal class AddonClickGuiScreen : Screen("LiquidAddon".asPlainText()) {

    private var selectedCategory = 0
    private var query = ""
    private var searchFocused = false
    private val expanded = mutableSetOf<String>()
    private var dragging: Pair<Int, Int>? = null

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        super.onClose()
        ModuleAddonClickGui.enabled = false
    }

    private fun categories(): List<String> =
        ModuleManager.map { it.category.tag }.distinct()

    private fun shownModules(category: String): List<ClientModule> {
        val q = query.lowercase()
        return ModuleManager.filter {
            it.category.tag == category &&
                !it.hidden &&
                !it.notActivatable &&
                it !== ModuleAddonClickGui &&
                (q.isEmpty() || it.name.lowercase().contains(q))
        }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, -1)
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        return super.mouseClicked(click, doubled)
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        dragging = null
        return super.mouseDragged(click, offsetX, offsetY)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(click)
    }
}
