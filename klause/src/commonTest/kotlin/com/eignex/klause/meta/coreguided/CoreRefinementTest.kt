package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CoreRefinementTest {
    @Test
    fun `refinement removes an unrelated pin by a joint refutation`() {
        val literals = IntArray(3) { Lit.make(it, true) }
        val problem = Problem(3, 0, emptyArray(), arrayOf(Clause(literals.copyOfRange(1, 3)))).bake()
        val refined = CoreRefinement.refine(problem, literals, Assumptions.None, Cancellation.Never) {}

        assertContentEquals(literals.copyOfRange(1, 3), refined)
    }

    @Test
    fun `refinement retains transitive premises of propagated assumptions`() {
        val problem = Problem(4, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
        )).bake()
        val literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(3, true))

        val refined = CoreRefinement.refine(problem, literals, Assumptions.None, Cancellation.Never) {}

        assertContentEquals(intArrayOf(literals[0], literals[2]), refined)
    }

    @Test
    fun `refinement checks every trial under caller conditions`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
        )).bake()
        val literals = intArrayOf(Lit.make(1, true), Lit.make(2, true))

        val refined = CoreRefinement.refine(problem, literals, Assumptions(bools = mapOf(0 to false)),
            Cancellation.Never) {}

        assertContentEquals(intArrayOf(literals[1]), refined)
    }

    @Test
    fun `interruption retains the certified input core`() {
        val literals = IntArray(3) { Lit.make(it, true) }
        val problem = Problem(3, 0, emptyArray(), arrayOf(Clause(literals.copyOfRange(1, 3)))).bake()
        var cancelled = false

        val refined = CoreRefinement.refine(problem, literals, Assumptions.None, Cancellation { cancelled }) {
            cancelled = true
        }

        assertContentEquals(literals, refined)
    }

    @Test
    fun `oll consumes a smaller weighted core`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
        )).bake()
        val search = CoreGuidedSearch(problem, LinearObjective(longArrayOf(1, 100, 100)),
            BacktrackParams(), refineCores = true)

        val result = search.use { it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {} }

        assertEquals(100.0, assertIs<MinimizeResult.Optimal>(result).objective)
        assertEquals(2, search.stats.coreGuided.largestCore)
        assertEquals(1L, search.stats.coreGuided.cores)
    }
}
