package com.eignex.klause.factor.scheduling.internals

import kotlin.test.Test
import kotlin.test.assertEquals

class CumulativeThetaTreeTest {
    @Test
    fun `reactivation should overwrite the prior contribution`() {
        val t = CumulativeThetaTree(n = 2, capacity = 1)
        t.setLeafOrder(intArrayOf(0, 1))
        t.activate(0, est = 0, taskEnergy = 100L)
        t.activate(0, est = 10, taskEnergy = 1L)
        t.activate(1, est = 20, taskEnergy = 1L)
        assertEquals(21L, t.envOfTheta())
        assertEquals(2L, t.energyOfTheta())
    }
}
