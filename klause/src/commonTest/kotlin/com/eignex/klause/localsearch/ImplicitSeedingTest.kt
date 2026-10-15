package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImplicitSeedingTest {

    @Test
    fun `suspended implicit seeding should preserve pins and publish owners after every global`() {
        val problem = Problem(
            0,
            5,
            Array(5) { IntDomain(0, 2) },
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
    fun `probing past the candidate cap should harvest no implications`() {
        val graph = ImplicitSeeding(twoForksProblem(), maxImplicationCandidates = 1).implicationGraph
        assertTrue(Lit.make(1, true) in graph[Lit.make(0, true)], "x0 within the cap is probed")
        assertTrue(graph[Lit.make(2, true)].isEmpty(), "x2 past the cap is never probed")
    }

    private fun row(a: Int, b: Int) = AllDifferent(vars = intArrayOf(a, b), domainMin = 0, domainSize = 2)

    private fun latinSquare(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
        factors = arrayOf<Factor>(row(0, 1), row(2, 3), row(0, 2), row(1, 3)),
    )

    @Test
    fun `the implicit seed set should be scope-disjoint`() {
        val problem = latinSquare()
        val state = LocalSearchState(problem.bake(), Random(1))
        val owned = HashSet<Int>()
        for (fid in state.seeding.implicitSeedFactors) {
            for (v in problem.factors[fid].intVars) {
                assertTrue(owned.add(v), "seed factors must not share var $v")
            }
        }
        assertTrue(state.seeding.implicitSeedFactors.isNotEmpty(), "at least one all-different must be seeded")
    }

    @Test
    fun `only the owner should move an owned var`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), 0, 3)),
        )
        val state = LocalSearchState(problem.bake(), Random(5))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        state.seedImplicitFeasible()
        state.recompute()
        assertTrue(state.cost == 0L, "seeding leaves the all-different satisfied")

        // A generic add (no proposing factor) on an owned var is filtered out of the neighbourhood.
        val generic = MoveSink()
        generic.setOwners(state.seeding.ownerInt)
        generic.addIntSet(0, 2)
        assertTrue(generic.list.isEmpty())

        // The owner's own structure-preserving moves on the same vars survive the filter.
        val owned = MoveSink()
        owned.setOwners(state.seeding.ownerInt)
        owned.proposer = 0
        state.factors[0].proposeStructuredMoves(state, 0, owned)
        assertTrue(owned.list.isNotEmpty())
    }
}
