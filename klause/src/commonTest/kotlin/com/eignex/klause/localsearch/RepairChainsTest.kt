package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.IntHashSet
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class RepairChainsTest {

    @Test
    fun `first sampling preserves seeded chains and the next random draw`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 6), IntDomain(0, 6)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 6),
            ),
        ).bake()
        for (cap in intArrayOf(0, 1, 4, 20)) for (seed in 0 until 6) {
            val expectedState = LocalSearchState(problem, Random(seed))
            val actualState = LocalSearchState(problem, Random(seed))
            for (state in listOf(expectedState, actualState)) {
                state.assignment.setInt(0, 0)
                state.assignment.setInt(1, 6)
                state.recompute()
            }
            val firsts = MoveSink()
            for (value in 1L..6L) firsts.addIntSet(0, value)
            firsts.addCompound(listOf(Move.IntSet(0, 3), Move.IntSet(1, 5)))
            val expected = MoveSink()
            val list = firsts.list
            val order = IntArray(list.size) { it }
            val propose = MoveSink()
            for (i in 0 until minOf(cap, list.size)) {
                val j = i + expectedState.rng.nextInt(list.size - i)
                val tmp = order[i]
                order[i] = order[j]
                order[j] = tmp
                expectedState.buildRepairChain(list[order[i]], 4, propose)?.let { expected.addCompound(it) }
            }

            val actual = MoveSink()
            actualState.sampleChainFirsts(firsts, cap, 4, MoveSink(), actual)

            assertEquals(expected.list, actual.list, "seed=$seed cap=$cap")
            assertEquals(expectedState.rng.nextLong(), actualState.rng.nextLong(), "seed=$seed cap=$cap")
        }
    }

    @Test
    fun `successive chain proposals restore activity and factor degrees`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 6), IntDomain(0, 6)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 6),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 6)
        state.recompute()
        state.apply(Move.IntSet(1, 5))
        val degrees = state.factorDegree.copyOf()
        val touched = state.tabu.lastTouched.copyOf()
        val counts = state.tabu.touchCount.copyOf()
        val conf = state.intConfChange.copyOf()
        val step = state.step
        val best = state.bestCostSeen
        val cost = state.cost

        repeat(12) { state.proposeRepairChains(0, 4, 4, MoveSink()) }

        assertEquals(0L, state.assignment.intValue(0))
        assertEquals(5L, state.assignment.intValue(1))
        assertEquals(cost, state.cost)
        assertEquals(step, state.step)
        assertEquals(best, state.bestCostSeen)
        assertContentEquals(degrees, state.factorDegree)
        assertContentEquals(touched, state.tabu.lastTouched)
        assertContentEquals(counts, state.tabu.touchCount)
        assertContentEquals(conf, state.intConfChange)
    }

    @Test
    fun `chain primitives step across a hole and keep the endpoints`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 6).excludeValue(3), IntDomain(0, 6)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 20),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 20),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 4)
        state.recompute()
        val sink = MoveSink()

        state.emitFactorPrimitives(seed = 0, nf = 1, seenFactors = IntHashSet(), sink = sink)

        val xTargets = sink.list.filterIsInstance<Move.IntSet>().filter { it.varId == 0 }.map { it.newValue }
        assertEquals(setOf(0L, 2L, 5L, 6L), xTargets.toSet(), "the step below 4 must skip the hole at 3")
    }
}
