package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.propagation.Assumptions
import kotlin.random.Random
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The candidate cap bounds the quadratic implication-graph allocation on Boolean-heavy models.
class ImplicitSeedingTest {

    @Test
    fun `suspended implicit seeding preserves pins and publishes owners after every global`() {
        val problem = Problem(
            0, 5, Array(5) { IntDomain(0, 2) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), 0, 3),
                AllDifferent(intArrayOf(0, 1, 2), 0, 3),
                AllDifferent(intArrayOf(3, 4), 0, 3),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7), Assumptions(ints = mapOf(0 to 2L)))
        state.randomizeAssignment()
        val steps = state.seeding.seedSteps(state).iterator()
        while (steps.hasNext()) {
            assertNull(state.seeding.ownerInt)
            steps.next()
            if (state.seeding.ownerInt != null) break
        }
        state.recompute()

        assertEquals(2L, state.assignment.intValue(0))
        assertContentEquals(intArrayOf(1, 1, 1, 2, 2), state.seeding.ownerInt)
        assertEquals(0L, state.cost)
        state.moveSink.addIntSet(1, state.assignment.intValue(0))
        assertTrue(state.moveSink.list.isEmpty())
    }

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
