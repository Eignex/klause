package com.eignex.klause.formats.smtlib

import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.AllDifferent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Parser tests for the SMT-LIB QF_LIA frontend, solved with klause's own backtrack engine. */
class SmtLibQfLiaTest {

    private fun solve(text: String): IntArray {
        val r = BacktrackSolver(SmtLibQfLia.parse(text).problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        return r.assignment.ints
    }

    @Test
    fun `parses conjunctive and disjunctive QF_LIA and solves SAT`() {
        val text = """
            (set-logic QF_LIA)
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (>= y 0))
            (assert (<= (+ x y) 10))
            (assert (or (>= x 7) (>= y 7)))
            (check-sat)
        """.trimIndent()
        val parsed = SmtLibQfLia.parse(text)
        assertEquals(2, parsed.problem.numIntVars)
        val ints = solve(text)
        val x = ints[0]
        val y = ints[1]
        assertTrue(x >= 0 && y >= 0 && x + y <= 10 && (x >= 7 || y >= 7), "x=$x y=$y")
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
        val parsed = SmtLibQfLia.parse(text)
        val obj = requireNotNull(parsed.objective)
        val r = BacktrackSolver(parsed.problem).minimize(obj, BacktrackParams())
        assertTrue(r is MinimizeResult.Optimal, "expected Optimal, got $r")
        assertEquals(7.0, r.objective)
    }

    @Test
    fun `let bindings expand with scoped shadowing`() {
        val text = """
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 0)) (assert (>= y 0))
            (assert (let ((s (+ x y))) (and (<= s 10) (let ((s (* 2 x))) (>= s 4)))))
            (check-sat)
        """.trimIndent()
        val ints = solve(text)
        val x = ints[0]
        val y = ints[1]
        assertTrue(x + y <= 10 && 2 * x >= 4, "x=$x y=$y")
    }

    @Test
    fun `n-ary distinct over ints maps to AllDifferent and is a permutation`() {
        val text = """
            (declare-const a Int) (declare-const b Int) (declare-const c Int)
            (assert (>= a 1)) (assert (<= a 3))
            (assert (>= b 1)) (assert (<= b 3))
            (assert (>= c 1)) (assert (<= c 3))
            (assert (distinct a b c))
            (check-sat)
        """.trimIndent()
        assertTrue(SmtLibQfLia.parse(text).problem.factors.any { it is AllDifferent }, "expected AllDifferent")
        val ints = solve(text)
        assertEquals(setOf(1, 2, 3), listOf(ints[0], ints[1], ints[2]).toSet())
    }

    @Test
    fun `distinct over bools forces inequality`() {
        val text = """
            (declare-const p Bool) (declare-const q Bool)
            (assert (distinct p q))
            (check-sat)
        """.trimIndent()
        val r = BacktrackSolver(SmtLibQfLia.parse(text).problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        assertTrue(r.assignment.bools[0] != r.assignment.bools[1], "p and q must differ")
    }

    @Test
    fun `bound inference tightens domains from constant comparisons`() {
        val text = """
            (declare-const x Int) (declare-const y Int)
            (assert (>= x 3)) (assert (<= x 7))
            (assert (<= (+ x y) 10)) (assert (>= y 0))
            (check-sat)
        """.trimIndent()
        val p = SmtLibQfLia.parse(text).problem
        assertEquals(3, p.intDomains[0].min)
        assertEquals(7, p.intDomains[0].max)
        // y >= 0 and x + y <= 10 with x >= 3 ⇒ y <= 7.
        assertEquals(0, p.intDomains[1].min)
        assertEquals(7, p.intDomains[1].max)
    }

    @Test
    fun `bound inference falls back to the default bound when unprovable`() {
        val p = SmtLibQfLia.parse("(declare-const x Int) (assert (<= x 4))", intBound = 50).problem
        assertEquals(4, p.intDomains[0].max)
        assertEquals(-50, p.intDomains[0].min) // no lower bound provable ⇒ -intBound
    }

    @Test
    fun `to_real and to_int are identity over ints`() {
        val p = SmtLibQfLia.parse(
            "(declare-const x Int) (assert (<= (to_int (to_real x)) 5)) (assert (>= x 5)) (check-sat)"
        ).problem
        assertEquals(5, p.intDomains[0].min)
        assertEquals(5, p.intDomains[0].max)
    }

    @Test
    fun `real literals are rejected with a clear message`() {
        val ex = assertFailsWith<UnsupportedSmtException> {
            SmtLibQfLia.parse("(declare-const x Int) (assert (<= x 1.5))")
        }
        assertTrue(ex.message!!.contains("real literal"), ex.message!!)
    }

    @Test
    fun `strict bounds errors on an unbounded variable`() {
        val ex = assertFailsWith<UnsupportedSmtException> {
            SmtLibQfLia.parse("(declare-const x Int) (declare-const y Int) (assert (<= x 4))", strictBounds = true)
        }
        assertTrue(ex.message!!.contains("y") || ex.message!!.contains("bound"), ex.message!!)
    }
}
