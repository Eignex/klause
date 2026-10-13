package com.eignex.klause.localsearch.strategy

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CblsImplicitNeighbourhoodTest {

    private fun row(a: Int, b: Int) = AllDifferent(vars = intArrayOf(a, b), domainMin = 0, domainSize = 2)

    /** Rows: (v0,v1), (v2,v3); columns: (v0,v2), (v1,v3). */
    private fun latinSquare(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
        factors = arrayOf<Factor>(row(0, 1), row(2, 3), row(0, 2), row(1, 3)),
    )

    @Test
    fun `the implicit seed set is scope-disjoint`() {
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
    fun `feasible init leaves frozen vars untouched`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), 0, 3)),
        )
        val frozen = Assumptions(ints = mapOf(0 to 2))
        val state = LocalSearchState(problem.bake(), Random(5), frozen)
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        state.seedImplicitFeasible()
        assertTrue(state.assignment.intValue(0) == 2L, "frozen var must keep its value")
    }

    @Test
    fun `only the owner may move an owned var`() {
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
        assertTrue(generic.list.isEmpty(), "the generic pool must not touch an owned var")

        // The owner's own structure-preserving moves on the same vars survive the filter.
        val owned = MoveSink()
        owned.setOwners(state.seeding.ownerInt)
        owned.proposer = 0
        state.factors[0].proposeStructuredMoves(state, 0, owned)
        assertTrue(owned.list.isNotEmpty(), "the owner must still be able to move the vars it owns")
    }

    @Test
    fun `default cbls descends through an owned global among arithmetic rows`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), 0, 3)) + Array<Factor>(256) {
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
            })
        val state = LocalSearchState(problem.bake(), Random(7))
        state.seedImplicitFeasible()
        for (v in 0..2) state.assignment.setInt(v, v.toLong())
        state.recompute()
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0, 0))
        state.shaping.objective = objective

        val move = assertNotNull(Cbls().pickMove(state))

        assertTrue(checkNotNull(state.objectiveDelta(objective, move)) < 0.0)
        assertEquals(0L, state.netDelta(move))
        state.apply(move)
        state.recompute()
        assertEquals(0L, state.cost)
    }
}
