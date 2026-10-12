package com.eignex.klause.solver.objective

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinearObjectiveTest {
    @Test
    fun `coefficient buffers cannot change an objective value`() {
        val bools = longArrayOf(2)
        val ints = longArrayOf(3)
        val reals = doubleArrayOf(4.0)
        val objective = LinearObjective(bools, ints, constant = 1, realCoefficients = reals)
        val sample = Sample(booleanArrayOf(true), longArrayOf(5), doubleArrayOf(0.5))
        val copied = objective.copy()

        bools[0] = 0
        ints[0] = 0
        reals[0] = 0.0
        objective.boolWeights[0] = 0
        objective.intCoefficients[0] = 0
        objective.realCoefficients[0] = 0.0
        val (booleanCoefficients, integerCoefficients) = copied
        val realCoefficients = copied.component4()
        booleanCoefficients[0] = 0
        integerCoefficients[0] = 0
        realCoefficients[0] = 0.0

        assertEquals(BigFraction.ofLong(20), objective.evaluateExact(sample))
        assertEquals(objective, copied)
    }


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
