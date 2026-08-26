package com.eignex.klause.localsearch.strategy

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Implicit-solving neighbourhoods: elected structural globals offer feasibility-preserving
 * structured moves even during infeasibility, and those moves win the weighted-gradient race only
 * when they clear a *coupled* constraint.
 *
 * Fixture: a 2×2 Latin square — vars laid out `v0 v1 / v2 v3` over `{0,1}` with an
 * all-different on each row and each column. From the assignment `(0,1,0,1)` both rows are
 * satisfied but both columns clash (`v0=v2=0`, `v1=v3=1`). Swapping the satisfied second row
 * (`v2 ↔ v3`) keeps that row distinct and simultaneously fixes both columns — exactly the
 * structure-preserving move only an implicit neighbourhood produces.
 */
class CblsImplicitNeighbourhoodTest {

    private fun row(a: Int, b: Int) = AllDifferent(vars = intArrayOf(a, b), domainMin = 0, domainSize = 2)

    /** Rows: (v0,v1), (v2,v3); columns: (v0,v2), (v1,v3). */
    private fun latinSquare(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
        factors = arrayOf<Factor>(row(0, 1), row(2, 3), row(0, 2), row(1, 3)),
    )

    private fun seed(state: LocalSearchState) {
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 0)
        state.assignment.setInt(3, 1)
        state.recompute()
    }

    @Test
    fun `every all-different is elected for implicit neighbourhoods`() {
        val state = LocalSearchState(latinSquare(), Random(1))
        assertTrue(
            state.seeding.electedImplicit.toList().sorted() == listOf(0, 1, 2, 3),
            "all four all-differents must be elected, got ${state.seeding.electedImplicit.toList()}",
        )
    }

    @Test
    fun `a satisfied row's structured swap clears the coupled column clashes`() {
        val problem = latinSquare()
        val state = LocalSearchState(problem, Random(1))
        seed(state)
        assertTrue(state.cost > 0L, "fixture must start infeasible (both columns clash)")

        // The second row (factor 1) is satisfied; its feasibility-preserving swap is the move.
        assertTrue(!state.violated.contains(1), "row (v2,v3) must be satisfied at the start state")
        val sink = MoveSink()
        state.factors[1].proposeStructuredMoves(state, 1, sink)
        val swap = sink.list.firstOrNull { m ->
            m is Move.Compound && m.parts.toSet() == setOf(Move.IntSet(2, 1), Move.IntSet(3, 0))
        }
        assertTrue(swap != null, "satisfied row must propose the v2↔v3 value swap, got ${sink.list}")

        // The structure-preserving swap strictly improves the weighted violation gradient
        // (it fixes both columns while keeping the row distinct).
        assertTrue(
            state.weightedNetDelta(swap) < 0.0,
            "the coupled-clearing swap must score as a strict improvement",
        )
    }

    @Test
    fun `the implicit seed set is scope-disjoint`() {
        val problem = latinSquare()
        val state = LocalSearchState(problem, Random(1))
        val owned = HashSet<Int>()
        for (fid in state.seeding.implicitSeedFactors) {
            for (v in problem.factors[fid].intVars) {
                assertTrue(owned.add(v), "seed factors must not share var $v")
            }
        }
        assertTrue(state.seeding.implicitSeedFactors.isNotEmpty(), "at least one all-different must be seeded")
    }

    @Test
    fun `feasible init seeds every elected global satisfied`() {
        // A row/column pair on a 3-value domain: a Latin row+column whose disjoint seeds each
        // become a permutation. With domain {0,1,2} the greedy seeder always succeeds.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), 0, 3)),
        )
        val state = LocalSearchState(problem, Random(5))
        // Start from an all-equal (maximally violated) assignment.
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        assertTrue(state.cost > 0L, "fixture must start violated")
        state.seedImplicitFeasible()
        state.recompute()
        assertTrue(state.cost == 0L, "feasible init must leave the seeded all-different satisfied")
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
        val state = LocalSearchState(problem, Random(5), frozen)
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        state.seedImplicitFeasible()
        assertTrue(state.assignment.intValue(0) == 2L, "frozen var must keep its value")
    }

    @Test
    fun `feasible init records the seeded global as owner of its vars`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), 0, 3)),
        )
        val state = LocalSearchState(problem, Random(5))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.recompute()
        state.seedImplicitFeasible()
        val owners = assertNotNull(state.seeding.ownerInt, "seeding must populate the owner map")
        assertTrue(
            owners.toList() == listOf(0, 0, 0),
            "the all-different (factor 0) owns its three vars, got ${owners.toList()}",
        )
    }

    @Test
    fun `only the owner may move an owned var`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), 0, 3)),
        )
        val state = LocalSearchState(problem, Random(5))
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
    fun `the CBLS engine drives the coupled square to feasibility`() {
        val problem = latinSquare()
        val state = LocalSearchState(problem, Random(1))
        seed(state)
        val strategy = Cbls(implicitStructuredCap = 8)
        var solved = state.cost == 0L
        repeat(2_000) {
            if (solved) return@repeat
            val m = strategy.pickMove(state) ?: return@repeat
            state.apply(m)
            if (state.cost == 0L) solved = true
        }
        assertTrue(solved, "implicit-neighbourhood-enabled CBLS must solve the coupled Latin square")
    }
}
