package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.Objective
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MixedExactLinearScoreTest {
    @Test
    fun `candidate deltas should agree with fresh scores for each relation and truth direction`() {
        for ((relation, strict) in listOf(LinearOp.LE to false, LinearOp.GE to false,
            LinearOp.LE to true, LinearOp.GE to true, LinearOp.EQ to false, LinearOp.NE to false)) {
            for (activator in listOf(-1, 0)) {
                val row = ExactLinear.of(
                    intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0), Term.ofRealVar(1),
                        Term.ofRealVar(0), Term.ofIntVar(1)),
                    listOf(2L, -2L, 1L, -1L, 1L).map(BigFraction::ofLong),
                    relation, BigFraction.ONE, strict, activator,
                )
                val source = Problem(1, 2, Array(2) { IntDomain(-2, 2) }, arrayOf(row), numRealVars = 2,
                    realLower = doubleArrayOf(-2.0, -2.0), realUpper = doubleArrayOf(2.0, 2.0))
                val model = LocalSearchModel.open(source)
                val state = LocalSearchState(model, Random(3))
                val fresh = LocalSearchState(model, Random(3), projection = state.projection)
                state.recompute()
                val moves = listOf(Move.IntSet(0, 2), Move.RealSet(0, 1.0), Move.BoolFlip(0),
                    Move.RealSet(1, 0.0), Move.IntSet(1, 0), Move.BoolFlip(0),
                    Move.Compound(listOf(Move.IntSet(0, -2), Move.RealSet(0, -1.0))),
                    Move.Compound(listOf(Move.RealSet(0, 2.0), Move.RealSet(0, 0.5), Move.BoolFlip(0))))

                for (move in moves) {
                    state.assignment.copyInto(fresh.assignment)
                    fresh.recompute()
                    val before = state.cost
                    val predicted = state.netDelta(move)
                    fresh.apply(move)
                    fresh.recompute()

                    assertEquals(fresh.cost - before, predicted, "$relation $strict $activator $move")
                    assertEquals(before, state.cost)
                    state.apply(move)
                    assertEquals(fresh.cost, state.cost)
                }
            }
        }
    }

    @Test
    fun `indicator transitions should retain row changes made while disabled`() {
        for (whenOne in listOf(false, true)) {
            for (aliased in listOf(false, true)) {
                val row = ExactLinear.of(intArrayOf(Term.ofIntVar(if (aliased) 0 else 1), Term.ofRealVar(0)),
                    listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.EQ, BigFraction.ONE)
                val source = Problem(0, 2, Array(2) { IntDomain(0, 1) },
                    arrayOf<Factor>(LinearIndicator(0, whenOne, row)), numRealVars = 1,
                    realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
                val model = LocalSearchModel.open(source)
                val state = LocalSearchState(model, Random(3))
                val fresh = LocalSearchState(model, Random(3), projection = state.projection)
                state.assignment.setInt(0, if (whenOne) 0 else 1)
                state.recompute()
                val moves = listOf(Move.RealSet(0, 2.0), Move.IntSet(1, 1),
                    Move.IntSet(0, if (whenOne) 1 else 0), Move.RealSet(0, 0.0),
                    Move.Compound(listOf(Move.IntSet(0, 0), Move.RealSet(0, 1.0), Move.IntSet(0, 1))))

                for (move in moves) {
                    state.assignment.copyInto(fresh.assignment)
                    fresh.recompute()
                    val before = state.cost
                    val delta = state.netDelta(move)
                    fresh.apply(move)
                    fresh.recompute()
                    state.apply(move)

                    assertEquals(fresh.cost - before, delta, "$whenOne $aliased $move")
                    assertEquals(fresh.cost, state.cost)
                }
            }
        }
    }

    @Test
    fun `repeated probes and large cancelling terms should retain fresh scores`() {
        val row = ExactLinear.of(IntArray(5) { Term.ofRealVar(it) }, List(5) { BigFraction.ONE },
            LinearOp.EQ, BigFraction.ZERO)
        val model = LocalSearchModel.open(Problem(0, 0, emptyArray(), arrayOf(row), numRealVars = 5,
            realLower = DoubleArray(5) { -1e16 }, realUpper = DoubleArray(5) { 1e16 }))
        val state = LocalSearchState(model, Random(3))
        state.assignment.setReal(0, 1e16)
        state.assignment.setReal(1, -1e16)
        state.assignment.setReal(2, 0.25)
        state.recompute()
        val fresh = LocalSearchState(model, Random(3), projection = state.projection)
        state.weights.factorWeights[0] = 2.5

        repeat(64) { step ->
            val move = Move.Compound(listOf(Move.RealSet(0, 0.0),
                Move.RealSet(2, step / 8.0), Move.RealSet(0, 1e16)))
            state.assignment.copyInto(fresh.assignment)
            fresh.recompute()
            val before = state.cost
            val snapshot = state.assignment.snapshot()
            val delta = state.netDelta(move)
            val weighted = state.weightedNetDelta(move)
            fresh.apply(move)
            fresh.recompute()

            assertEquals(snapshot, state.assignment.snapshot())
            assertEquals(before, state.cost)
            assertEquals(fresh.cost - before, delta)
            assertEquals(delta * 2.5, weighted)
            state.apply(move)
            assertEquals(fresh.cost, state.cost)
        }
    }

    @Test
    fun `restart and external writes should rebuild only the owning state activity`() {
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
            listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.EQ, BigFraction.ZERO)
        val model = LocalSearchModel.open(Problem(0, 1, arrayOf(IntDomain(-2, 2)), arrayOf(row), numRealVars = 1,
            realLower = doubleArrayOf(-2.0), realUpper = doubleArrayOf(2.0)))
        val state = LocalSearchState(model, Random(3))
        val sibling = LocalSearchState(model, Random(3), projection = state.projection)
        state.recompute()
        sibling.recompute()
        val siblingCost = sibling.cost
        state.assignment.setInt(0, 1)
        state.assignment.setReal(0, -1.0)
        state.recompute()

        assertEquals(0L, state.cost)
        assertEquals(siblingCost, sibling.cost)
        state.restart()
        val cost = state.cost
        state.recompute()
        assertEquals(cost, state.cost)
        assertEquals(siblingCost, sibling.cost)
    }

    @Test
    fun `cancelled compound grading should restore activity before later candidates`() {
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
            listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.EQ, BigFraction.ZERO)
        val state = LocalSearchState(LocalSearchModel.open(Problem(0, 1, arrayOf(IntDomain(0, 1)),
            arrayOf(row), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))), Random(3))
        state.recompute()
        val before = state.assignment.snapshot()
        val cost = state.cost
        val move = Move.Compound(listOf(Move.IntSet(0, 1), Move.RealSet(0, 2.0)))
        val expected = state.netDelta(move)
        val objective = object : Objective {
            override fun evaluate(sample: Sample): Double {
                if (sample.approximateRealValue(0) == 2.0) throw CancellationException("grading cancelled")
                return 0.0
            }
        }

        assertFailsWith<CancellationException> { state.objectiveDelta(objective, move) }

        assertEquals(before, state.assignment.snapshot())
        assertEquals(cost, state.cost)
        assertEquals(expected, state.netDelta(move))
        state.recompute()
        assertEquals(cost, state.cost)
    }

    @Test
    fun `integer endpoint proposals should preserve the sign of the replacement product`() {
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
            listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.LE, BigFraction.ZERO)
        val state = LocalSearchState(LocalSearchModel.open(Problem(0, 1,
            arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)), arrayOf(row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))), Random(3))
        state.assignment.setInt(0, Long.MAX_VALUE)
        state.recompute()
        val before = state.cost
        val move = Move.IntSet(0, Long.MIN_VALUE)

        val delta = state.netDelta(move)
        state.apply(move)

        assertTrue(before > 0L)
        assertEquals(-before, delta)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `nonfinite activities should stay violated for every relation and reifier`() {
        for (relation in LinearOp.entries) {
            val row = ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                relation, BigFraction.ZERO, activator = 0)
            val state = LocalSearchState(LocalSearchModel.open(Problem(1, 0, emptyArray(), arrayOf(row),
                numRealVars = 3, realLower = DoubleArray(3) { -Double.MAX_VALUE },
                realUpper = DoubleArray(3) { Double.MAX_VALUE })), Random(3))
            for (truth in listOf(false, true)) {
                state.assignment.setBool(0, truth)
                state.assignment.setReal(0, Double.MAX_VALUE)
                state.assignment.setReal(1, Double.MAX_VALUE)
                state.assignment.setReal(2, -Double.MAX_VALUE)
                state.recompute()

                assertTrue(state.cost > 0L, "$relation $truth")
            }
        }
    }
}
