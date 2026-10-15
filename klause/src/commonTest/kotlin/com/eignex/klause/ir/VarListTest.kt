package com.eignex.klause.ir

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VarListTest {

    @Test
    fun `bounds-reasoning factor should declare no span ints`() {
        val linear = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5)

        assertIs<IntVars>(linear.variables)
        assertEquals(listOf(0, 1), linear.variables.ints.toList())
        assertTrue(linear.variables.spanInts.isEmpty())
    }

    @Test
    fun `value-enumerating factor should declare span ints`() {
        val allDifferent = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)

        assertEquals(listOf(0, 1, 2), allDifferent.variables.spanInts.toList())
    }

    @Test
    fun `mixed vars should keep roles separate and list all ints`() {
        val mixed = MixedVars(spanInts = intArrayOf(7), boundInts = intArrayOf(8, 9), boolVars = intArrayOf(2))

        assertEquals(listOf(7), mixed.spanInts.toList())
        assertEquals(listOf(7, 8, 9), mixed.ints.toList())
        assertEquals(listOf(2), mixed.boolVars.toList())
    }

    @Test
    fun `factor should declare every constrained real column`() {
        val product = RealProduct(
            intOperand = 0,
            realOperand = 1,
            result = 2,
            realOperandLo = 0.0,
            realOperandHi = 4.0,
        )

        assertEquals(listOf(0), product.variables.spanInts.toList())
        assertEquals(listOf(1, 2), product.variables.reals.toList())
    }

    @Test
    fun `bool vars should be raw variable ids instead of literals`() {
        val clause = Clause(intArrayOf(Lit.make(3, true), Lit.make(4, false)))

        assertEquals(listOf(3, 4), clause.variables.boolVars.toList())
        assertEquals(listOf(3, 4), clause.boolVars.toList())
    }
}
