package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class OpenSourcePreparationTest {

    private val aggregateOnly = PresolveConfig.parse(PresolvePass.AGGREGATE_SUB_SUMS.id)

    // Three AllDifferent columns, since structural reduction rewrites a two-column one into a disequality.
    private fun aggregatable(openColumns: Boolean): Problem {
        val open = if (openColumns) Bits(6).also { bits -> repeat(3) { bits.set(it) } } else null
        val upper = if (openColumns) longArrayOf(0, 0, 0, 3, 3, 3) else longArrayOf(9, 9, 9, 3, 3, 3)
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(6), upper, null, open),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1, -1, -2), intArrayOf(0, 1, 2), LinearOp.EQ, 0L),
                Linear(longArrayOf(1, 2), intArrayOf(1, 2), LinearOp.LE, 4L),
                AllDifferent(vars = intArrayOf(3, 4, 5), domainMin = 0, domainSize = 4),
            ),
        )
    }

    private fun refutable(): Problem {
        val open = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf<Factor>(Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 3L)),
        )
    }

    @Test
    fun `a source rewrite should move factor ownership with the factors`() {
        val model = aggregatable(openColumns = true)
        val declared = model.componentPlan()

        val planned = assertIs<OpenSourcePreparation.Planned>(model.prepareOpenSource(config = aggregateOnly))

        assertEquals(FactorOwner.CP, planned.plan.factorOwner(1))
        assertNotEquals(declared.factorOwner(1), planned.plan.factorOwner(1))
    }

    @Test
    fun `source refutation should be terminal for the open route`() {
        val model = refutable()

        val result = OpenTheoryEngine(model, model.sourceRoute()).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(true, result.stats.presolve?.infeasible)
    }

    @Test
    fun `a solve stopped before it starts should prepare nothing`() {
        val model = aggregatable(openColumns = true)

        val result = OpenTheoryEngine(model, model.sourceRoute())
            .solve(TheoryParams(cancellation = Cancellation { true }))

        assertIs<OpenTheoryResult.Unknown>(result)
        assertNull(result.stats.presolve)
    }
}
