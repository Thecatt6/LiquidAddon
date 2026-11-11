package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.ui.VapeLayout
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
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

/**
 * The click GUI in the style of the reference screenshots: a dark sidebar of categories with a
 * search field, columns of module cards flowing to the right, an enabled card paper-white with
 * near-black text and a disabled one dark with grey text, three dots on every card that expand it
 * into the rows of its settings — a switch for a boolean, a track and knob for a ranged value, a
 * button and a dropdown for a mode.
 *
 * The menu is a reader of the client, not a registry of it: categories come from whatever
 * [ModuleManager] holds, cards from whatever is enabled or can be, settings from the values the
 * modules declare. Value kinds the menu has no control for (binds, colours, lists, curves) are
 * left out of the expanded card instead of being drawn as dead rows — the LiquidBounce GUI still
 * edits them, and this one stays honest about what it can touch.
 *
 * All geometry lives in [VapeLayout], a pure class with its own tests; this screen measures text,
 * paints the rectangles the layout placed and routes clicks back through it. The palette is the
 * one measured on the reference screenshot ([VapeLayout.PANEL] and friends).
 *
 * The GUI and the LiquidBounce one are mutually exclusive: opening this turns [ModuleClickGui]
 * off, and opening that one closes this — one menu on screen at a time, never two stacks of
 * panels fighting over the same click.
 */
object ModuleAddonClickGui : ClientModule("AddonClickGui", Categories.LIQUIDADDON) {

    private var screen: AddonClickGuiScreen? = null

    override fun onEnabled() {
        ModuleClickGui.enabled = false
        val opened = AddonClickGuiScreen()
        screen = opened
        mc.gui.setScreen(opened)
    }

    override fun onDisabled() {
        // MC 26 moved the screen accessor under `mc.gui`, the same place setScreen lives.
        if (mc.gui.screen() === screen) {
            mc.gui.setScreen(null)
        }
        screen = null
    }

    /** The other menu opens while this one is up: this one steps aside. */
    private val exclusion = handler<ModuleToggleEvent> { event ->
        if (event.enabled && event.moduleName == ModuleClickGui.name && running) {
            enabled = false
        }
    }

    /** Typing goes to the search field while it has the focus. */
    private val typing = handler<KeyboardCharEvent> { event ->
        screen?.typeChar(event.codePoint)
    }
}

/**
 * The screen itself: state (selected category, search query, expanded cards, open dropdown,
 * dragged slider), painting, and the routing of mouse and keys through [VapeLayout.hit].
 */
internal class AddonClickGuiScreen : Screen("LiquidAddon".asPlainText()) {

    private var selectedCategory = 0
    private var query = ""
    private var searchFocused = false
    private val expanded = mutableSetOf<String>()
    private var dragging: Pair<Int, Int>? = null
    private var openDropdown: OpenDropdown? = null

    private data class OpenDropdown(val module: Int, val row: Int, val names: List<String>)

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

    // --- what the menu shows ------------------------------------------------------------------

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

    /** The settings a card expands into, in draw order, with the kind of control each one gets. */
    private fun settingsOf(module: ClientModule): List<Value<*>> {
        val out = mutableListOf<Value<*>>()
        fun take(value: Value<*>) {
            when {
                value is ModeValueGroup<*> -> out += value
                value.valueType == ValueType.BOOLEAN -> out += value
                value.valueType == ValueType.INT -> out += value
                value.valueType == ValueType.FLOAT -> out += value
                else -> Unit
            }
        }
        for (child in module.get()) {
            if (child is ValueGroup && child !is ModeValueGroup<*>) {
                child.get().forEach(::take)
            } else {
                take(child)
            }
        }
        return out
    }

    private fun kindOf(value: Value<*>): VapeLayout.RowKind = when {
        value is ModeValueGroup<*> -> VapeLayout.RowKind.MODE
        else -> if (value.valueType == ValueType.BOOLEAN) VapeLayout.RowKind.SWITCH else VapeLayout.RowKind.SLIDER
    }

