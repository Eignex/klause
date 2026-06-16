package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #655 (Tranche B): the degree + channel arc relaxation of [Subcircuit]. The model is the permutation
 * polytope (self-loop = excluded node), a sound assignment relaxation — it must never change the
 * optimum, and (unlike Circuit) it registers no subtour-elimination model, since the Hamiltonian SEC
 * is unsound for a subcircuit.
 */
class SubcircuitArcRelaxationTest {

    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = Array(4) { IntDomain(0, 3) },
        factors = arrayOf<Factor>(
            Subcircuit(intArrayOf(0, 1, 2, 3)),
            // Force node 0 into the cycle (succ[0] != 0) via a relaxable bound, so the optimum is non-trivial.
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
        ),
    )

    private val objective = LinearObjective(intCoefficients = longArrayOf(1L, 0L, 0L, 0L)) // minimize succ[0]

    @Test
    fun `arc relaxation builds columns and stays feasible`() {
        val p = problem()
        val r = CpToLpRelaxation(p, objective, circuitArcs = true).build(PropagationSession(p))
        // Arc columns were materialized beyond the four succ variables.
        assertTrue(r.model.n > 4, "expected arc columns, model has ${r.model.n} columns")
        // No subtour model is registered for a subcircuit (the Hamiltonian SEC would be unsound).
        assertTrue(r.circuitArcs.isEmpty(), "subcircuit must not register a subtour-elimination arc model")
        assertEquals(LpStatus.OPTIMAL, DualSimplex(r.model).solve().status, "the permutation relaxation is feasible")
    }

    @Test
    fun `arc relaxation keeps the optimum correct end to end`() {
        val p = problem()
        val base = BacktrackParams(randomSeed = 1L)
        val noLp = BacktrackSolver(p).minimize(objective, base)
        val lp = BacktrackSolver(p).minimize(objective, base.copy(lpBounding = true, lpCircuit = true))
        assertTrue(noLp is MinimizeResult.Optimal, "baseline should solve, got $noLp")
        assertTrue(lp is MinimizeResult.Optimal, "subcircuit-LP solve should be optimal, got $lp")
        // succ[0] >= 1 forces node 0 included; the cheapest is succ[0] = 1 (e.g. the 2-cycle 0->1->0).
        assertEquals(1.0, lp.objective, 1e-9, "minimum succ[0] under the forced inclusion")
        assertEquals(noLp.objective, lp.objective, 1e-9, "the arc relaxation must not change the optimum")
        assertTrue(lp.stats.lpSolves.sum > 0.0, "the LP should actually run, got ${lp.stats.lpSolves.sum}")
    }
}
