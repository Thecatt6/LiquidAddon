package com.liquidaddon.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrayListLayoutTest {

    private val space = 4
    private val layout = ArrayListLayout(rowHeight = 11, spacing = 2, pad = 3, spaceWidth = space)

    private fun row(name: String, suffix: String = ""): ArrayListLayout.Row {
        // One unit of width per character: enough to order rows and check the arithmetic.
        return ArrayListLayout.Row(name, suffix, name.length, suffix.length)
    }

    private val rows = listOf(
        row("Velocity", "Normal"),
        row("Scaffold", "Legit"),
        row("ESP"),
        row("AutoClicker", "FastPlace"),
    )

    @Test
    fun `rows stack widest first`() {
        val placed = layout.place(rows, anchorRight = 200, top = 0, colorTop = -1, colorBottom = -1, gradient = false)
        assertEquals(
            listOf("AutoClicker", "Velocity", "Scaffold", "ESP"),
            placed.map { it.name },
        )
    }

    @Test
    fun `every row ends at the same right edge`() {
        val placed = layout.place(rows, anchorRight = 200, top = 4, colorTop = -1, colorBottom = -1, gradient = false)
        for (p in placed) {
            assertEquals(200, p.bgRight, "background right of ${p.name}")
            val total = p.name.length + if (p.suffix.isNotEmpty()) space + p.suffix.length else 0
            assertEquals(200 - 3 - total, p.nameX, "name origin of ${p.name}")
        }
    }

    @Test
    fun `rows stack top to bottom without overlapping`() {
        val placed = layout.place(rows, anchorRight = 200, top = 4, colorTop = -1, colorBottom = -1, gradient = false)
        for (i in placed.indices) {
            assertEquals(4 + i * (11 + 2), placed[i].y)
            assertEquals(placed[i].y + 11, placed[i].bgBottom)
            if (i > 0) assertTrue(placed[i].y >= placed[i - 1].bgBottom, "row ${i} overlaps row ${i - 1}")
        }
    }

    @Test
    fun `a module without a mode has no suffix origin`() {
        val placed = layout.place(listOf(row("ESP")), anchorRight = 100, top = 0, colorTop = -1, colorBottom = -1, gradient = false)
        assertNull(placed.single().suffixX)
        val with = layout.place(
            listOf(row("Velocity", "Normal")), anchorRight = 100, top = 0, colorTop = -1, colorBottom = -1, gradient = false,
        )
        val p = with.single()
        assertEquals(p.nameX + "Velocity".length + space, p.suffixX)
    }

    @Test
    fun `the gradient starts and ends on the two colours and walks between them`() {
        val top = 0xFF56D6D6.toInt()
        val bottom = 0xFF30A8B8.toInt()
        val placed = layout.place(rows, anchorRight = 200, top = 0, colorTop = top, colorBottom = bottom, gradient = true)
        assertEquals(top, placed.first().color)
        assertEquals(bottom, placed.last().color)
        val reds = placed.map { (it.color shr 16) and 0xFF }
        for (i in 1 until reds.size) assertTrue(reds[i] <= reds[i - 1], "red must walk down: $reds")
    }

    @Test
    fun `without gradient every row wears the top colour`() {
        val placed = layout.place(rows, anchorRight = 200, top = 0, colorTop = 0xFF112233.toInt(), colorBottom = 0xFFAABBCC.toInt(), gradient = false)
        assertTrue(placed.all { it.color == 0xFF112233.toInt() })
    }

    @Test
    fun `lerp clamps outside the unit interval and hits the ends exactly`() {
        val a = 0x00000000
        val b = 0xFFFFFFFF.toInt()
        assertEquals(a, ArrayListLayout.lerpColor(a, b, -3f))
        assertEquals(b, ArrayListLayout.lerpColor(a, b, 7f))
        assertEquals(a, ArrayListLayout.lerpColor(a, b, 0f))
        assertEquals(b, ArrayListLayout.lerpColor(a, b, 1f))
        val mid = ArrayListLayout.lerpColor(a, b, 0.5f)
        assertEquals(0x7F, (mid shr 24) and 0xFF, "alpha blends too")
    }

    @Test
    fun `an empty list places nothing`() {
        assertEquals(0, layout.place(emptyList(), anchorRight = 10, top = 0, colorTop = -1, colorBottom = -1, gradient = true).size)
    }
}
