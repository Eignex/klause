package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The seeding-side binary-implication graph feeding
 * [com.eignex.klause.localsearch.movesource.FlipAndPropagate]. The harvest is bounded to a
 * free-Boolean candidate cap: an uncapped full-`numBoolVars` probe materialises an O(numBoolVars²)
 * adjacency that exhausts the heap on Boolean-heavy models, so probing stops after the cap and later
 * seeds simply find no forced literals.
 */
class ImplicitSeedingTest {

    /** Two independent implications `x0 -> x1` and `x2 -> x3`, each via a binary clause. */
    private fun twoForksProblem(): Problem = Problem(
        numBoolVars = 4,
        numIntVars = 0,
        intDomains = arrayOf(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
        ),
    )

    @Test
    fun `probing past the candidate cap harvests no implications`() {
        // Cap 1 probes only x0, so its forced literal is recorded but x2 (id past the cap) is never
        // pinned and contributes no edges — the bound that keeps a Boolean-heavy build off the heap.
        val graph = ImplicitSeeding(twoForksProblem(), maxImplicationCandidates = 1).implicationGraph
        assertTrue(Lit.make(1, true) in graph[Lit.make(0, true)], "x0 within the cap is probed")
        assertTrue(graph[Lit.make(2, true)].isEmpty(), "x2 past the cap is never probed")
    }

    @Test
    fun `a cap covering every variable harvests all implications`() {
        val graph = ImplicitSeeding(twoForksProblem(), maxImplicationCandidates = 4).implicationGraph
        assertTrue(Lit.make(1, true) in graph[Lit.make(0, true)], "x0 -> x1 harvested")
        assertTrue(Lit.make(3, true) in graph[Lit.make(2, true)], "x2 -> x3 harvested once the cap reaches x2")
    }
}
