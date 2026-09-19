package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CostPerturbationTest {
    @Test
    fun `removal restores tiny authoritative costs without reverse subtraction`() {
        val original = doubleArrayOf(1e-30, -1e-30, -0.0)
        val shifts = CostPerturbation(original)
        shifts.apply(arrayOf(VarStatus.AT_LOWER, VarStatus.AT_UPPER, VarStatus.FREE), true, CostPerturbationOptions(), { false })
        assertNotEquals(original[0], shifts.cost(0))

        shifts.restore()

        original.indices.forEach { assertEquals(original[it].toBits(), shifts.cost(it).toBits()) }
    }

    @Test
    fun `stall adds only nonbasic shifts and preserves the root cost of a new basic`() {
        val shifts = CostPerturbation(doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        shifts.apply(
            arrayOf(VarStatus.AT_LOWER, VarStatus.AT_UPPER, VarStatus.FREE, VarStatus.FIXED),
            true, CostPerturbationOptions(), { false },
        )
        val rootBasic = shifts.cost(0)
        val rootUpper = shifts.cost(1)

        shifts.apply(
            arrayOf(VarStatus.BASIC, VarStatus.AT_UPPER, VarStatus.FREE, VarStatus.FIXED),
            false, CostPerturbationOptions(), { false },
        )

        assertTrue(rootBasic > 1.0)
        assertEquals(rootBasic, shifts.cost(0))
        assertTrue(shifts.cost(1) < rootUpper)
        assertEquals(3.0, shifts.cost(2))
        assertEquals(4.0, shifts.cost(3))
    }

    @Test
    fun `nonfinite combined shifts are declined`() {
        val shifts = CostPerturbation(doubleArrayOf(Double.MAX_VALUE))

        val changed = shifts.apply(arrayOf(VarStatus.AT_LOWER), true, CostPerturbationOptions(), { false })

        assertEquals(0, changed)
        assertEquals(Double.MAX_VALUE, shifts.cost(0))
    }
}
