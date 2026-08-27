package com.eignex.klause.solver.objective

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
}
