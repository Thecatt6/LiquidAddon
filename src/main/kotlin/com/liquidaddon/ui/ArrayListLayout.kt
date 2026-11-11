package com.liquidaddon.ui

/**
 * The geometry of an array-list of enabled modules, the overlay the screenshot theme asks for:
 * rows stacked top to bottom, widest first, every row ending at the same right edge, the name in
 * one colour and its mode suffix in another, a dark box behind the pair.
 *
 * The class knows nothing about fonts, screens or modules: it is handed the rows with their text
 * widths already measured (only a font knows how wide "Velocity" is) and the anchor the list hangs
 * from, and it answers where every piece of text and every background box goes, plus the colour
 * of the name on that row. That separation is the whole point — the layout is a list of
 * arithmetic facts, testable without a game running (see `ArrayListLayoutTest`), while the module
 * that draws it stays a thin adapter over the render context.
 *
 * Colours are plain ARGB ints, the unit the Minecraft overlay draws in, so the gradient needs no
 * colour type of its own: [lerpColor] walks the four channels independently.
 */
internal class ArrayListLayout(
    private val rowHeight: Int,
    private val spacing: Int,
    private val pad: Int,
    private val spaceWidth: Int,
) {

    /**
     * One enabled module, with the widths of its two texts as the font measured them. An empty
     * [suffix] is a module with no `Mode` choice: the row is the name alone.
     */
    data class Row(val name: String, val suffix: String, val nameWidth: Int, val suffixWidth: Int) {
        fun textWidth(spaceWidth: Int): Int =
            nameWidth + if (suffix.isNotEmpty()) spaceWidth + suffixWidth else 0
    }

    /** Where one row lands: text origins, background box, and the name colour of that row. */
    data class Placed(
        val name: String,
        val suffix: String,
        val nameX: Int,
        val suffixX: Int?,
        val y: Int,
        val bgLeft: Int,
        val bgRight: Int,
        val bgTop: Int,
        val bgBottom: Int,
        val color: Int,
    )

    /**
     * Places [rows] under the anchor: widest row first, every row's text ending [pad] left of
     * [anchorRight] and every background box ending exactly at it, row [i] at
     * `top + i * (rowHeight + spacing)`. The name colour walks from [colorTop] to [colorBottom]
     * over the rows when [gradient] is on — the vertical fade of the theme — and sits on
     * [colorTop] when it is off or the list has a single row.
     */
    fun place(
        rows: List<Row>,
        anchorRight: Int,
        top: Int,
        colorTop: Int,
        colorBottom: Int,
        gradient: Boolean,
    ): List<Placed> {
        val ordered = rows.sortedByDescending { it.textWidth(spaceWidth) }
        val last = ordered.size - 1
        return ordered.mapIndexed { i, row ->
            val total = row.textWidth(spaceWidth)
            val nameX = anchorRight - pad - total
            val y = top + i * (rowHeight + spacing)
            Placed(
                name = row.name,
                suffix = row.suffix,
                nameX = nameX,
                suffixX = if (row.suffix.isNotEmpty()) nameX + row.nameWidth + spaceWidth else null,
                y = y,
                bgLeft = nameX - pad,
                bgRight = anchorRight,
                bgTop = y,
                bgBottom = y + rowHeight,
                color = if (gradient && last > 0) {
                    lerpColor(colorTop, colorBottom, i.toFloat() / last)
                } else {
                    colorTop
                },
            )
        }
    }

    companion object {

        /**
         * Linear blend of two ARGB colours, per channel, with [t] clamped: a gradient parameter
         * outside 0..1 would otherwise read as a colour from nowhere. Alpha blends too — a
         * semi-transparent top over an opaque bottom must fade through, not snap.
         */
        fun lerpColor(a: Int, b: Int, t: Float): Int {
            val tt = t.coerceIn(0f, 1f)
            fun channel(shift: Int): Int {
                val ca = (a shr shift) and 0xFF
                val cb = (b shr shift) and 0xFF
                return (ca + (cb - ca) * tt).toInt() and 0xFF
            }
            return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
