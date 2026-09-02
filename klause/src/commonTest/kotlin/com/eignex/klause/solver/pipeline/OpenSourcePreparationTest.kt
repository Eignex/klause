package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** Routing an open source model from what source-safe preparation produced, rather than from the input. */
class OpenSourcePreparationTest {

    /**
     * `x = y + z` and `y + z <= 4` over open columns, then an `AllDifferent` over two bounded ones.
     *
     * The aggregate pass rewrites the second row into `x <= 4`, which drops it and appends the rewrite —
     * so the `AllDifferent` moves up a slot and factor ownership no longer lines up with the input.
     */
    private fun aggregatable(openColumns: Boolean): Problem {
        val open = if (openColumns) Bits(5).also { bits -> repeat(3) { bits.set(it) } } else null
        val upper = if (openColumns) longArrayOf(0, 0, 0, 3, 3) else longArrayOf(9, 9, 9, 3, 3)
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(5), upper, null, open),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0L),
                Linear(longArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 4L),
                AllDifferent(vars = intArrayOf(3, 4), domainMin = 0, domainSize = 4),
            ),
        )
    }

    /** `2x + 4y = 3` over open columns: no gcd multiple reaches 3, whatever the columns are worth. */
    private fun refutable(): Problem {
        val open = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf<Factor>(Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 3L)),
        )
    }

    @Test
    fun `a source rewrite moves factor ownership with the factors`() {
        val model = aggregatable(openColumns = true)
        val declared = model.componentPlan()

        val planned = assertIs<OpenSourcePreparation.Planned>(model.prepareOpenSource())

        assertEquals(FactorOwner.CP, planned.plan.factorOwner(1))
        assertNotEquals(declared.factorOwner(1), planned.plan.factorOwner(1))
    }

    @Test
    fun `source refutation is terminal for the open route`() {
        val model = refutable()

        val result = OpenTheoryEngine(model, model.sourceRoute()).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(true, result.stats.presolve?.infeasible)
    }

    @Test
    fun `the open route reports what preparation did to the source`() {
        val model = aggregatable(openColumns = true)

        val result = OpenTheoryEngine(model, model.sourceRoute()).solve()

        assertContains(result.stats.presolve!!.passes, PresolvePass.AGGREGATE_SUB_SUMS.id)
    }

    @Test
    fun `source refutation is terminal for the open optimization route`() {
        val model = refutable()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0))

        val result = OpenTheoryMinimizer(model, objective).minimize()

        assertIs<OpenTheoryOptimum.Infeasible>(result)
    }

    @Test
    fun `the bounded and the open lane rewrite the source the same way`() {
        val open = assertIs<OpenSourcePreparation.Planned>(aggregatable(openColumns = true).prepareOpenSource())
        val bounded = FinitePipeline.prepare(
            FinitePipelineRequest(
                aggregatable(openColumns = false),
                FiniteEngine.BACKTRACK,
                presolveConfig = PresolveConfig.parse(PresolvePass.AGGREGATE_SUB_SUMS.id),
            ),
        )

        assertContains(bounded.presolve!!.passes, PresolvePass.AGGREGATE_SUB_SUMS.id)
        assertEquals(intArrayOf(0).toList(), aggregatedRow(open.model).vars.toList())
        assertEquals(intArrayOf(0).toList(), aggregatedRow(bounded.problem).vars.toList())
    }

    private fun aggregatedRow(problem: Problem): Linear =
        problem.factors.filterIsInstance<Linear>().single { it.vars.size == 1 }
}
