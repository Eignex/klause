package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSearchOperationTest {
    @Test
    fun `interrupted relation scoring should preserve the assignment and subsequent exact scores`() {
        val length = 300
        val sequence = IntArray(length)
        val tuples = LongArray(length * 2) { if (it < length) 0L else 1L }
        val relations = listOf(
            Table(sequence, tuples),
            Table(sequence, tuples, tuples.copyOf()),
            Mdd(sequence, IntArray(length + 1) { 1 }, IntArray(length + 1) { it * 6 },
                LongArray(length * 6) { if (it % 6 == 4) 1L else 0L }, 0, intArrayOf(0), 3),
        )
        for (factor in relations) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            state.assignment.setInt(0, 2)
            state.recompute()
            val before = state.assignment.snapshot()
            val cost = state.cost
            val move = Move.IntSet(0, 1)
            val delta = state.netDelta(move)
            var polls = 0

            val interrupted = state.scoringPick(Cancellation { ++polls == 2 }) {
                state.netDelta(move)
                move
            }

            assertNull(interrupted)
            assertEquals(2, polls)
            assertEquals(before, state.assignment.snapshot())
            assertEquals(cost, state.cost)
            assertEquals(delta, state.netDelta(move))
            state.apply(move)
            assertEquals(cost + delta, state.cost)
        }
    }

    @Test
    fun `stepped compound edits should reconcile definitions and retain one tabu step`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 4) }, arrayOf<Factor>(
            Product(0, 1, 2),
            Table(intArrayOf(0, 2, 0), longArrayOf(0, 0, 0, 1, 2, 1, 2, 4, 2)),
            Mdd(intArrayOf(2), intArrayOf(1, 1), intArrayOf(0, 12),
                longArrayOf(0, 0, 0, 1, 0, 2, 0, 2, 0, 4, 0, 3), 0, intArrayOf(0), 4, 3),
        ))
        val ordinary = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        val stepped = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        for (state in listOf(ordinary, stepped)) {
            state.invariants = checkNotNull(DefinitionalSweep.infer(problem)).network(4, 0)
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 2)
            state.assignment.setInt(2, 0)
            state.assignment.setInt(3, 1)
            state.recompute()
        }
        val move = Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(3, 2)))
        ordinary.apply(move)
        val cursor = stepped.applySteps(move).iterator()
        var checkpoints = 0
        while (cursor.hasNext()) {
            cursor.next()
            checkpoints++
            assertFailsWith<IllegalStateException> { stepped.apply(Move.IntSet(0, 0)) }
        }

        assertTrue(checkpoints > 1)
        assertEquals(ordinary.assignment.snapshot(), stepped.assignment.snapshot())
        assertEquals(ordinary.cost, stepped.cost)
        assertContentEquals(ordinary.factorDegree, stepped.factorDegree)
        assertEquals(ordinary.step, stepped.step)
        assertEquals(ordinary.netDelta(Move.IntSet(0, 2)), stepped.netDelta(Move.IntSet(0, 2)))
    }

    @Test
    fun `satisfaction should retain a selected compound through a suspended table update`() {
        val tuples = LongArray(2048) { (it / 2 % 2).toLong() }
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) },
            arrayOf<Factor>(Table(intArrayOf(0, 1), tuples)))
        var selected = false
        var picks = 0
        var polls = 0
        val strategy = SourceDrivenStrategy(emptyList(), feasibleDescent = FeasibleDescent.SelfOwned,
            perturbation = {
                picks++
                selected = picks == 2
                if (picks == 1) Move.IntSet(0, 1) else {
                    Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(1, 1)))
                }
            })
        val engine = LocalSearchEngine(LocalSearchModel.open(problem), strategy, greedyRepairOnRestart = false)
        val params = LocalSearchParams(maxFlips = 3L, randomSeed = 0L,
            initialAssignment = Sample(booleanArrayOf(), longArrayOf(2, 2)))
        engine.resumableSolve(params).use { handle ->
            assertNull(handle.runSlice(Cancellation { selected && ++polls >= 2 }, Long.MAX_VALUE, -1L))
            assertEquals(2, picks)
            assertEquals(1.0, handle.stats.ls.moves.sum)

            val result = handle.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L)

            val sample = assertIs<SolveResult.Sat>(result).assignment
            assertEquals(1L, sample.intValue(0))
            assertEquals(1L, sample.intValue(1))
            assertEquals(2, picks)
            assertEquals(2.0, handle.stats.ls.moves.sum)
        }
    }
}
