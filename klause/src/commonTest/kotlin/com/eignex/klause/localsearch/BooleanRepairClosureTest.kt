package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooleanRepairClosureTest {
    @Test
    fun `objective seeds follow forced ternary consequences before ranking`() {
        val problem = Problem(4, 0, emptyArray(), arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
        ))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(3, true)
        state.invariants = DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(3, intArrayOf(Lit.make(0, false)), true),
        ))?.network(0, 4)
        state.shaping.objective = LinearObjective(boolWeights = longArrayOf(0, 0, 0, 1))
        state.recompute()
        state.booleanRepairs = BooleanRepairClosure.preparation(state).filterNotNull().first().closure
        val strategy = SourceDrivenStrategy(
            sources = listOf(ConfiguredSource(ObjectiveSeed())),
            feasibleAcceptance = AcceptanceRule.GreedyDescent,
            feasibleDescent = FeasibleDescent.SelfOwned,
        )

        state.apply(assertNotNull(strategy.pickMove(state)))

        assertTrue((0..2).all { state.assignment.boolValue(it) })
        assertFalse(state.assignment.boolValue(3))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `a pinned consequence rejects a Boolean repair`() {
        val problem = Problem(4, 0, emptyArray(), arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
        ))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0), Assumptions.None.withBool(2, false))
        state.assignment.setBool(3, true)
        state.invariants = DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(3, intArrayOf(Lit.make(0, false)), true),
        ))?.network(0, 4)
        state.recompute()
        val closure = assertNotNull(BooleanRepairClosure.preparation(state).filterNotNull().first().closure)

        val move = closure.close(state, Move.BoolFlip(0), Cancellation.Never)

        assertNull(move)
        assertFalse(state.assignment.boolValue(0))
        assertFalse(state.assignment.boolValue(2))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `canceled closure preserves assignment and write activity`() {
        val problem = Problem(2, 0, emptyArray(), arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
        ))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(1, true)
        state.invariants = DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(1, intArrayOf(Lit.make(0, false)), true),
        ))?.network(0, 2)
        state.recompute()
        val closure = assertNotNull(BooleanRepairClosure.preparation(state).filterNotNull().first().closure)

        var polls = 0
        val move = closure.close(state, Move.BoolFlip(0), Cancellation { ++polls >= 4 })

        assertNull(move)
        assertFalse(state.assignment.boolValue(0))
        assertTrue(state.assignment.boolValue(1))
        assertEquals(0L, state.step)
        assertTrue(state.tabu.touchCount.all { it == 0 })
    }

    @Test
    fun `duplicate flips cancel before closure`() {
        val problem = Problem(4, 0, emptyArray(), arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
        ))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(3, true)
        state.invariants = DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(3, intArrayOf(Lit.make(0, false)), true),
        ))?.network(0, 4)
        state.recompute()
        val closure = assertNotNull(BooleanRepairClosure.preparation(state).filterNotNull().first().closure)
        val proposed = Move.Compound(listOf(Move.BoolFlip(0), Move.BoolFlip(0), Move.BoolFlip(1)))

        state.apply(assertNotNull(closure.close(state, proposed, Cancellation.Never)))

        assertFalse(state.assignment.boolValue(0))
        assertTrue(state.assignment.boolValue(1))
        assertFalse(state.assignment.boolValue(2))
        assertTrue(state.assignment.boolValue(3))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `models without unit definitions retain primitive neighborhoods`() {
        val problem = Problem(2, 0, emptyArray(), arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
        ))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.recompute()
        state.booleanRepairs = BooleanRepairClosure.preparation(state).filterNotNull().first().closure
        val strategy = SourceDrivenStrategy(
            sources = emptyList(), perturbation = { Move.BoolFlip(0) }, feasibleDescent = FeasibleDescent.SelfOwned,
        )

        val move = strategy.pickMove(state)

        assertEquals(Move.BoolFlip(0), move)
    }

    @Test
    fun `suspended preparation retains all clause consequences`() {
        val count = 270
        val factors = Array<Factor>(count + 1) { index ->
            when {
                index < count - 1 -> Clause(intArrayOf(Lit.make(index, false), Lit.make(index + 1, true)))
                index == count - 1 -> Clause(intArrayOf(Lit.make(0, true), Lit.make(count, true)))
                else -> Clause(intArrayOf(Lit.make(0, false), Lit.make(count, false)))
            }
        }
        val problem = Problem(count + 1, 0, emptyArray(), factors)
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(count, true)
        state.invariants = DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(count, intArrayOf(Lit.make(0, false)), true),
        ))?.network(0, count + 1)
        state.recompute()
        val preparation = BooleanRepairClosure.preparation(state).iterator()
        assertNull(preparation.next())

        val closure = assertNotNull(preparation.asSequence().filterNotNull().first().closure)
        state.apply(assertNotNull(closure.close(state, Move.BoolFlip(0), Cancellation.Never)))

        assertTrue((0 until count).all { state.assignment.boolValue(it) })
        assertFalse(state.assignment.boolValue(count))
        assertEquals(0L, state.cost)
    }
}
