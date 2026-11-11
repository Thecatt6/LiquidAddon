package com.liquidaddon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VapeLayoutTest {

    private val layout = VapeLayout(screenW = 800, screenH = 440)
    private val plain = VapeLayout.CardSpec(expanded = false, rows = emptyList())

    @Test
    fun `cards stack in a column until it fills`() {
        val cards = layout.placeCards(List(3) { plain })
        val area = layout.columnsArea()
        assertEquals(area.x, cards[0].x)
        assertEquals(cards[0].x, cards[1].x, "second card stays in the first column")
        assertEquals(cards[0].y + VapeLayout.CARD_H + VapeLayout.CARD_GAP, cards[1].y)
        assertTrue(cards.all { it.w == VapeLayout.COL_W })
    }

    @Test
    fun `a card that would cross the bottom starts a new column whole`() {
        val area = layout.columnsArea()
        val tall = VapeLayout.CardSpec(expanded = true, rows = List(12) { VapeLayout.RowKind.SWITCH })
        // First a card that eats most of the column, then the tall one: it must not be split.
        val cards = layout.placeCards(listOf(tall, tall))
        assertEquals(cards[0].x + VapeLayout.COL_W + VapeLayout.COL_GAP, cards[1].x, "second card moves right")
        assertEquals(area.y, cards[1].y, "and restarts at the top")
        assertEquals(layout.cardHeight(tall), cards[1].h)
    }

    @Test
    fun `an expanded card grows by its rows`() {
        val spec = VapeLayout.CardSpec(expanded = true, rows = listOf(VapeLayout.RowKind.SWITCH, VapeLayout.RowKind.SLIDER, VapeLayout.RowKind.MODE))
        assertEquals(VapeLayout.CARD_H + 3 * VapeLayout.ROW_H, layout.cardHeight(spec))
        assertEquals(VapeLayout.CARD_H, layout.cardHeight(plain))
    }

    @Test
    fun `the dots beat the card they sit on`() {
        val cards = layout.placeCards(listOf(plain))
        val dots = layout.dotsRect(cards[0])
        val hit = layout.hit(dots.x + 1, dots.y + 1, cards, listOf(plain), null, 3)
        assertEquals(VapeLayout.Element.Dots(0), hit)
        val body = layout.hit(cards[0].x + 4, cards[0].y + 4, cards, listOf(plain), null, 3)
        assertEquals(VapeLayout.Element.Card(0), body)
    }

    @Test
    fun `a control beats its row and the row beats nothing else`() {
        val spec = VapeLayout.CardSpec(expanded = true, rows = listOf(VapeLayout.RowKind.SWITCH, VapeLayout.RowKind.SLIDER, VapeLayout.RowKind.MODE))
        val cards = layout.placeCards(listOf(spec))
        val card = cards[0]
        val switch = layout.controlRect(layout.rowRect(card, 0), VapeLayout.RowKind.SWITCH)
        assertEquals(VapeLayout.Element.Switch(0, 0), layout.hit(switch.x + 2, switch.y + 2, cards, listOf(spec), null, 3))
        val track = layout.controlRect(layout.rowRect(card, 1), VapeLayout.RowKind.SLIDER)
        assertEquals(VapeLayout.Element.Slider(0, 1), layout.hit(track.x + 2, track.y + 1, cards, listOf(spec), null, 3))
        val mode = layout.controlRect(layout.rowRect(card, 2), VapeLayout.RowKind.MODE)
        assertEquals(VapeLayout.Element.ModeButton(0, 2), layout.hit(mode.x + 2, mode.y + 2, cards, listOf(spec), null, 3))
    }

    @Test
    fun `an open dropdown swallows the clicks on its items and nothing else`() {
        val spec = VapeLayout.CardSpec(expanded = true, rows = listOf(VapeLayout.RowKind.MODE))
        val cards = layout.placeCards(listOf(spec))
        val anchor = layout.controlRect(layout.rowRect(cards[0], 0), VapeLayout.RowKind.MODE)
        val items = layout.dropdownRects(anchor, 3)
        val open = Triple(0, 0, 3)
        assertEquals(VapeLayout.Element.ModeItem(0, 0, 1), layout.hit(items[1].x + 2, items[1].y + 2, cards, listOf(spec), open, 3))
        assertNull(layout.hit(cards[0].x + 4, cards[0].y + 4, cards, listOf(spec), open, 3), "the card under an open dropdown is dead")
    }

    @Test
    fun `sidebar hits categories and the search field and empty space hits nothing`() {
        val cards = layout.placeCards(listOf(plain))
        val search = layout.searchRect()
        assertEquals(VapeLayout.Element.Search(), layout.hit(search.x + 3, search.y + 3, cards, listOf(plain), null, 3))
        val cat = layout.categoryRect(2)
        assertEquals(VapeLayout.Element.Category(2), layout.hit(cat.x + 3, cat.y + 3, cards, listOf(plain), null, 3))
        assertNull(layout.hit(700, 430, cards, listOf(plain), null, 3))
    }

    @Test
    fun `slider math clamps, snaps and round-trips`() {
        assertEquals(0f, VapeLayout.sliderValue(0f, 10f, 1f, -1f))
        assertEquals(10f, VapeLayout.sliderValue(0f, 10f, 1f, 2f))
        assertEquals(5f, VapeLayout.sliderValue(0f, 10f, 1f, 0.5f))
        assertEquals(2.5f, VapeLayout.sliderValue(0f, 10f, 2.5f, 0.24f), 1.0e-6f)
        assertEquals(0.5f, VapeLayout.sliderFrac(5f, 0f, 10f), 1.0e-6f)
        assertEquals(0.5f, VapeLayout.sliderFrac(3f, 3f, 3f), "a collapsed range sits in the middle")
        val v = VapeLayout.sliderValue(1f, 7f, 0.5f, VapeLayout.sliderFrac(4.5f, 1f, 7f))
        assertEquals(4.5f, v, 1.0e-6f)
    }

    @Test
    fun `rounded spans are full width in the middle and inset at the corners`() {
        val spans = VapeLayout.roundedSpans(10, 20, 40, 16, 4)
        assertEquals(16, spans.size)
        assertEquals(20, spans.first().first)
        assertTrue(spans.first().second > 10, "top row is inset")
        assertTrue(spans.first().third < 50)
        val middle = spans[8]
        assertEquals(10, middle.second)
        assertEquals(50, middle.third)
        assertEquals(spans.first().second, spans.last().second, "top and bottom corners match")
        for (s in spans) assertTrue(s.second <= s.third, "a span never inverts: $s")
    }

    @Test
    fun `a dropdown near the bottom edge stays on screen`() {
        val anchor = VapeLayout.Rect(400, layout.screenH - 30, 60, 16)
        val items = layout.dropdownRects(anchor, 4)
        assertTrue(items.last().y + items.last().h <= layout.screenH, "the list clamps up into the screen")
    }
}
