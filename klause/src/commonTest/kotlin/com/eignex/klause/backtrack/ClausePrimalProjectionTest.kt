package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClausePrimalProjectionTest {
    @Test
    fun `a reduced witness reconstructs hard clauses and scoped pins`() {
        val source = Problem(5, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, false))),
        ))
        val prepared = assertNotNull(ClausePrimalProjection.prepare(
            source, Assumptions(bools = mapOf(0 to false, 4 to true)), true, Cancellation.Never, {},
        ).projection)

        val result = BacktrackSolver(prepared.problem).minimize(LinearObjective(), BacktrackParams())
        val sample = prepared.lift((result as MinimizeResult.WithSample).sample)

        assertTrue(!sample.bools[0])
        assertTrue(sample.bools[4])
        assertTrue(source.factors.all { factor ->
            (factor as Clause).literals.any { Lit.evaluate(it, sample.bools[Lit.variable(it)]) }
        })
    }
}
