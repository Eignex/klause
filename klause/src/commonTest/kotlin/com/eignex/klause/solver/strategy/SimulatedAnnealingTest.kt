package com.eignex.klause.solver.strategy

import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.solver.LocalSearchSolver
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SimulatedAnnealingTest {

    /** SA must converge on a small 3-SAT instance — same shape as the WalkSat smoke test. */
    @Test
    fun `simulated annealing solves small 3 sat`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val problem = Problem(3, 0, emptyArray(), clauses)
        val solver = LocalSearchSolver(problem, strategy = SimulatedAnnealing())
        val sample = solver.sample(LocalSearchParams(maxFlips = 20_000L, randomSeed = 7L))
        assertNotNull(sample, "SA should find a satisfying assignment within budget")
        for (clause in clauses) {
            val sat = clause.literals.any { lit ->
                Lit.evaluate(lit, sample.bools[Lit.variable(lit)])
            }
            assertEquals(true, sat, "Clause unsatisfied by $sample")
        }
    }

    /** Two cooling rates should both find a satisfying assignment within budget on a small
     *  problem — different schedules, same outcome. */
    @Test
    fun `different cooling schedules both converge`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
        )
        val problem = Problem(4, 0, emptyArray(), clauses)
        val fast = LocalSearchSolver(problem, strategy = SimulatedAnnealing(coolingRate = 0.99))
        val slow = LocalSearchSolver(problem, strategy = SimulatedAnnealing(coolingRate = 0.9999))
        val a = fast.sample(LocalSearchParams(maxFlips = 20_000L, randomSeed = 1L))
        val b = slow.sample(LocalSearchParams(maxFlips = 20_000L, randomSeed = 2L))
        assertNotNull(a)
        assertNotNull(b)
    }
}
