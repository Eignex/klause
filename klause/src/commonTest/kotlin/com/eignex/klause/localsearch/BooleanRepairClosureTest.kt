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
    fun `joint implications should complete within a linear clause visit allowance`() {
        val count = 300
        val output = count + 2
        val factors = Array<Factor>(count + 3) { index ->
            when {
                index < count -> Clause(intArrayOf(Lit.make(0, false), Lit.make(index + 1, true)))
                index == count -> Clause(IntArray(count + 1) {
                    if (it < count) Lit.make(it + 1, false) else Lit.make(count + 1, true)
                })
                index == count + 1 -> Clause(intArrayOf(Lit.make(0, true), Lit.make(output, true)))
                else -> Clause(intArrayOf(Lit.make(0, false), Lit.make(output, false)))
            }
        }
        val problem = Problem(output + 1, 0, emptyArray(), factors)
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(output, true)
        state.invariants = DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(output, intArrayOf(Lit.make(0, false)), true),
        ))?.network(0, output + 1)
        state.recompute()
        val closure = assertNotNull(BooleanRepairClosure.preparation(state).filterNotNull().first().closure)
        var polls = 0

        val move = closure.close(state, Move.BoolFlip(0), Cancellation { ++polls > 16 })
        state.apply(assertNotNull(move))

        assertTrue((0 until output).all { state.assignment.boolValue(it) })
        assertFalse(state.assignment.boolValue(output))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `queued mandatory clauses should settle before replacement support choices`() {
        val problem = Problem(6, 0, emptyArray(), arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(3, true), Lit.make(4, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, false))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(5, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(5, false))),
        ))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(5, true)
        state.invariants = DefinitionalSweep.infer(problem, boolFolds = listOf(
            BoolFoldDefinition(5, intArrayOf(Lit.make(0, false)), true),
        ))?.network(0, 6)
        state.shaping.objective = LinearObjective(boolWeights = longArrayOf(0, 0, 0, 1, 2, 10))
        state.recompute()
        val closure = assertNotNull(BooleanRepairClosure.preparation(state).filterNotNull().first().closure)

        state.apply(assertNotNull(closure.close(state, Move.BoolFlip(0), Cancellation.Never)))

        assertTrue(state.assignment.boolValue(1))
        assertTrue(state.assignment.boolValue(2))
        assertFalse(state.assignment.boolValue(3))
        assertTrue(state.assignment.boolValue(4))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `closed pools should retain primitive lane order`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(2, true)
        state.invariants = DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(2, intArrayOf(Lit.make(0, false)), true),
            ),
        )?.network(0, 4)
        state.recompute()
        val closure = assertNotNull(BooleanRepairClosure.preparation(state).filterNotNull().first().closure)

        val sink = MoveSink()
        closure.closeInto(state, listOf(Move.BoolFlip(0)), sink, Cancellation.Never)
        closure.closeInto(state, listOf(Move.BoolFlip(3)), sink, Cancellation.Never)

        assertEquals(Move.BoolFlip(3), sink.list.first())
    }

    @Test
    fun `mandatory consequences should settle before replacement support choices`() {
        val problem = Problem(
            5,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(4, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(4, false))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(4, true)
        state.invariants = DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(4, intArrayOf(Lit.make(0, false)), true),
            ),
        )?.network(0, 5)
        state.recompute()
        val closure = assertNotNull(BooleanRepairClosure.preparation(state).filterNotNull().first().closure)

        state.apply(assertNotNull(closure.close(state, Move.BoolFlip(0), Cancellation.Never)))

        assertFalse(state.assignment.boolValue(2))
        assertTrue(state.assignment.boolValue(3))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `replacement support prices should follow the current source batch`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(3, true)
        state.invariants = DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(3, intArrayOf(Lit.make(0, false)), true),
            ),
        )?.network(0, 4)
        state.shaping.objective = LinearObjective(boolWeights = longArrayOf(0, 5, 1, 100))
        state.recompute()
        val closure = assertNotNull(BooleanRepairClosure.preparation(state).filterNotNull().first().closure)
        closure.closeInto(state, listOf(Move.BoolFlip(0)), MoveSink(), Cancellation.Never)
        state.shaping.objective = LinearObjective(boolWeights = longArrayOf(0, 1, 5, 100))
        val sink = MoveSink()

        closure.closeInto(state, listOf(Move.BoolFlip(0)), sink, Cancellation.Never)
        state.apply(sink.list.single())

        assertTrue(state.assignment.boolValue(1))
        assertFalse(state.assignment.boolValue(2))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `objective seeds should follow forced ternary consequences before ranking`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(3, true)
        state.invariants = DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(3, intArrayOf(Lit.make(0, false)), true),
            ),
        )?.network(0, 4)
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
    fun `a pinned consequence should reject a Boolean repair`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0), Assumptions.None.withBool(2, false))
        state.assignment.setBool(3, true)
        state.invariants = DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(3, intArrayOf(Lit.make(0, false)), true),
            ),
        )?.network(0, 4)
        state.recompute()
        val closure = assertNotNull(BooleanRepairClosure.preparation(state).filterNotNull().first().closure)

        val move = closure.close(state, Move.BoolFlip(0), Cancellation.Never)

        assertNull(move)
        assertFalse(state.assignment.boolValue(0))
        assertFalse(state.assignment.boolValue(2))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `canceled closure should preserve assignment and write activity`() {
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
        state.invariants = DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(count, intArrayOf(Lit.make(0, false)), true),
            ),
        )?.network(0, count + 1)
        state.recompute()
        val closure = assertNotNull(BooleanRepairClosure.preparation(state).filterNotNull().first().closure)

        var polls = 0
        val move = closure.close(state, Move.BoolFlip(0), Cancellation { ++polls >= 2 })

        assertNull(move)
        assertTrue((0 until count).all { !state.assignment.boolValue(it) })
        assertTrue(state.assignment.boolValue(count))
        assertEquals(0L, state.step)
        assertTrue(state.tabu.touchCount.all { it == 0 })
    }

    @Test
    fun `duplicate flips should cancel before closure`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(3, true)
        state.invariants = DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(3, intArrayOf(Lit.make(0, false)), true),
            ),
        )?.network(0, 4)
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
    fun `models without unit definitions should retain primitive neighborhoods`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.recompute()
        state.booleanRepairs = BooleanRepairClosure.preparation(state).filterNotNull().first().closure
        val strategy = SourceDrivenStrategy(
            sources = emptyList(),
            perturbation = { Move.BoolFlip(0) },
            feasibleDescent = FeasibleDescent.SelfOwned,
        )

        val move = strategy.pickMove(state)

        assertEquals(Move.BoolFlip(0), move)
    }

    @Test
    fun `suspended preparation should retain all clause consequences`() {
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
        state.invariants = DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(count, intArrayOf(Lit.make(0, false)), true),
            ),
        )?.network(0, count + 1)
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
