package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocalSearchMoveInspectionTest {
    @Test
    fun `inspection preserves violated factor sampling order`() {
        val problem = Problem(2, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true))),
            Clause(intArrayOf(Lit.make(1, true))),
        ))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
        state.recompute()
        val before = state.violated.toIntArray()

        state.inspectMove(Move.BoolFlip(0))

        assertContentEquals(before, state.violated.toIntArray())
    }

    @Test
    fun `inspection restores grades and search activity without random draws`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf(
            Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
        ))
        val random = object : Random() {
            override fun nextBits(bitCount: Int): Int = error("inspection consumed a random draw")
        }
        val state = LocalSearchState(LocalSearchModel.open(problem), random)
        state.assignment.setInt(1, 3)
        state.recompute()
        val before = state.assignment.snapshot()
        val degrees = state.factorDegree.copyOf()
        val touched = state.tabu.lastTouched.copyOf()
        val counts = state.tabu.touchCount.copyOf()
        val changes = state.intConfChange.copyOf()
        val best = state.bestCostSeen

        val observed = state.inspectMove(Move.IntSet(0, 2), LinearObjective(intCoefficients = longArrayOf(1)))

        assertEquals(-2L, observed.netDelta)
        assertEquals(2.0, observed.objectiveDelta)
        assertEquals(2L, observed.assignment.intValue(0))
        assertContentEquals(intArrayOf(1), observed.factorDegrees)
        assertContentEquals(before.ints, state.assignment.snapshot().ints)
        assertContentEquals(degrees, state.factorDegree)
        assertContentEquals(touched, state.tabu.lastTouched)
        assertContentEquals(counts, state.tabu.touchCount)
        assertContentEquals(changes, state.intConfChange)
        assertEquals(0L, state.step)
        assertEquals(3L, state.cost)
        assertEquals(best, state.bestCostSeen)
        assertFalse(state.weights.allocated)
    }

    @Test
    fun `inspection includes maintained compound outputs and restores them`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 8) }, arrayOf(
            Linear(longArrayOf(1, 1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
        ))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
        state.invariants = DefinitionalSweep.infer(problem, intArrayOf(2))?.network(3, 0)
        state.recompute()

        val observed = state.inspectMove(
            Move.Compound(listOf(Move.IntSet(0, 2), Move.IntSet(1, 3))),
            LinearObjective(intCoefficients = longArrayOf(0, 0, 1)),
        )

        assertContentEquals(longArrayOf(2, 3, 5), observed.assignment.ints)
        assertContentEquals(intArrayOf(0), observed.factorDegrees)
        assertEquals(5.0, observed.objectiveDelta)
        assertContentEquals(longArrayOf(0, 0, 0), state.assignment.snapshot().ints)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `inspection preserves the walk across instruction slices`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 4)), arrayOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10),
        ))
        fun trace(inspect: Boolean, slice: Long): List<Long> {
            val values = ArrayList<Long>()
            val strategy = SourceDrivenStrategy(emptyList(), feasibleDescent = FeasibleDescent.SelfOwned, perturbation = { state ->
                values += state.assignment.intValue(0)
                if (inspect) state.inspectMove(Move.IntSet(0, 4))
                val next = state.rng.nextInt(5).toLong()
                values += next
                Move.IntSet(0, next)
            })
            val engine = LocalSearchEngine(
                LocalSearchModel.open(problem), strategy = strategy, greedyRepairOnRestart = false,
            )
            val params = LocalSearchParams(maxFlips = 4, randomSeed = 7)
            engine.resumable(LinearObjective(intCoefficients = longArrayOf(1)), params).use { run ->
                val sliced = run as InstructionSlicedSearch
                while (sliced.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, slice) {} == null) {
                    check(values.size < 100)
                }
            }
            return values
        }

        val control = trace(false, Long.MAX_VALUE)
        val observed = trace(true, 1)

        assertEquals(control, observed)
    }
}
