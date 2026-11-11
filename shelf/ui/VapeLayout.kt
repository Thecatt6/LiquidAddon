package com.liquidaddon.ui

/**
 * The geometry of a Vape-style click GUI, as pure arithmetic: sidebar of categories, a search bar,
 * and columns of module cards that flow left to right when a column fills up, each card able to
 * expand into the rows of its settings — a switch for a boolean, a track and knob for a ranged
 * value, a button and a dropdown for a mode.
 *
 * Nothing here knows about Minecraft, modules or values: the layout is handed the *shape* of the
 * menu (how many categories, which cards, how many settings rows each expanded card shows) and
 * answers rectangles and hit tests. The screen that draws it stays a painter and a router of
 * clicks; every fact about where things are lives here, where a test can look at it
 * (see `VapeLayoutTest`).
 *
 * Coordinates are GUI pixels, origin top-left, y down — the unit the overlay draws in.
 */
internal class VapeLayout(
    val screenW: Int,
    val screenH: Int,
    private val sidebarW: Int = SIDEBAR_W,
    private val cardH: Int = CARD_H,
    private val rowH: Int = ROW_H,
    private val colW: Int = COL_W,
    private val colGap: Int = COL_GAP,
    private val topY: Int = TOP_Y,
) {

    /** A rectangle, inclusive-exclusive: [x, x+w) by [y, y+h). */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Int, py: Int): Boolean = px >= x && px < x + w && py >= y && py < y + h
    }

    /** What a click can land on. */
    sealed interface Element {
        data class Category(val index: Int) : Element
        data class Search(val ignored: Unit = Unit) : Element
        data class Card(val module: Int) : Element
        data class Dots(val module: Int) : Element
        data class Switch(val module: Int, val row: Int) : Element
        data class Slider(val module: Int, val row: Int) : Element
        data class ModeButton(val module: Int, val row: Int) : Element
        data class ModeItem(val module: Int, val row: Int, val mode: Int) : Element
    }

    /** One module card: its name length is irrelevant here, only whether it is expanded and into how many rows. */
    data class CardSpec(val expanded: Boolean, val rows: List<RowKind>) {
        val rowCount: Int get() = if (expanded) rows.size else 0
    }

    /** The kind of control a settings row carries; the layout gives each its rectangle. */
    enum class RowKind { SWITCH, SLIDER, MODE }

    /** The whole sidebar: one row per category, plus the search field above them. */
    fun searchRect(): Rect = Rect(SIDEBAR_X, SEARCH_Y, sidebarW - 2 * PAD, SEARCH_H)

    fun categoryRect(index: Int): Rect =
        Rect(SIDEBAR_X, SEARCH_Y + SEARCH_H + PAD + index * (CAT_H + PAD), sidebarW - 2 * PAD, CAT_H)

    /** The area the card columns flow in: right of the sidebar, below the top margin. */
    fun columnsArea(): Rect = Rect(sidebarW + COL_GAP, topY, screenW - sidebarW - COL_GAP, screenH - topY - PAD)

    /** Height of one card, settings rows included. */
    fun cardHeight(spec: CardSpec): Int = cardH + spec.rowCount * rowH

    /**
     * Flows the cards into columns: a card starts where the previous one ended plus [CARD_GAP],
     * and the moment a card would cross the bottom of [columnsArea] a new column begins at its
     * top. A card is never split across columns — a settings list that does not fit stays whole
     * in the next column, the way Vape's panels do.
     */
    fun placeCards(specs: List<CardSpec>): List<Rect> {
        val area = columnsArea()
        val out = ArrayList<Rect>(specs.size)
        var col = 0
        var y = area.y
        for (spec in specs) {
            val h = cardHeight(spec)
            if (y + h > area.y + area.h && y > area.y) {
                col++
                y = area.y
            }
            out += Rect(area.x + col * (colW + colGap), y, colW, h)
            y += h + CARD_GAP
        }
        return out
    }

    /** The three-dot button on the right of a card's header line: the settings expander. */
    fun dotsRect(card: Rect): Rect = Rect(card.x + card.w - DOTS_W - 6, card.y + (cardH - DOTS_W) / 2, DOTS_W, DOTS_W)

    /** The rectangle of settings row [row] inside an expanded [card]. */
    fun rowRect(card: Rect, row: Int): Rect = Rect(card.x + 6, card.y + cardH + row * rowH, card.w - 12, rowH)

    /** The control rectangle of a row, by its kind: where the switch, track or mode button lives. */
    fun controlRect(row: Rect, kind: RowKind): Rect = when (kind) {
        RowKind.SWITCH -> Rect(row.x + row.w - SWITCH_W - 4, row.y + (rowH - SWITCH_H) / 2, SWITCH_W, SWITCH_H)
        RowKind.SLIDER -> Rect(row.x + row.w / 2, row.y + (rowH - TRACK_H) / 2, row.w / 2 - 4, TRACK_H)
        RowKind.MODE -> Rect(row.x + row.w / 2, row.y + 2, row.w / 2 - 4, rowH - 4)
    }

    /** The dropdown a mode button opens: [count] items hanging under [anchor], clamped to the screen. */
    fun dropdownRects(anchor: Rect, count: Int): List<Rect> {
        val top = (anchor.y + anchor.h + 2).coerceAtMost(screenH - count * DROP_H - 2)
        return (0 until count).map { Rect(anchor.x, top + it * DROP_H, anchor.w, DROP_H) }
    }

    /**
     * What a click at ([px], [py]) hit, most specific first: an open dropdown swallows everything,
     * then the dots beat the card they sit on, then a control beats the row, then the row's card,
     * then the sidebar. `null` is empty space — a click on the blurred world does nothing.
     */
    fun hit(
        px: Int,
        py: Int,
        cards: List<Rect>,
        specs: List<CardSpec>,
        openDropdown: Triple<Int, Int, Int>?,
        categoryCount: Int,
    ): Element? {
        openDropdown?.let { (module, row, modes) ->
            val anchor = controlRect(rowRect(cards[module], row), RowKind.MODE)
            dropdownRects(anchor, modes).forEachIndexed { i, r ->
                if (r.contains(px, py)) return Element.ModeItem(module, row, i)
            }
            return null
        }
        cards.forEachIndexed { i, card ->
            if (card.contains(px, py)) {
                if (dotsRect(card).contains(px, py)) return Element.Dots(i)
                specs[i].rows.forEachIndexed { r, kind ->
                    val row = rowRect(card, r)
                    if (row.contains(px, py)) {
                        return when (kind) {
                            RowKind.SWITCH -> Element.Switch(i, r)
                            RowKind.SLIDER -> Element.Slider(i, r)
                            RowKind.MODE -> Element.ModeButton(i, r)
                        }
                    }
                }
                return Element.Card(i)
            }
        }
        if (searchRect().contains(px, py)) return Element.Search()
        for (c in 0 until categoryCount) {
            if (categoryRect(c).contains(px, py)) return Element.Category(c)
        }
        return null
    }

    companion object {

        /**
         * The measured palette of the reference screenshot, ARGB: panels near-black, the sidebar
         * a shade above them, the search field the darkest thing on screen, an enabled card paper
         * white with near-black text and a disabled card dark with grey text.
         */
        const val PANEL = 0xFF090909.toInt()
        const val SIDEBAR = 0xFF101010.toInt()
        const val SEARCH_BG = 0xFF050505.toInt()
        const val CARD_ON = 0xFFFEFEFE.toInt()
        const val CARD_OFF = 0xFF161616.toInt()
        const val TEXT_ON_LIGHT = 0xFF0A0A0A.toInt()
        const val TEXT_ON_DARK = 0xFF9C9C9C.toInt()
        const val TEXT_HEADER = 0xFFFFFFFF.toInt()
        const val DIM = 0xA8000000.toInt()
        const val TRACK_BG = 0xFF2A2A2A.toInt()
        const val TRACK_FILL = 0xFFFEFEFE.toInt()

        const val SIDEBAR_W = 130
        const val SIDEBAR_X = 8
        const val SEARCH_Y = 64
        const val SEARCH_H = 22
        const val CAT_H = 26
        const val CARD_H = 26
        const val ROW_H = 20
        const val COL_W = 132
        const val COL_GAP = 10
        const val CARD_GAP = 4
        const val TOP_Y = 8
        const val PAD = 6
        const val DOTS_W = 12
        const val SWITCH_W = 22
        const val SWITCH_H = 12
        const val TRACK_H = 4
        const val DROP_H = 18

        /**
         * The value a slider drag reads at: [frac] of the track, 0..1, mapped into [min]..[max] and
         * snapped to [step]. The clamp is the contract — a drag past the knob's rail must not
         * invent a value outside the range.
         */
        fun sliderValue(min: Float, max: Float, step: Float, frac: Float): Float {
            val f = frac.coerceIn(0f, 1f)
            val raw = min + (max - min) * f
            return if (step <= 0f) raw else ((raw / step) + 0.5f).toInt() * step
        }

        /** Where a value sits on its track, 0..1; a collapsed range sits in the middle, not at NaN. */
        fun sliderFrac(value: Float, min: Float, max: Float): Float =
            if (max <= min) 0.5f else ((value - min) / (max - min)).coerceIn(0f, 1f)

        /**
         * The horizontal spans of a rounded rectangle: for each pixel row its inset x range, so a
         * painter can fill row by row and get corners of radius [r] without a shader. Outside the
         * corner bands the span is the full width.
         */
        fun roundedSpans(x: Int, y: Int, w: Int, h: Int, r: Int): List<Triple<Int, Int, Int>> {
            val rad = r.coerceAtMost(minOf(w / 2, h / 2))
            return (0 until h).map { dy ->
                val fromTop = dy
                val fromBottom = h - 1 - dy
                val d = minOf(fromTop, fromBottom)
                val inset = if (d >= rad) 0 else rad - sqrtOf(rad * rad - (rad - d - 1) * (rad - d - 1))
                Triple(y + dy, x + inset, x + w - inset)
            }
        }

        /** Integer circle math for [roundedSpans]: the ceiling of the square root, never negative. */
        private fun sqrtOf(v: Int): Int {
            if (v <= 0) return 0
            var s = 1
            while (s * s < v) s++
            return s
        }
    }
}
