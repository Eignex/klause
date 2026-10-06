package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceRecessionTest {

    private class Case(
        val name: String,
        val factors: List<Factor>,
        val reals: List<Long>,
        val direction: List<Long>,
        val costs: DoubleArray = doubleArrayOf(-1.0, 0.0),
        val realLower: DoubleArray = doubleArrayOf(0.0, Double.NEGATIVE_INFINITY),
        val aux: Boolean = true,
    )

    // x in [0,3] fixed at 2; r0 >= 0 and r1 free; minimizing -r0 by default.
    private fun proves(case: Case): Boolean {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 3)),
            factors = case.factors.toTypedArray(),
            numRealVars = 2,
            realLower = case.realLower,
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
        )
        val exact = case.reals.map(BigFraction::ofLong)
        val point = Sample(
            booleanArrayOf(case.aux),
            longArrayOf(2L),
            DoubleArray(exact.size) { exact[it].toDouble() },
            exact,
        )
        return problem.provesUnbounded(
            LinearObjective(realCoefficients = case.costs),
            point,
            case.direction.map(BigFraction::ofLong),
        )
    }

    private fun row(coefficients: DoubleArray, op: LinearOp, bound: Long) =
        Linear(longArrayOf(1L), intArrayOf(0), coefficients, intArrayOf(0, 1), op, bound)

    private val open = row(doubleArrayOf(-1.0, 0.0), LinearOp.LE, 1L)
    private val sum = row(doubleArrayOf(1.0, 1.0), LinearOp.EQ, 2L)
    private val capped = row(doubleArrayOf(1.0, 0.0), LinearOp.LE, 10L)
    private val product = RealProduct(0, 0, 1, 0.0, Double.POSITIVE_INFINITY)
    private val atom =
        ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 5.0)

    @Test
    fun `a ray every factor keeps and the objective descends along proves unboundedness`() {
        val cases = listOf(
            Case("open row", listOf(open), listOf(1L, 0L), listOf(1L, 0L)),
            Case("equality moving both reals", listOf(sum), listOf(0L, 0L), listOf(1L, -1L)),
            Case("product at the fixed integer", listOf(product), listOf(1L, 2L), listOf(1L, 2L)),
            Case("complement of a false atom", listOf(atom), listOf(6L, 0L), listOf(1L, 0L), aux = false),
        )
        for (case in cases) assertEquals(true, proves(case), case.name)
    }

    @Test
    fun `a ray that leaves the model or fails to descend is declined`() {
        val ascending = doubleArrayOf(1.0, 0.0)
        val cases = listOf(
            Case("crosses a finite bound", listOf(open), listOf(1L, 0L), listOf(-1L, 0L), costs = ascending),
            Case("leaves a row", listOf(capped), listOf(1L, 0L), listOf(1L, 0L)),
            Case("objective does not descend", listOf(open), listOf(1L, 0L), listOf(1L, 0L), costs = ascending),
            Case("zero ray", listOf(open), listOf(1L, 0L), listOf(0L, 0L)),
            Case("point violates a row", listOf(open), listOf(0L, 0L), listOf(1L, 0L)),
            Case("product off the fixed integer", listOf(product), listOf(1L, 2L), listOf(1L, 1L)),
            Case("true atom bounds the ray", listOf(atom), listOf(1L, 0L), listOf(1L, 0L)),
        )
        for (case in cases) assertEquals(false, proves(case), case.name)
    }
}
