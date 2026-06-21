package com.eignex.klause.solver

import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionTest {

    @Test
    fun `empty session forwards to solver unchanged`() {
        val problem = exactlyOneOver(3)
        val session = BacktrackSolver(problem).session()
        assertEquals(0, session.depth)
        val r = session.solve(BacktrackParams(randomSeed = 0L))
        assertTrue(r is SolveResult.Sat)
    }

    @Test
    fun `push pins a variable and pop reverts`() {
        val problem = exactlyOneOver(3)
        val session = BacktrackSolver(problem).session()

        session.push(Assumptions(bools = mapOf(1 to true)))
        assertEquals(1, session.depth)
        val withPin = session.solve(BacktrackParams(randomSeed = 0L))
        assertTrue(withPin is SolveResult.Sat)
        assertTrue(withPin.assignment.bools[1], "pushed pin should force var 1 = true")

        session.pop()
        assertEquals(0, session.depth)
    }

    @Test
    fun `nested pushes merge with last-write semantics`() {
        val problem = exactlyOneOver(3)
        val session = BacktrackSolver(problem).session()

        session.push(Assumptions(bools = mapOf(0 to true, 1 to false, 2 to false)))
        session.push(Assumptions(bools = mapOf(1 to true))) // overrides 1 = false
        val r = session.solve(BacktrackParams(randomSeed = 0L))
        assertIs<SolveResult.Unsat>(r)

        session.pop()
        val r2 = session.solve(BacktrackParams(randomSeed = 0L))
        assertTrue(r2 is SolveResult.Sat)
        assertTrue(r2.assignment.bools[0])
    }

    @Test
    fun `pop on empty stack throws`() {
        val session = BacktrackSolver(exactlyOneOver(2)).session()
        assertFails { session.pop() }
    }

    @Test
    fun `local search session honors pushed assumptions`() {
        val problem = exactlyOneOver(4)
        val session = LocalSearchSolver(problem).session()

        session.push(Assumptions(bools = mapOf(2 to true)))
        val samples = session.samples(LocalSearchParams(maxFlips = 5_000, randomSeed = 0L))
            .take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            assertTrue(s.bools[2], "pushed pin should force var 2 = true in every sample")
        }
    }

    private fun exactlyOneOver(n: Int): Problem {
        val factor = Cardinality.exactlyOne(
            IntArray(n) { Lit.make(it, true) },
        )
        return Problem(n, 0, emptyArray(), listOf(factor))
    }
}
