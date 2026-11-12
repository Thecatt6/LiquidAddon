package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LatticeFrictionTest {

    @Test
    fun `a keep share of one lets every count through`() {
        val lat = LatticeFriction()
        repeat(10) { assertEquals(1, lat.keep(1f, 1f)) }
        assertEquals(0f, lat.dropped, 1.0e-6f)
    }

    @Test
    fun `the kept counts are whole and average the share`() {
        val lat = LatticeFriction()
        var kept = 0
        repeat(10) { kept += lat.keep(1f, 0.8f) }
        assertEquals(8, kept, "eight counts out of ten at twenty percent friction")
        assertEquals(2f, lat.dropped, 1.0e-6f)
    }

    @Test
    fun `the drop pattern is irregular, not four fifths`() {
        val lat = LatticeFriction()
        val pattern = (1..10).map { lat.keep(1f, 0.8f) }
        assertTrue(pattern.contains(0), "some event keeps nothing: $pattern")
        assertTrue(pattern.filter { it == 1 }.size == 8, "and eight keep a whole count: $pattern")
    }

    @Test
    fun `the carry never grows past half a count`() {
        val lat = LatticeFriction()
        repeat(50) { lat.keep(3f, 0.35f) }
        // The exact share of 150 counts is 52.5; what was kept must sit within half a count.
        val keptTotal = 150 - lat.dropped
        assertTrue(kotlin.math.abs(keptTotal - 52.5f) <= 0.5f + 1.0e-4f, "carry bounded: $keptTotal")
    }

    @Test
    fun `zero keeps nothing and counts it all as dropped`() {
        val lat = LatticeFriction()
        assertEquals(0, lat.keep(4f, 0f))
        assertEquals(4f, lat.dropped, 1.0e-6f)
    }
}
