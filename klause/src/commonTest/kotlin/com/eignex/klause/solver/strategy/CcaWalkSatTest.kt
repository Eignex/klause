package com.eignex.klause.solver.strategy

import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.solver.LocalSearchSolver
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolverState
import com.eignex.klause.solver.factor.Clause
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CcaWalkSatTest {

    @Test
    fun confChangeIsResetOnFlipAndPropagatedToNeighbors() {
        // Three bool vars, one ternary clause — every var is a neighbor of every other.
        val factor = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = SolverState(problem, Random(0))
        state.restart()
        // After restart all flags must be true.
        assertTrue(state.boolConfChange.all { it })

        state.apply(Move.BoolFlip(0))
        // Var 0 just flipped → its own confChange is false; vars 1 and 2 (its neighbors
        // through the shared clause) are now true.
        assertEquals(false, state.boolConfChange[0])
        assertEquals(true, state.boolConfChange[1])
        assertEquals(true, state.boolConfChange[2])
    }

    @Test
    fun restartResetsConfChangeToTrue() {
        val factor = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = SolverState(problem, Random(0))
        state.restart()
        state.apply(Move.BoolFlip(0))
        assertEquals(false, state.boolConfChange[0])
        state.restart()
        assertTrue(state.boolConfChange.all { it })
    }

    @Test
    fun ccaWalkSatSolvesSmall3Sat() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val problem = Problem(3, 0, emptyArray(), clauses)
        val solver = LocalSearchSolver(problem, strategy = CcaWalkSat())
        val sample = solver.sample(LocalSearchParams(maxFlips = 20_000L, randomSeed = 7L))
        assertNotNull(sample, "CcaWalkSat should find a satisfying assignment within budget")
        for (clause in clauses) {
            val sat = clause.literals.any { lit ->
                Lit.evaluate(lit, sample.bools[Lit.variable(lit)])
            }
            assertEquals(true, sat)
        }
    }
}
