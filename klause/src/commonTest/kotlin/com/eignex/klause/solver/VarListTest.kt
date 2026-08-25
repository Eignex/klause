package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A factor states its variables once, and the kind carries what it needs from them: bounds, or values
 * it must be able to enumerate.
 */
class VarListTest {

    @Test
    fun `a factor that reasons over bounds demands no values`() {
        val linear = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5)

        assertIs<IntVars>(linear.variables)
        assertEquals(listOf(0, 1), linear.variables.ints.toList())
        assertTrue(linear.variables.spanInts.isEmpty(), "a bounds reader demands no enumerable column")
    }

    @Test
    fun `a factor that enumerates values says so in its declaration`() {
        val allDifferent = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)

        assertEquals(listOf(0, 1, 2), allDifferent.variables.spanInts.toList())
    }

    @Test
    fun `a mixed declaration keeps each role separate while reading as one list`() {
        val mixed = MixedVars(spanInts = intArrayOf(7), boundInts = intArrayOf(8, 9), boolVars = intArrayOf(2))

        assertEquals(listOf(7), mixed.spanInts.toList(), "only the enumerating role demands values")
        assertEquals(listOf(7, 8, 9), mixed.ints.toList(), "an occurrence scan still sees every column")
        assertEquals(listOf(2), mixed.boolVars.toList())
    }

    @Test
    fun `a mixed list composes once rather than on each read`() {
        val mixed = MixedVars(spanInts = intArrayOf(1), boundInts = intArrayOf(2))

        assertTrue(mixed.ints === mixed.ints, "the combined array is built at construction")
    }

    @Test
    fun `a single-kind list hands back its own array without copying`() {
        val columns = intArrayOf(4, 5)

        assertTrue(IntVars(columns).ints === columns)
        assertTrue(SpanIntVars(columns).spanInts === columns)
    }

    @Test
    fun `a factor declares every real column it constrains`() {
        val product = RealProduct(
            intOperand = 0,
            realOperand = 1,
            result = 2,
            realOperandLo = 0.0,
            realOperandHi = 4.0,
        )

        assertEquals(listOf(0), product.variables.spanInts.toList())
        assertEquals(listOf(1, 2), product.variables.reals.toList(), "both continuous columns are constrained")
    }

    @Test
    fun `boolean columns are declared as raw variable ids rather than encoded literals`() {
        val clause = Clause(intArrayOf(Lit.make(3, true), Lit.make(4, false)))

        assertEquals(listOf(3, 4), clause.variables.boolVars.toList())
        assertEquals(listOf(3, 4), clause.boolVars.toList())
    }

    @Test
    fun `a factor with no variables declares that too`() {
        assertTrue(NoVars.ints.isEmpty())
        assertTrue(NoVars.boolVars.isEmpty())
        assertTrue(NoVars.reals.isEmpty())
    }
}
