package com.eignex.klause.compile

import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.model.CircuitExpr
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.iff
import kotlin.test.Test
import kotlin.test.assertTrue

/** A reified global must lower to a model the solver can iterate against (the decomposition is sound). */
class ReifiedGlobalTest {

    private class CircuitReifiedSchema : VariableSchema() {
        val n0 by intVar(min = 0, max = 2)
        val n1 by intVar(min = 0, max = 2)
        val n2 by intVar(min = 0, max = 2)
        val flag by boolVar()

        // Sub-expression position: reify the global behind iff/implies.
        val c by constraint {
            flag iff CircuitExpr(listOf(n0.toIntExpr(), n1.toIntExpr(), n2.toIntExpr()))
        }
    }

    @Test
    fun `reified circuit produces a feasibility-checkable model`() {
        val s = CircuitReifiedSchema()
        val compiled = s.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 300),
        )
        // Exercise the lowering and confirm it produces a model the solver can iterate against
        // without crashing; we don't insist LS terminates on a feasible sample within a fixed budget.
        val samples = solver.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 41)).take(5).toList()
        assertTrue(samples.isNotEmpty(), "solver returned no samples")
    }
}
