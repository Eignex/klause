package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.objective.objectiveBoundOverlay
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LocalSearchState.synthesizeChannelingMove] reads the linear structure (coeffs/op/bound) from
 * the original factors, not the parallel invariants which no longer carry it after the
 * propagator/invariant split. These fixtures drive the EQ channeling paths the
 * [strategy.CblsStallSwapTest] fixture (GE/LE only) deliberately avoids.
 */
class LocalSearchStateTest {

    private fun parts(move: Move): List<Move> = (move as Move.Compound).parts

    /** `c == p` reified as `b_p` for p in 0..2, encoded as N parallel single-var EQ reifieds. */
    private fun reifiedChannelingProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 2)),
        factors = arrayOf<Factor>(
            ReifiedLinear(auxBoolVar = 0, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 0),
            ReifiedLinear(auxBoolVar = 1, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 1),
            ReifiedLinear(auxBoolVar = 2, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 2),
        ),
    )

    @Test
    fun `an objective variable's flip leaves the other objective variables' configuration as it was`() {
        // The model ties no variable to another; only the objective-bound overlay spans all three.
        val problem = Problem(3, 0, emptyArray<IntDomain>(), listOf(Clause(intArrayOf(Lit.make(0, true)))))
        val (overlay, _) = requireNotNull(
            objectiveBoundOverlay(problem.bake(), LinearObjective(boolWeights = longArrayOf(1, 1, 1))),
        )
        val state = LocalSearchState(overlay, Random(0))
        state.recompute()
        state.boolConfChange.fill(false)

        state.apply(Move.BoolFlip(0))

        assertEquals(listOf(false, false), listOf(state.boolConfChange[1], state.boolConfChange[2]))
    }

    @Test
    fun `single-var EQ reified channeling rolls indicator flips into one compound`() {
        val state = LocalSearchState(reifiedChannelingProblem().bake(), Random(1))
        state.assignment.setInt(0, 0)
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false)
        state.assignment.setBool(2, false)
        state.recompute()

        val move = state.synthesizeChannelingMove(intVar = 0, newValue = 1)

        val ps = parts(move)
        assertTrue(Move.IntSet(0, 1) in ps, "compound must set the int var to the new value")
        assertTrue(Move.BoolFlip(0) in ps, "indicator for the old value must clear")
        assertTrue(Move.BoolFlip(1) in ps, "indicator for the new value must set")
        assertTrue(Move.BoolFlip(2) !in ps, "untouched indicators must not flip")
        assertEquals(3, ps.size, "no spurious extra parts")
    }

    /** A satisfied `x + y = 3` whose balance is restored by counter-shifting the sibling var. */
    private fun sumChannelingProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
        ),
    )

    @Test
    fun `satisfied Linear EQ channeling counter-shifts a sibling to preserve the sum`() {
        val state = LocalSearchState(sumChannelingProblem().bake(), Random(1))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.recompute()

        val move = state.synthesizeChannelingMove(intVar = 0, newValue = 3)

        val ps = parts(move)
        assertTrue(Move.IntSet(0, 3) in ps, "compound must set the driving var")
        assertTrue(Move.IntSet(1, 0) in ps, "sibling must absorb the +2 drift to keep x + y = 3")
        assertEquals(2, ps.size, "no spurious extra parts")
    }

    @Test
    fun `Linear EQ channeling never counter-shifts a sibling into a hole`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5).excludeValue(3)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 4)),
        )
        val state = LocalSearchState(problem.bake(), Random(1))
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 2)
        state.recompute()

        val move = state.synthesizeChannelingMove(intVar = 0, newValue = 1)

        assertEquals(Move.IntSet(0, 1), move, "absorbing the drift would put y on the hole at 3")
    }

    @Test
    fun `an int value in a hole is not within the domains`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5).excludeValue(3)),
            factors = arrayOf<Factor>(),
        )
        val state = LocalSearchState(problem.bake(), Random(1))

        state.assignment.setInt(0, 3)

        assertFalse(state.intValuesInDomain())
    }

    @Test
    fun `no sibling indicators leaves a plain int set`() {
        val state = LocalSearchState(sumChannelingProblem().bake(), Random(1))
        // Violated EQ: the caller is repairing it, so no counter-shift is synthesized.
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.recompute()

        val move = state.synthesizeChannelingMove(intVar = 0, newValue = 1)

        assertEquals(Move.IntSet(0, 1), move, "a violated EQ yields a bare int set, not a compound")
    }

    private fun foldedProblem() = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 10)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
    ).bake()

    @Test
    fun `the search seeds its root domains from the projection's fold rather than the declared box`() {
        val state = LocalSearchState(foldedProblem(), Random(1))

        assertEquals(3L, state.rootDomains[0].max, "search must not propose values the root bake ruled out")
    }

    @Test
    fun `seeding a search state aliases the projection's fold instead of copying it`() {
        val problem = foldedProblem()

        val state = LocalSearchState(problem, Random(1))

        assertTrue(state.rootDomains === problem.rootIntDomainsInPlace, "the fold is aliased, not copied")
    }

    @Test
    fun `re-summing the real rows reconciles a drifted sum`() {
        val row = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(row),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.restart()
        state.doublePayload[0] = 5.0
        state.apply(Move.RealSet(0, 0.5))
        assertTrue(state.cost > 0L, "the drifted sum reads as violated")

        state.refreshRealRows()

        assertEquals(0L, state.cost)
    }
}
