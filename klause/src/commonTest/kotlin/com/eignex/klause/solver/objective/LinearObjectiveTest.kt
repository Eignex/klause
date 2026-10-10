package com.eignex.klause.solver.objective

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [LinearObjective.singleIntObjective] recognises the objective as one integer variable. Callers treat
 * that variable as standing for the whole objective — LP bounding posts its relaxation bound onto it —
 * so the recognition has to fail whenever any other term carries cost.
 */
class LinearObjectiveTest {
    @Test
    fun `exact evaluation preserves integer sums outside Long`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(8L))
        val sample = Sample(BooleanArray(0), longArrayOf(1L shl 61))

        assertEquals("18446744073709551616", objective.evaluateExact(sample).toString())
    }

    @Test
    fun `exact evaluation uses certified real values`() {
        val third = BigFraction.ofLong(3L).reciprocal()
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val sample = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(1.0 / 3.0), listOf(third))

        assertEquals(third, objective.evaluateExact(sample))
    }

    @Test
    fun `one weighted integer column is the single objective variable`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(0L, 3L, 0L))

        assertEquals(1, objective.singleIntObjective()?.varId)
    }

    @Test
    fun `a continuous term leaves no single objective variable`() {
        val objective = LinearObjective(
            intCoefficients = longArrayOf(0L, 3L, 0L),
            realCoefficients = doubleArrayOf(1.0),
        )

        assertNull(objective.singleIntObjective(), "the objective is the integer column plus a real one")
    }

    @Test
    fun `a zero continuous coefficient carries no cost and leaves the variable`() {
        val objective = LinearObjective(
            intCoefficients = longArrayOf(0L, 3L, 0L),
            realCoefficients = doubleArrayOf(0.0),
        )

        assertEquals(1, objective.singleIntObjective()?.varId)
    }

    @Test
    fun `a value past the 64-bit range is summed without wrapping`() {
        val objective = LinearObjective(intCoefficients = longArrayOf(8L))
        val sample = Sample(BooleanArray(0), longArrayOf(1L shl 61))

        assertEquals(1.8446744073709552e19, objective.evaluate(sample))
    }
}
