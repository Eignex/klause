package com.eignex.klause.solver.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.formats.smtlib.*
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SMT-LIB chainable relations: `(op a1 … an)` is `a1 op a2 ∧ … ∧ a(n-1) op an` — consecutive pairs
 * only, with no direct relation between non-adjacent operands.
 */
class SmtLibChainTest {

    private companion object {
        const val VARS = 3
        const val SIZE = 3
        val ORDER_OPS = listOf("<", "<=", ">", ">=")
    }

    private fun SmtLibProblem.bounded(): Problem = model.materializeFiniteBounds()

    /** `k` integers over `[0, size)`, with [tail] appended as the closing assertions. */
    private fun boundedInts(k: Int, size: Int, tail: String): String {
        val decls = (0 until k).joinToString("\n") {
            "(declare-const x$it Int) (assert (>= x$it 0)) (assert (<= x$it ${size - 1}))"
        }
        return "$decls\n$tail\n(check-sat)"
    }

    private fun names(k: Int): String = (0 until k).joinToString(" ") { "x$it" }

    /** Every tuple over `[0, SIZE)` for the first [VARS] variables that [text] accepts, found by pinning
     *  each variable to a singleton domain and reading the solver's verdict. */
    private fun accepted(text: String): Set<List<Long>> {
        val parsed = SmtLib.parse(text).model.materializeFiniteBounds()
        val out = HashSet<List<Long>>()
        for (code in 0 until SIZE * SIZE * SIZE) {
            val tuple = List(VARS) { ((code / pow(SIZE, it)) % SIZE).toLong() }
            val domains = Array(parsed.numIntVars) { v ->
                if (v < VARS) IntDomain(tuple[v], tuple[v]) else parsed.requireFiniteIntDomains()[v]
            }
            val r = BacktrackSolver(parsed.withIntDomains(domains).bake()).solve(BacktrackParams())
            if (r is SolveResult.Sat) out.add(tuple)
        }
        return out
    }

    private fun pow(base: Int, exp: Int): Int {
        var p = 1
        repeat(exp) { p *= base }
        return p
    }

    private fun holds(op: String, a: Long, b: Long): Boolean = when (op) {
        "<" -> a < b
        "<=" -> a <= b
        ">" -> a > b
        else -> a >= b
    }

    /** The consecutive-pair expansion of `op` over every tuple, as the oracle the encoding must match. */
    private fun pairwise(op: String, negated: Boolean = false): Set<List<Long>> {
        val out = HashSet<List<Long>>()
        for (code in 0 until SIZE * SIZE * SIZE) {
            val tuple = List(VARS) { ((code / pow(SIZE, it)) % SIZE).toLong() }
            val chain = (0 until VARS - 1).all { holds(op, tuple[it], tuple[it + 1]) }
            if (chain != negated) out.add(tuple)
        }
        return out
    }

    @Test
    fun `an asserted chain accepts exactly the consecutive pair expansion`() {
        for (op in ORDER_OPS) {
            val text = boundedInts(VARS, SIZE, "(assert ($op ${names(VARS)}))")
            assertEquals(pairwise(op), accepted(text), "chain '$op' differs from its pairwise expansion")
        }
    }

    @Test
    fun `a reified chain held true accepts exactly the consecutive pair expansion`() {
        for (op in ORDER_OPS) {
            val text = boundedInts(VARS, SIZE, "(assert (or false ($op ${names(VARS)})))")
            assertEquals(pairwise(op), accepted(text), "reified chain '$op' differs from its pairwise expansion")
        }
    }

    @Test
    fun `a negated chain accepts exactly the complement of the consecutive pair expansion`() {
        for (op in ORDER_OPS) {
            val text = boundedInts(VARS, SIZE, "(assert (not ($op ${names(VARS)})))")
            assertEquals(
                pairwise(op, negated = true),
                accepted(text),
                "negated chain '$op' is not the complement of its pairwise expansion",
            )
        }
    }

