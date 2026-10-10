package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [LocalSearchState.synthesizeChannelingMove] reads the linear structure (coeffs/op/bound) from
 * the original factors, not the parallel invariants which no longer carry it after the
 * propagator/invariant split. These fixtures drive the EQ channeling paths the
 * [strategy.CblsStallSwapTest] fixture (GE/LE only) deliberately avoids.
 */
class LocalSearchStateTest {
    @Test
    fun `indicator channels and their product outputs follow coordinate moves`() {
        for (bound in listOf(0, 1)) {
            val problem = Problem(
                1, 6, arrayOf(
                    IntDomain(0, 2), IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3),
                    IntDomain(0, 1), IntDomain(0, 3),
                ),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
                    Product(1, 2, 3),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(4), LinearOp.EQ, 1 - bound),
                    Product(4, 2, 5),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, 0)
            state.assignment.setBool(0, false)
            state.assignment.setInt(1, 1L - bound)
            state.assignment.setInt(2, 3)
            state.assignment.setInt(3, 3L * (1L - bound))
            state.assignment.setInt(4, bound.toLong())
            state.assignment.setInt(5, 3L * bound)
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 6)).network(6, 1)
            state.recompute()

            for (coordinate in listOf(1L, 2L, 1L)) {
                val move = state.synthesizeChannelingMove(0, coordinate)
                val predicted = state.netDelta(move)
                state.apply(move)
                val cost = state.cost
                val expected = if (coordinate == 1L) bound.toLong() else 1L - bound

                assertEquals(expected, state.assignment.intValue(1))
                assertEquals(3L * expected, state.assignment.intValue(3))
                assertEquals(1L - expected, state.assignment.intValue(4))
                assertEquals(3L * (1L - expected), state.assignment.intValue(5))
                assertEquals(0L, predicted)
                assertEquals(0L, cost)
                state.recompute()
                assertEquals(cost, state.cost)
            }
        }
    }

    @Test
    fun `indicator moves retain protected channel coordinates`() {
        for (protection in listOf("pin", "owner", "definition")) {
            val problem = Problem(
                1, 3, arrayOf(IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 0)),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                    Product(2, 2, 1),
                ),
            )
            val assumptions = if (protection == "pin") Assumptions.None.withInt(1, 0) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            if (protection == "owner") state.moveSink.setOwners(intArrayOf(-1, 1, -1))
            if (protection == "definition") {
                state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 3)).network(3, 1)
            }
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 0)
            state.assignment.setInt(2, 0)
            state.assignment.setBool(0, false)
            state.recompute()

            state.apply(state.synthesizeChannelingMove(0, 1))

            assertEquals(0L, state.assignment.intValue(1))
            assertEquals(1L, state.cost)
        }
    }

    @Test
    fun `nonbinary equality inputs retain their independent value`() {
        val problem = Problem(
            1, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 2)
        state.assignment.setBool(0, false)
        state.recompute()

        state.apply(state.synthesizeChannelingMove(0, 1))

        assertEquals(2L, state.assignment.intValue(1))
        assertEquals(1L, state.cost)
    }

    @Test
    fun `indicator moves cannot set a channel outside its root domain`() {
        val problem = Problem(
            1, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 0)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setBool(0, false)
        state.recompute()

        state.apply(state.synthesizeChannelingMove(0, 1))

        assertEquals(0L, state.assignment.intValue(1))
        assertTrue(state.intValuesInDomain())
        assertEquals(1L, state.cost)
    }

    @Test
    fun `reified degree updates and scores agree with recompute across moves`() {
        for (op in LinearOp.entries) {
            val problem = Problem(
                1, 1, arrayOf(IntDomain(0, 10)),
                arrayOf<Factor>(ReifiedLinear(0, intArrayOf(2), intArrayOf(0), op, 6)),
            )
            val state = LocalSearchState(problem.bake(), Random(1))
            state.violationSoftCap = 4
            state.assignment.setBool(0, true)
            state.recompute()
            val moves = listOf(
                Move.BoolFlip(0), Move.IntSet(0, 3), Move.BoolFlip(0), Move.IntSet(0, 10),
                Move.Compound(listOf(Move.BoolFlip(0), Move.IntSet(0, 0))),
            )

            for (move in moves) {
                val before = state.cost
                val predicted = state.netDelta(move)
                state.apply(move)
                val cost = state.cost
                val degree = state.factorDegree[0]
                val breaks = state.breakScore(Move.BoolFlip(0))
                val makes = state.makeScore(Move.BoolFlip(0))

                state.recompute()

                assertEquals(predicted, cost - before, "$op $move")
                assertEquals(state.cost, cost, "$op $move")
                assertEquals(state.factorDegree[0], degree, "$op $move")
                assertEquals(state.breakScore(Move.BoolFlip(0)), breaks, "$op $move")
                assertEquals(state.makeScore(Move.BoolFlip(0)), makes, "$op $move")
            }
        }
    }

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
    fun `states sharing a projection keep assignments and invariant payloads independent`() {
        val model = LocalSearchModel.of(reifiedChannelingProblem().bake())
        val projection = LocalSearchProblem(model.problem, model.domains)
        val first = LocalSearchState(model, Random(1), projection = projection)
        val second = LocalSearchState(model, Random(2), projection = projection)
        for (state in listOf(first, second)) {
            state.assignment.setInt(0, 0)
            state.assignment.setBool(0, true)
            state.recompute()
        }
        val cost = second.cost
        val delta = second.netDelta(Move.IntSet(0, 1))

        first.apply(Move.IntSet(0, 1))

        assertTrue(first.cost > cost)
        assertEquals(0L, second.assignment.intValue(0))
        assertEquals(cost, second.cost)
        assertEquals(delta, second.netDelta(Move.IntSet(0, 1)))
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
