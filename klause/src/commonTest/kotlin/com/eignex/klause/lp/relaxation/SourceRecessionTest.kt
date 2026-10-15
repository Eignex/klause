package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
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
    fun `provesUnbounded should accept a product ray only when it keeps the chosen operand and exact product`() {
        val factor = ContinuousProduct(0, 2, 1, 2, doubleArrayOf(0.5), intArrayOf(Lit.make(0, true)))
        val problem = Problem(
            1,
            0,
            emptyArray(),
            arrayOf<Factor>(factor),
            numRealVars = 3,
            realLower = doubleArrayOf(0.0, 0.0, 0.0),
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY },
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0, 0.0, 0.0))
        val exact = listOf(BigFraction.ofLong(2), BigFraction.ONE, BigFraction.ofLong(2).reciprocal())
        val point = Sample(booleanArrayOf(true), longArrayOf(), doubleArrayOf(2.0, 1.0, 0.5), exact)
        val valid = listOf(2L, 1L, 0L).map(BigFraction::ofLong)
        val cases = listOf(
            valid to true,
            listOf(1L, 1L, 0L).map(BigFraction::ofLong) to false,
            listOf(2L, 1L, 1L).map(BigFraction::ofLong) to false,
        )
        for ((direction, expected) in cases) {
            assertEquals(expected, problem.provesUnbounded(objective, point, direction))
        }
        assertEquals(false, problem.provesUnbounded(objective, point.copy(bools = booleanArrayOf(false)), valid))
    }

    @Test
    fun `provesUnbounded should accept a ray every factor keeps and the objective descends along`() {
        val cases = listOf(
            Case("open row", listOf(open), listOf(1L, 0L), listOf(1L, 0L)),
            Case("equality moving both reals", listOf(sum), listOf(0L, 0L), listOf(1L, -1L)),
            Case(
                "maximum retaining its selected operand",
                listOf(RealArrayMinMax(0, intArrayOf(0, 1), max = true)),
                listOf(0L, 0L),
                listOf(1L, 0L),
            ),
            Case("product at the fixed integer", listOf(product), listOf(1L, 2L), listOf(1L, 2L)),
            Case("complement of a false atom", listOf(atom), listOf(6L, 0L), listOf(1L, 0L), aux = false),
        )
        for (case in cases) assertEquals(true, proves(case), case.name)
    }

    @Test
    fun `provesUnbounded should decline a ray that leaves the model or fails to descend`() {
        val ascending = doubleArrayOf(1.0, 0.0)
        val cases = listOf(
            Case("crosses a finite bound", listOf(open), listOf(1L, 0L), listOf(-1L, 0L), costs = ascending),
            Case("leaves a row", listOf(capped), listOf(1L, 0L), listOf(1L, 0L)),
            Case("objective does not descend", listOf(open), listOf(1L, 0L), listOf(1L, 0L), costs = ascending),
            Case("zero ray", listOf(open), listOf(1L, 0L), listOf(0L, 0L)),
            Case("point violates a row", listOf(open), listOf(0L, 0L), listOf(1L, 0L)),
            Case(
                "minimum crosses its other operand",
                listOf(RealArrayMinMax(0, intArrayOf(0, 1), max = false)),
                listOf(0L, 0L),
                listOf(1L, 0L),
            ),
            Case("product off the fixed integer", listOf(product), listOf(1L, 2L), listOf(1L, 1L)),
            Case("true atom bounds the ray", listOf(atom), listOf(1L, 0L), listOf(1L, 0L)),
        )
        for (case in cases) assertEquals(false, proves(case), case.name)
    }
}