    // --- painting ------------------------------------------------------------------------------

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, -1)
    }

    // --- clicks ---------------------------------------------------------------------------------

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() != 0) return super.mouseClicked(click, doubled)
        val layout = VapeLayout(width, height)
        val cats = categories()
        val modules = shownModules(cats.getOrElse(selectedCategory) { "" })
        val specs = modules.map { m ->
            val settings = if (expanded.contains(m.name)) settingsOf(m) else emptyList()
            VapeLayout.CardSpec(expanded = expanded.contains(m.name), rows = settings.map(::kindOf))
        }
        val cards = layout.placeCards(specs)
        val px = click.x.toInt()
        val py = click.y.toInt()
        val drop = openDropdown
        val hit = layout.hit(
            px, py, cards, specs,
            if (drop == null) null else Triple(drop.module, drop.row, drop.names.size),
            cats.size,
        )
        when (hit) {
            is VapeLayout.Element.Category -> {
                selectedCategory = hit.index
                expanded.clear()
                openDropdown = null
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
                openDropdown = null
            }
            is VapeLayout.Element.Switch -> {
                val value = settingsOf(modules[hit.module])[hit.row]
                setAny(value, !(value.get() as Boolean))
            }
            is VapeLayout.Element.Slider -> {
                searchFocused = false
                dragging = Pair(hit.module, hit.row)
                slideTo(layout, cards, hit.module, hit.row, modules, px)
            }
            is VapeLayout.Element.ModeButton -> {
                val group = settingsOf(modules[hit.module])[hit.row] as ModeValueGroup<*>
                openDropdown = OpenDropdown(hit.module, hit.row, group.modes.map { it.name })
            }
            is VapeLayout.Element.ModeItem -> {
                val group = settingsOf(modules[hit.module])[hit.row] as ModeValueGroup<*>
                drop?.names?.getOrNull(hit.mode)?.let { group.setByString(it) }
                openDropdown = null
            }
            null -> {
                if (drop != null) openDropdown = null else searchFocused = false
            }
        }
        return true
    }

    override fun mouseDragged(click: MouseButtonEvent, offsetX: Double, offsetY: Double): Boolean {
        val drag = dragging ?: return super.mouseDragged(click, offsetX, offsetY)
        if (click.button() != 0) return super.mouseDragged(click, offsetX, offsetY)
        val layout = VapeLayout(width, height)
        val cats = categories()
        val modules = shownModules(cats.getOrElse(selectedCategory) { "" })
        val specs = modules.map { m ->
            val settings = if (expanded.contains(m.name)) settingsOf(m) else emptyList()
            VapeLayout.CardSpec(expanded = expanded.contains(m.name), rows = settings.map(::kindOf))
        }
        val cards = layout.placeCards(specs)
        slideTo(layout, cards, drag.first, drag.second, modules, click.x.toInt())
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(click)
    }


    private fun slideTo(
        layout: VapeLayout,
        cards: List<VapeLayout.Rect>,
        module: Int,
        row: Int,
        modules: List<ClientModule>,
        px: Int,
    ) {
        val value = settingsOf(modules[module]).getOrNull(row) ?: return
        val info = rangedInfo(value) ?: return
        val track = layout.controlRect(layout.rowRect(cards[module], row), VapeLayout.RowKind.SLIDER)
        val frac = ((px - track.x).toFloat() / track.w)
        val stepped = VapeLayout.sliderValue(info.first, info.second, if (info.first % 1 == 0f && info.second % 1 == 0f) 1f else 0.01f, frac)
        if (value.valueType == ValueType.INT) {
            setAny(value, stepped.toInt())
        } else {
            setAny(value, stepped)
        }
    }

    private data class Ranged(val min: Float, val max: Float, val current: Float)

    private fun rangedInfo(value: Value<*>): Ranged? {
        val ranged = value as? RangedValue<*> ?: return null
        return when (val range = ranged.range) {
            is IntRange -> Ranged(range.first.toFloat(), range.last.toFloat(), (value.get() as Int).toFloat())
            is ClosedFloatingPointRange<*> -> Ranged(
                (range.start as Number).toFloat(),
                (range.endInclusive as Number).toFloat(),
                (value.get() as Number).toFloat(),
            )
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setAny(value: Value<*>, newValue: Any) {
        (value as Value<Any>).set(newValue)
    }
}