    @Test
    fun `a chain over compound operands accepts exactly its consecutive pair expansion`() {
        val text = boundedInts(VARS, SIZE, "(assert (< x0 (+ x1 1) x2))")
        val expected = HashSet<List<Long>>()
        for (code in 0 until SIZE * SIZE * SIZE) {
            val t = List(VARS) { ((code / pow(SIZE, it)) % SIZE).toLong() }
            if (t[0] < t[1] + 1 && t[1] + 1 < t[2]) expected.add(t)
        }
        assertEquals(expected, accepted(text), "compound-operand chain differs from its pairwise expansion")
    }

    @Test
    fun `an ascending chain over bare variables posts one increasing factor`() {
        val text = boundedInts(VARS, SIZE, "(assert (< ${names(VARS)}))")
        val factors = SmtLib.parse(text).model.factors.filterIsInstance<Increasing>()
        assertEquals(1, factors.size, "expected one Increasing factor")
        assertTrue(factors[0].strict, "'<' is a strict chain")
        assertContentEquals(intArrayOf(0, 1, 2), factors[0].xs, "ascending chain keeps the operand order")
    }

    @Test
    fun `a descending chain over bare variables reverses the increasing factor`() {
        val text = boundedInts(VARS, SIZE, "(assert (>= ${names(VARS)}))")
        val factors = SmtLib.parse(text).model.factors.filterIsInstance<Increasing>()
        assertEquals(1, factors.size, "expected one Increasing factor")
        assertTrue(!factors[0].strict, "'>=' is a non-strict chain")
        assertContentEquals(intArrayOf(2, 1, 0), factors[0].xs, "descending chain reverses the operand order")
    }

    @Test
    fun `a strict chain repeating a variable is unsatisfiable`() {
        val text = boundedInts(2, SIZE, "(assert (< x0 x0 x1))")
        val r = BacktrackSolver(SmtLib.parse(text).bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
    }

    @Test
    fun `a non-strict chain repeating a variable stays satisfiable`() {
        val text = boundedInts(2, SIZE, "(assert (<= x0 x0 x1))")
        val r = BacktrackSolver(SmtLib.parse(text).bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        assertTrue(r.assignment.ints[0] <= r.assignment.ints[1], "the chain must still order the two variables")
    }

    @Test
    fun `an n-ary equality chain forces every operand equal`() {
        val text = boundedInts(VARS, SIZE, "(assert (= ${names(VARS)})) (assert (>= x0 2))")
        val r = BacktrackSolver(SmtLib.parse(text).bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        assertEquals(listOf(2L, 2L, 2L), (0 until VARS).map { r.assignment.ints[it] })
    }

    @Test
    fun `a constant-bounded chain tightens the variable domain`() {
        val p = SmtLib.parse("(declare-const x Int) (assert (<= 3 x 7)) (check-sat)").bounded()
        assertEquals(3, p.requireFiniteIntDomains()[0].min)
        assertEquals(7, p.requireFiniteIntDomains()[0].max)
    }

    @Test
    fun `a mixed real chain solves strictly inside its interval`() {
        assertRealChainInterval("(assert (< (to_real n) x 2.5))")
    }

    @Test
    fun `a reified mixed real chain held true solves strictly inside its interval`() {
        assertRealChainInterval("(assert (or false (< (to_real n) x 2.5)))")
    }

    private fun assertRealChainInterval(assertion: String) {
        val text = """
            (declare-const x Real) (declare-const n Int)
            (assert (>= n 1)) (assert (<= n 3))
            $assertion
            (check-sat)
        """.trimIndent()
        val r = BacktrackSolver(SmtLib.parse(text).bounded().bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Sat, "expected SAT, got $r")
        val n = r.assignment.ints[0]
        val x = r.assignment.reals[0]
        assertTrue(x > n.toDouble() && x < 2.5, "x=$x outside (n=$n, 2.5)")
    }
}
