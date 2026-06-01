package com.eignex.klause.bench.format.smtlib

import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmtLibQfLiaTest {

    @Test
    fun `parses conjunctive + disjunctive QF_LIA and solves SAT`() {
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (>= y 0))
            (assert (<= (+ x y) 10))
            (assert (or (>= x 7) (>= y 7)))
            (check-sat)
        """.trimIndent()
        val ing = SmtLibQfLia.parse(text)
        assertEquals(2, ing.problem.numIntVars)
        val r = ChocoSolver(ing.problem).solve(ChocoParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        val x = r.assignment.ints[0]; val y = r.assignment.ints[1]
        assertTrue(x >= 0 && y >= 0 && x + y <= 10 && (x >= 7 || y >= 7), "solution violates constraints: x=$x y=$y")
    }

    @Test
    fun `parses objective and finds optimum`() {
        val text = """
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (>= y 0))
            (assert (<= (+ x y) 10))
            (assert (or (>= x 7) (>= y 7)))
            (minimize (+ x y))
        """.trimIndent()
        val ing = SmtLibQfLia.parse(text)
        val obj = requireNotNull(ing.objective)
        val r = ChocoSolver(ing.problem).minimize(obj, ChocoParams())
        assertTrue(r is MinimizeResult.Optimal, "expected Optimal, got $r")
        assertEquals(7.0, r.objective)
    }
}
