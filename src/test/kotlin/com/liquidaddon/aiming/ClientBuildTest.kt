package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientBuildTest {

    @Test
    fun `an open door names the client and the descriptor it bound`() {
        val line = ClientBuild.describe(
            "0.41.0",
            ClientBuild.Door.OPEN,
            "RotationTarget, Priority, ClientModule"
        )
        assertEquals(
            "client 0.41.0 | rotation door open (RotationTarget, Priority, ClientModule)",
            line
        )
    }

    @Test
    fun `a shut door is the loud one, and carries the reason`() {
        val line = ClientBuild.describe("0.40.0", ClientBuild.Door.SHUT, "no overload takes this provider")
        assertTrue(line.contains("ROTATION DOOR SHUT"), line)
        assertTrue(line.contains("0.40.0"), line)
        assertTrue(line.contains("no overload takes this provider"), line)
    }

    @Test
    fun `an untried door says so instead of pretending`() {
        assertEquals(
            "client 0.42.0 | rotation door not tried yet",
            ClientBuild.describe("0.42.0", ClientBuild.Door.UNTRIED, "not attempted")
        )
    }

    @Test
    fun `a version nobody can read is unknown, never a guess`() {
        assertEquals("client unknown | rotation door not tried yet", ClientBuild.describe(null, ClientBuild.Door.UNTRIED, ""))
        assertEquals("client unknown | rotation door not tried yet", ClientBuild.describe("   ", ClientBuild.Door.UNTRIED, ""))
        // The unsubstituted placeholder of the client's own build template.
        val placeholder = "$" + "{version}"
        assertEquals("client unknown | rotation door not tried yet", ClientBuild.describe(placeholder, ClientBuild.Door.UNTRIED, ""))
    }

    @Test
    fun `a real version survives trimming and a snapshot suffix`() {
        assertEquals("0.42.0", ClientBuild.readable("  0.42.0 "))
        assertEquals("0.42.0+26.3-SNAPSHOT", ClientBuild.readable("0.42.0+26.3-SNAPSHOT"))
        assertNull(ClientBuild.readable(null))
        assertNull(ClientBuild.readable(""))
        assertNull(ClientBuild.readable("$" + "{version}"))
    }
}
