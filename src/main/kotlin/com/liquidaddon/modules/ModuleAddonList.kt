package com.liquidaddon.modules

import com.liquidaddon.Categories
import com.liquidaddon.ui.ArrayListLayout
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.render.Color4b

/**
 * The array-list of the theme: every enabled module, stacked widest-first against the right edge
 * of the screen, name in a teal gradient and its `Mode` in grey on the same row, a dark box behind
 * the pair — the shape of the reference screenshot, drawn over the overlay.
 *
 * The list is a reader, not a registry: it walks [ModuleManager] every frame, so a module that
 * gets enabled anywhere in the client — LiquidBounce's own or this add-on's — appears on the next
 * frame without being listed here, and one that gets disabled leaves. Modules that carry a
 * `Mode` choice show its active value as the grey suffix, the way the screenshot reads
 * "Velocity Normal"; a module without one is its name alone, the way it reads "ESP". Hidden
 * modules and this module itself stay out: a list that lists its own lister is a list that never
 * stops growing.
 *
 * All the geometry lives in [ArrayListLayout], a pure class with its own tests; this module only
 * measures text with the game font, reads the enabled modules, and paints what the layout placed.
 */
object ModuleAddonList : ClientModule("AddonList", Categories.LIQUIDADDON) {

    private val gradient by boolean("Gradient", true)
    private val colorTop by color("ColorTop", Color4b(86, 214, 214, 255))
    private val colorBottom by color("ColorBottom", Color4b(48, 168, 184, 255))
    private val suffixColor by color("SuffixColor", Color4b(155, 155, 155, 255))
    private val background by boolean("Background", true)
    private val backgroundColor by color("BackgroundColor", Color4b(14, 14, 16, 140))
    private val shadow by boolean("TextShadow", true)
    private val spacing by int("Spacing", 2, 0..8, "px")
    private val margin by int("Margin", 4, 0..40, "px")
    private val topMargin by int("TopMargin", 2, 0..400, "px")

    private val renderHandler = handler<OverlayRenderEvent> { event ->
        draw(event)
    }

    private fun draw(event: OverlayRenderEvent) {
        val font = mc.font
        val spaceWidth = font.width(" ")
        val rows = ModuleManager.modules
            .filter { it.enabled && !it.hidden && it !== this }
            .map { module ->
                val suffix = modeSuffix(module)
                ArrayListLayout.Row(
                    name = module.name,
                    suffix = suffix,
                    nameWidth = font.width(module.name),
                    suffixWidth = if (suffix.isNotEmpty()) font.width(suffix) else 0,
                )
            }
        if (rows.isEmpty()) return

        val layout = ArrayListLayout(
            rowHeight = font.lineHeight + 2,
            spacing = spacing,
            pad = 3,
            spaceWidth = spaceWidth,
        )
        val placed = layout.place(
            rows = rows,
            anchorRight = mc.window.guiScaledWidth - margin,
            top = topMargin,
            colorTop = colorTop.argb,
            colorBottom = colorBottom.argb,
            gradient = gradient,
        )

        val context = event.context
        for (row in placed) {
            if (background) {
                context.fill(row.bgLeft, row.bgTop, row.bgRight, row.bgBottom, backgroundColor.argb)
            }
            context.drawString(font, row.name, row.nameX, row.y + 1, row.color, shadow)
            val suffixX = row.suffixX
            if (suffixX != null) {
                context.drawString(font, row.suffix, suffixX, row.y + 1, suffixColor.argb, shadow)
            }
        }
    }

    /**
     * The active value of the module's `Mode` choice, the grey half of a row. Modules keep their
     * settings in nested groups, so the walk descends a few levels; the first `Mode` it meets is
     * the one the screenshot shows, and a module without any answers with an empty string.
     */
    private fun modeSuffix(module: ClientModule): String {
        fun walk(group: ValueGroup, depth: Int): String? {
            if (depth > 3) return null
            val children = group.get()
            for (child in children) {
                if (child is ModeValueGroup<*> && child.name.equals("Mode", ignoreCase = true)) {
                    return child.activeMode.name
                }
            }
            for (child in children) {
                if (child is ValueGroup && child !is ModeValueGroup<*>) {
                    walk(child, depth + 1)?.let { return it }
                }
            }
            return null
        }
        return walk(module, 0).orEmpty()
    }
}
