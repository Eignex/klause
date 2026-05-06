package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolverState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ClauseWatchedLitTest {

    @Test
    fun isViolatedAgreesWithBruteForceOverEveryAssignment() {
        // Mixed-polarity 5-literal clause exercises both watch placement and rewatch logic.
        val literals = intArrayOf(
            Lit.make(0, true), Lit.make(1, false), Lit.make(2, true),
            Lit.make(3, false), Lit.make(4, true),
        )
        val clause = Clause(literals)
        val problem = Problem(5, 0, emptyArray(), listOf(clause))
        val state = SolverState(problem, Random(0))
        for (mask in 0..31) {
            for (i in 0..4) state.assignment.setBool(i, (mask shr i) and 1 == 1)
            state.recompute()
            val expected = naiveIsViolated(literals, state)
            assertEquals(expected, clause.isViolated(state, 0), "mask=$mask")
        }
    }

    @Test
    fun watchedLiteralsSurviveLongFlipSequence() {
        // Apply a deterministic sequence of flips and verify isViolated stays correct after
        // each one. This exercises the rewatch path.
        val literals = intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, false),
            Lit.make(3, true), Lit.make(4, false), Lit.make(5, true),
        )
        val clause = Clause(literals)
        val problem = Problem(6, 0, emptyArray(), listOf(clause))
        val state = SolverState(problem, Random(0))
        // Initial assignment all-false.
        for (i in 0..5) state.assignment.setBool(i, false)
        state.recompute()

        // Sequence: flip 0, 0, 1, 2, 3, 0, 4, 5, 1, 2, 3, 4, 5, 0
        val seq = intArrayOf(0, 0, 1, 2, 3, 0, 4, 5, 1, 2, 3, 4, 5, 0)
        for (v in seq) {
            state.apply(Move.BoolFlip(v))
            val expected = naiveIsViolated(literals, state)
            assertEquals(expected, clause.isViolated(state, 0), "after flip of $v")
        }
    }

    private fun naiveIsViolated(literals: IntArray, state: SolverState): Boolean {
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) return false
        }
        return true
    }
}
