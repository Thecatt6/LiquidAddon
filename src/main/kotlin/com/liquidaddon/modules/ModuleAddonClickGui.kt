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
        val layout = VapeLayout(width, height)
        val cats = categories()
        if (selectedCategory >= cats.size) selectedCategory = 0
        val modules = shownModules(cats.getOrElse(selectedCategory) { "" })
        val specs = modules.map { m ->
            val settings = if (expanded.contains(m.name)) settingsOf(m) else emptyList()
            VapeLayout.CardSpec(expanded = expanded.contains(m.name), rows = settings.map(::kindOf))
        }
        val cards = layout.placeCards(specs)

        context.fill(0, 0, width, height, VapeLayout.DIM)

        // Sidebar: one dark column, the search field, then the categories, the selected one lit.
        fillRounded(context, layout, 4, 8, VapeLayout.SIDEBAR_W + 8, height - 16, 6, VapeLayout.SIDEBAR)
        context.text(mc.font, "LIQUIDADDON", 14, 24, VapeLayout.TEXT_HEADER, false)
        val search = layout.searchRect()
        fillRounded(context, layout, search.x, search.y, search.w, search.h, 4, VapeLayout.SEARCH_BG)
        val shownQuery = if (query.isEmpty() && !searchFocused) "Search" else query
        val queryColor = if (query.isEmpty() && !searchFocused) VapeLayout.TEXT_ON_DARK else VapeLayout.TEXT_HEADER
        context.text(mc.font, shownQuery + caret(), search.x + 6, search.y + 6, queryColor, false)
        cats.forEachIndexed { i, tag ->
            val rect = layout.categoryRect(i)
            val on = i == selectedCategory
            if (on) fillRounded(context, layout, rect.x, rect.y, rect.w, rect.h, 4, VapeLayout.CARD_ON)
            context.text(mc.font, tag, rect.x + 8, rect.y + 8, if (on) VapeLayout.TEXT_ON_LIGHT else VapeLayout.TEXT_ON_DARK, false)
        }

        // Cards.
        modules.forEachIndexed { i, module ->
            val card = cards[i]
            val on = module.enabled
            fillRounded(context, layout, card.x, card.y, card.w, card.h, 4, if (on) VapeLayout.CARD_ON else VapeLayout.CARD_OFF)
            val ink = if (on) VapeLayout.TEXT_ON_LIGHT else VapeLayout.TEXT_ON_DARK
            context.text(mc.font, module.name, card.x + 8, card.y + 8, ink, false)
            val dots = layout.dotsRect(card)
            for (d in 0..2) {
                context.fill(dots.x + 4, dots.y + d * 4, dots.x + 6, dots.y + d * 4 + 2, ink)
            }
            if (specs[i].expanded) {
                val settings = settingsOf(module)
                settings.forEachIndexed { r, value ->
                    val row = layout.rowRect(card, r)
                    context.text(mc.font, value.name, row.x + 4, row.y + 6, ink, false)
                    when (val kind = kindOf(value)) {
                        VapeLayout.RowKind.SWITCH -> {
                            val sw = layout.controlRect(row, kind)
                            val swOn = value.get() as Boolean
                            fillRounded(context, layout, sw.x, sw.y, sw.w, sw.h, sw.h / 2, if (swOn) VapeLayout.TRACK_FILL else VapeLayout.TRACK_BG)
                            val knobX = if (swOn) sw.x + sw.w - sw.h + 2 else sw.x + 2
                            fillRounded(context, layout, knobX, sw.y + 2, sw.h - 4, sw.h - 4, (sw.h - 4) / 2, if (swOn) VapeLayout.TEXT_ON_LIGHT else VapeLayout.CARD_ON)
                        }
                        VapeLayout.RowKind.SLIDER -> {
                            val tr = layout.controlRect(row, kind)
                            val bounds = rangedInfo(value)
                            if (bounds != null) {
                                val frac = VapeLayout.sliderFrac(bounds.current, bounds.min, bounds.max)
                                fillRounded(context, layout, tr.x, tr.y, tr.w, tr.h, 2, VapeLayout.TRACK_BG)
                                val done = (tr.w * frac).toInt().coerceAtLeast(2)
                                fillRounded(context, layout, tr.x, tr.y, done, tr.h, 2, if (on) VapeLayout.TEXT_ON_LIGHT else VapeLayout.TRACK_FILL)
                            }
                        }
                        VapeLayout.RowKind.MODE -> {
                            val mb = layout.controlRect(row, kind)
                            val group = value as ModeValueGroup<*>
                            fillRounded(context, layout, mb.x, mb.y, mb.w, mb.h, 3, if (on) VapeLayout.SEARCH_BG else VapeLayout.TRACK_BG)
                            context.text(mc.font, group.activeMode.name, mb.x + 5, mb.y + 5, if (on) VapeLayout.TEXT_HEADER else VapeLayout.TEXT_ON_DARK, false)
                        }
                    }
                }
            }
        }

        // An open dropdown paints over everything else.
        openDropdown?.let { drop ->
            val anchor = layout.controlRect(layout.rowRect(cards[drop.module], drop.row), VapeLayout.RowKind.MODE)
            layout.dropdownRects(anchor, drop.names.size).forEachIndexed { i, rect ->
                val hover = rect.contains(mouseX, mouseY)
                fillRounded(context, layout, rect.x, rect.y, rect.w, rect.h, 3, if (hover) VapeLayout.CARD_ON else VapeLayout.PANEL)
                context.text(
                    mc.font, drop.names[i], rect.x + 6, rect.y + 5,
                    if (hover) VapeLayout.TEXT_ON_LIGHT else VapeLayout.TEXT_HEADER, false,
                )
            }
        }
    }

    private fun caret(): String =
        if (searchFocused && System.currentTimeMillis() / 300 % 2 == 0L) "|" else ""

    private fun fillRounded(
        ctx: GuiGraphicsExtractor,
        layout: VapeLayout,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        r: Int,
        color: Int,
    ) {
        for (span in VapeLayout.roundedSpans(x, y, w, h, r)) {
            ctx.fill(span.second, span.first, span.third, span.first + 1, color)
        }
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
        val lay = VapeLayout(width, height)
        val cats = categories()
        val pool = shownModules(cats.getOrElse(selectedCategory) { "" })
        val specs = pool.map { m ->
            val settings = if (expanded.contains(m.name)) settingsOf(m) else emptyList()
            VapeLayout.CardSpec(expanded = expanded.contains(m.name), rows = settings.map(::kindOf))
        }
        val cardRects = lay.placeCards(specs)
        slideTo(lay, cardRects, drag.first, drag.second, pool, click.x.toInt())
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(click)
    }


    private fun slideTo(
        lay: VapeLayout,
        cardRects: List<VapeLayout.Rect>,
        moduleIndex: Int,
        rowIndex: Int,
        pool: List<ClientModule>,
        pointerX: Int
    ) {
        val target = settingsOf(pool[moduleIndex]).getOrNull(rowIndex) ?: return
        val bounds = rangedInfo(target) ?: return
        val track = lay.controlRect(lay.rowRect(cardRects[moduleIndex], rowIndex), VapeLayout.RowKind.SLIDER)
        val fraction = (pointerX - track.x).toFloat() / track.w.toFloat()
        val whole = bounds.min % 1f == 0f && bounds.max % 1f == 0f
        val stepSize = if (whole) 1f else 0.01f
        val picked = VapeLayout.sliderValue(bounds.min, bounds.max, stepSize, fraction)
        if (target.valueType == ValueType.INT) {
            setAny(target, picked.toInt())
        } else {
            setAny(target, picked)
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
