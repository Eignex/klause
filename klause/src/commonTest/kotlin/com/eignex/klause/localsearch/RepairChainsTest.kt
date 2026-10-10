package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.IntHashSet
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RepairChainsTest {

    @Test
    fun `chain repair scoring matches clause deltas after real moves`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
        ).bake()
        for (mask in 0 until 8) {
            val state = LocalSearchState(problem, Random(mask))
            for (v in 0 until 3) state.assignment.setBool(v, mask and (1 shl v) != 0)
            state.recompute()
            state.apply(Move.BoolFlip(1))
            state.apply(Move.BoolFlip(0))
            for (fid in state.factors.indices) {
                val proposals = MoveSink()
                state.factors[fid].proposeRepairMoves(state, fid, proposals)
                val expectedDelta = proposals.list.minOfOrNull { state.netDelta(it) }

                val picked = state.pickChainRepair(fid, IntHashSet(), MoveSink())

                assertEquals(expectedDelta, picked?.let { state.netDelta(it) }, "mask=$mask fid=$fid")
            }
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
