package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CompletedMoveEvaluationTest {
    @Test
    fun `completion admits only the ranked and exploratory proposals`() {
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 1) },
            arrayOf<Factor>(Linear(intArrayOf(0, 1, 2), doubleArrayOf(1.0, 1.0, 1.0),
                intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setReal(0, 1.0)
        state.recompute()
        val initial = state.assignment.snapshot()
        var calls = 0
        val evaluation = CompletedMoveEvaluation(
            state, LinearObjective(intCoefficients = longArrayOf(1, 1, 1), realCoefficients = doubleArrayOf(10.0)),
            Cancellation.Never, mayAttempt = { true },
            complete = { candidate ->
                calls++
                Completion.Witness(candidate.copy(reals = doubleArrayOf(0.0)))
            }, current = { initial },
        )
        evaluation.begin()
        val sink = MoveSink()
        val compound = Move.Compound(listOf(Move.IntSet(1, 1), Move.IntSet(2, 1)))
        sink.addCompound(compound.parts)
        evaluation.seed(sink)
        val moves = List(3) { Move.IntSet(it, 1) }

        evaluation.prepare(moves + sink.list) { move ->
            if (move is Move.IntSet) 3.0 - move.varId else 5.0
        }

        assertEquals(2, calls)
        assertNotNull(evaluation.sample(moves[0]))
        assertNull(evaluation.sample(moves[1]))
        assertNotNull(evaluation.sample(moves[2]))
        assertNull(evaluation.sample(compound))
        assertEquals(-9.0, evaluation.score(moves[0]))
        assertEquals(initial, state.assignment.snapshot())
        assertEquals(0L, state.step)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `an exact finite violation declines a numerical completion proposal`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
            ),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setReal(0, 1.0)
        state.recompute()
        val initial = state.assignment.snapshot()
        var calls = 0
        val evaluation = CompletedMoveEvaluation(
            state, LinearObjective(intCoefficients = longArrayOf(1)), Cancellation.Never,
            mayAttempt = { true }, complete = { candidate -> calls++; Completion.Witness(candidate) },
            current = { initial },
        )

        val completed = evaluation.attempt(Move.IntSet(0, 1))

        assertNull(completed)
        assertEquals(0, calls)
        assertEquals(initial, state.assignment.snapshot())
        assertEquals(0L, state.cost)
    }

    @Test
    fun `real guesses do not displace finite completion proposals`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 1)), emptyArray(),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.recompute()
        val initial = state.assignment.snapshot()
        val pins = mutableListOf<Long>()
        val evaluation = CompletedMoveEvaluation(
            state, LinearObjective(intCoefficients = longArrayOf(1)), Cancellation.Never,
            mayAttempt = { true }, complete = { candidate ->
                pins += candidate.intValue(0)
                Completion.Witness(candidate)
            }, current = { initial },
        )
        val finite = Move.IntSet(0, 1)
        val real = Move.RealSet(0, 1.0)

        evaluation.prepare(listOf(real, finite)) { if (it == real) -10.0 else 1.0 }
        evaluation.attempt(real)

        assertEquals(listOf(1L), pins)
        assertNotNull(evaluation.sample(finite))
        assertNull(evaluation.sample(real))
        assertEquals(initial, state.assignment.snapshot())
    }

    @Test
    fun `a throwing completion leaves the live assignment unchanged`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 1)), emptyArray(),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setReal(0, 1.0)
        state.recompute()
        val initial = state.assignment.snapshot()
        val evaluation = CompletedMoveEvaluation(
            state, LinearObjective(intCoefficients = longArrayOf(1)), Cancellation.Never,
            mayAttempt = { true }, complete = { error("failed completion") }, current = { initial },
        )

        assertFailsWith<IllegalStateException> { evaluation.attempt(Move.IntSet(0, 1)) }

        assertEquals(initial, state.assignment.snapshot())
        assertEquals(0L, state.step)
        assertEquals(0L, state.cost)
    }
}
