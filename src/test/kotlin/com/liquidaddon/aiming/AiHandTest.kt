package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals

class AiHandTest {

    @Test
    fun `with the model authoring, the paint comes off the step`() {
        // The daily layer (MouseNoise 2, Overshoot 10, StrengthSpread 20) under Engine = AI:
        // one count of noise, no invented overshoot, no spread of a pull the model does not use.
        assertEquals(
            AiHand.Layer(mouseNoise = 1, overshoot = 0, strengthSpread = 0),
            AiHand.layer(aiHandOn = true, engineAi = true, mouseNoise = 2, overshoot = 10, strengthSpread = 20)
        )
    }

    @Test
    fun `a noise of zero the player chose stays zero`() {
        // The cap takes wobble away; it never adds the one count somebody deliberately removed.
        assertEquals(
            AiHand.Layer(mouseNoise = 0, overshoot = 0, strengthSpread = 0),
            AiHand.layer(aiHandOn = true, engineAi = true, mouseNoise = 0, overshoot = 8, strengthSpread = 15)
        )
    }

    @Test
    fun `the curve keeps its whole hand - profile A is untouched`() {
        assertEquals(
            AiHand.Layer(mouseNoise = 2, overshoot = 10, strengthSpread = 20),
            AiHand.layer(aiHandOn = true, engineAi = false, mouseNoise = 2, overshoot = 10, strengthSpread = 20)
        )
    }

    @Test
    fun `aihand off means the layer is the layer, engine or not`() {
        assertEquals(
            AiHand.Layer(mouseNoise = 4, overshoot = 30, strengthSpread = 50),
            AiHand.layer(aiHandOn = false, engineAi = true, mouseNoise = 4, overshoot = 30, strengthSpread = 50)
        )
    }
}
