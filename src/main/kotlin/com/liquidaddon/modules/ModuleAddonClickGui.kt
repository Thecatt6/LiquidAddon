package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.ui.VapeLayout
import net.ccbluex.liquidbounce.event.events.KeyboardCharEvent
import net.ccbluex.liquidbounce.event.events.ModuleToggleEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.text.asPlainText
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/** PROBE 9: typing + keyPressed + routing, no value plumbing, render minimal. */
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

    private val typing = handler<KeyboardCharEvent> { event ->
        screen?.typeChar(event.codePoint)
    }
}

internal class AddonClickGuiScreen : Screen("LiquidAddon".asPlainText()) {

    private var selectedCategory = 0
    private var query = ""
    private var searchFocused = false
    private val expanded = mutableSetOf<String>()

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        super.onClose()
        ModuleAddonClickGui.enabled = false
    }

    fun typeChar(codePoint: Int) {
        if (!searchFocused) return
        if (codePoint == 8 || codePoint == 127) {
            if (query.isNotEmpty()) query = query.dropLast(1)
            return
        }
        if (codePoint >= 32) {
            query += String(Character.toChars(codePoint))
        }
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (searchFocused && input.key == InputConstants.KEY_BACKSPACE) {
            if (query.isNotEmpty()) query = query.dropLast(1)
            return true
        }
        return super.keyPressed(input)
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
        if (click.button() != 0) return super.mouseClicked(click, doubled)
        val layout = VapeLayout(width, height)
        val cats = categories()
        val modules = shownModules(cats.getOrElse(selectedCategory) { "" })
        val specs = modules.map { VapeLayout.CardSpec(expanded = false, rows = emptyList()) }
        val cards = layout.placeCards(specs)
        val px = click.x.toInt()
        val py = click.y.toInt()
        when (val hit = layout.hit(px, py, cards, specs, null, cats.size)) {
            is VapeLayout.Element.Category -> {
                selectedCategory = hit.index
                expanded.clear()
            }
            is VapeLayout.Element.Search -> searchFocused = true
            is VapeLayout.Element.Card -> {
                searchFocused = false
                val module = modules[hit.module]
                module.enabled = !module.enabled
            }
            is VapeLayout.Element.Dots -> {
                searchFocused = false
                val name = modules[hit.module].name
                if (!expanded.remove(name)) expanded.add(name)
            }
            else -> searchFocused = false
        }
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        return super.mouseReleased(click)
    }
}
