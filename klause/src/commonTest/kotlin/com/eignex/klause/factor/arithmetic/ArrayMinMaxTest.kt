package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArrayMinMaxTest {
    @Test
    fun `a singleton extremum equated to itself reduces to a tautology`() {
        for (max in listOf(false, true)) {
            val factor = ArrayMinMax(0, intArrayOf(0), max)

            val reduced = factor.structuralReduce(arrayOf(IntDomain(0, 2)))

            assertTrue(assertIs<FactorReduction.Rewrite>(reduced).replacement.isEmpty())
        }
    }
}
