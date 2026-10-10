package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class OpenPortfolioTest {

    private fun openColumns(n: Int, vararg factors: Factor): Problem {
        val open = Bits(n).also { bits -> for (v in 0 until n) bits.set(v) }
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(n), LongArray(n), open, open),
            factors = arrayOf(*factors),
        )
    }

    private fun params() = TheoryParams(cancellation = Cancellation.after(10.seconds))

    private fun satisfied(execution: OpenTheoryExecution) = assertIs<OpenTheoryExecution.Satisfy>(execution).result

    private fun optimum(execution: OpenTheoryExecution) = assertIs<OpenTheoryExecution.Optimize>(execution).result

    @Test
    fun `local search finds a witness of an open model no theory decides`() {
        // x0 = 3, x1 = 4 and x2 = x0·x1 over open columns: the product holds no theory.
        val model = openColumns(
            3,
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
            Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 4),
            Product(0, 1, 2),
        )

        val result = satisfied(OpenTheoryPipeline.searchWithoutTheory(model, params()))

        assertEquals("12", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(2))
    }

    @Test
    fun `the theory arm refutes an unsatisfiable open model on one lane or several`() {
        // 2·x0 = 1 has no integer solution, which local search can never show.
        val model = openColumns(1, Linear(intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1))
        val request = OpenTheoryRequest(model, componentPlan = model.componentPlan())
        for (cores in listOf(1, 4)) {
            val result = satisfied(OpenTheoryPipeline.executePortfolio(request, params(), cores))

            assertIs<OpenTheoryResult.Unsat>(result, "cores=$cores")
        }
    }

    @Test
    fun `a model with no local-search arm runs the theory without a portfolio`() {
        // x + y ≥ 3 over open continuous columns and nothing else: no Boolean, no integer, so no local-search arm.
        val model = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), LinearOp.GE, 3.0),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
        )
        val request = OpenTheoryRequest(model, componentPlan = model.componentPlan())

        val result = assertIs<OpenTheoryResult.Sat>(satisfied(OpenTheoryPipeline.executePortfolio(request, params())))

        assertTrue(result.stats.portfolio.arms.isEmpty())
    }

    @Test
    fun `the portfolio proves the optimum of an open model on one lane or several`() {
        // Minimize x0 + x1 with x0 ≥ 7 and x1 ≥ x0 − 2 over open columns: 7 + 5.
        val model = openColumns(
            2,
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 7),
            Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.GE, -2),
        )
        val request = OpenTheoryRequest(model, LinearObjective(intCoefficients = longArrayOf(1, 1)))
        for (cores in listOf(1, 4)) {
            val result = optimum(OpenTheoryPipeline.executePortfolio(request, params(), cores))

            assertEquals(BigFraction.ofLong(12), assertIs<OpenTheoryOptimum.Optimal>(result, "cores=$cores").value)
        }
    }

    @Test
    fun `the portfolio preserves an optimal integer witness outside the Long range`() {
        val value = parseBigInt("9223372036854775808")
        val model = openColumns(
            1,
            Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, value),
        )
        val request = OpenTheoryRequest(model, LinearObjective(intCoefficients = longArrayOf(1)))

        val result = optimum(OpenTheoryPipeline.executePortfolio(request, params()))

        val optimal = assertIs<OpenTheoryOptimum.Optimal>(result)
        assertEquals(value.toString(), optimal.assignment.intValue(0))
        assertEquals(value.toString(), optimal.value.toString())
        assertTrue(optimal.stats.portfolio.arms.isNotEmpty())
    }

    @Test
    fun `the portfolio returns all exact coordinates of a mixed satisfying witness`() {
        val wide = parseBigInt("9223372036854775808")
        val integerModel = openColumns(1, Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, wide))
        val model = Problem(
            numBoolVars = 0,
            intBounds = integerModel.intBounds,
            factors = integerModel.factors + Linear(
                intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0,
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val request = OpenTheoryRequest(model, componentPlan = model.componentPlan())

        val result = satisfied(OpenTheoryPipeline.executePortfolio(request, params()))

        val sat = assertIs<OpenTheoryResult.Sat>(result)
        assertEquals(wide.toString(), sat.assignment.intValue(0))
        assertEquals("1/3", sat.assignment.realValue(0))
        assertTrue(sat.stats.portfolio.arms.isNotEmpty())
    }

    @Test
    fun `local search bounds the optimum of an open model no theory decides`() {
        val model = openColumns(
            3,
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
            Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 4),
            Product(0, 1, 2),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 1))
        val params = TheoryParams(cancellation = Cancellation.after(100.milliseconds))

        val result = optimum(OpenTheoryPipeline.searchWithoutTheory(model, params, objective))

        assertEquals(BigFraction.ofLong(12), assertIs<OpenTheoryOptimum.Bounded>(result).value)
    }
}
