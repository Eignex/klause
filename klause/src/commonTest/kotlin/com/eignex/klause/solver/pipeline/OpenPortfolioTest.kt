package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test
    fun `local search finds a witness of an open model no theory decides`() {
        // x0 = 3, x1 = 4 and x2 = x0·x1 over open columns: the product holds no theory.
        val model = openColumns(
            3,
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
            Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 4),
            Product(0, 1, 2),
        )

        val result = OpenTheoryPipeline.searchWithoutTheory(model, params())

        assertEquals("12", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(2))
    }

    @Test
    fun `the theory arm refutes an unsatisfiable open model`() {
        // 2·x0 = 1 has no integer solution, which local search can never show.
        val model = openColumns(1, Linear(intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1))
        val request = OpenTheoryRequest(model, componentPlan = model.componentPlan())

        val result = OpenTheoryPipeline.executePortfolio(request, params())

        assertIs<OpenTheoryResult.Unsat>(result)
    }
}
