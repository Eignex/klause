package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProblemProfileTest {

    private val row = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4)

    private fun ints(vararg factors: Factor, hi: Long = 5, numRealVars: Int = 0) = Problem(
        0,
        2,
        Array(2) { IntDomain(0, hi) },
        arrayOf(*factors),
        numRealVars = numRealVars,
        realLower = DoubleArray(numRealVars),
        realUpper = DoubleArray(numRealVars) { 1.0 },
    )

    @Test
    fun `each model shape falls in its class`() {
        val openUpper = Bits(2).also { it.set(1) }
        val cases = listOf(
            "clauses" to Problem(
                2,
                0,
                emptyArray(),
                arrayOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false)))),
            ) to ProblemClass.Sat,
            "pseudo-Boolean row" to Problem(
                2,
                0,
                emptyArray(),
                arrayOf(PseudoBoolean(longArrayOf(2, 1), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 2)),
            ) to ProblemClass.PseudoBoolean,
            "finite integers" to ints(row) to ProblemClass.FiniteCp,
            "integers and reals" to ints(row, numRealVars = 1) to ProblemClass.MixedInteger,
            "reals alone" to Problem(
                0,
                0,
                emptyArray(),
                emptyArray(),
                numRealVars = 2,
                realLower = DoubleArray(2),
                realUpper = DoubleArray(2) { 1.0 },
            ) to ProblemClass.Continuous,
            "an open integer side" to Problem(
                numBoolVars = 0,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(9, 0), null, openUpper),
                factors = arrayOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 10)),
            ) to ProblemClass.Open,
        )

        for ((case, expected) in cases) {
            assertEquals(expected, ProblemProfile.classOf(case.second), case.first)
        }
    }

    @Test
    fun `a domain past the 32-bit range marks the model wide`() {
        assertTrue(ProblemProfile.of(ints(row, hi = Int.MAX_VALUE + 1L), optimizing = false).wide)
        assertFalse(ProblemProfile.of(ints(row), optimizing = false).wide)
    }

    @Test
    fun `a cumulative constraint marks the model scheduling`() {
        val cumulative = Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), capacity = 1)

        assertTrue(ProblemProfile.of(ints(cumulative), optimizing = true).scheduling)
        assertFalse(ProblemProfile.of(ints(row), optimizing = true).scheduling)
    }
}
