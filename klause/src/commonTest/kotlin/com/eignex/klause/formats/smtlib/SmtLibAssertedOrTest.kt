package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An asserted top-level `or` is a clause. Posting it directly states the same thing as reifying it and
 * forcing the auxiliary true, without allocating that auxiliary or the implications defining it.
 */
class SmtLibAssertedOrTest {

    private fun SmtLibProblem.bounded(): Problem =
        deferredBounds?.run(Cancellation.Never)?.let { problem.withIntDomains(it.domains) } ?: problem

    private fun parse(text: String): Problem = SmtLib.parse("$text\n(check-sat)").bounded()

    private fun sat(text: String): Boolean =
        BacktrackSolver(parse(text).bake()).solve(BacktrackParams()) is SolveResult.Sat

    @Test
    fun `an asserted or posts a single clause over its disjuncts`() {
        val p = parse("(declare-const p Bool) (declare-const q Bool) (declare-const r Bool) (assert (or p q r))")
        val clauses = p.factors.filterIsInstance<Clause>()
        assertEquals(1, p.factors.size, "the disjunction should lower to exactly one factor")
        assertEquals(3, clauses.single().literals.size, "the clause should carry one literal per disjunct")
    }

    @Test
    fun `an asserted or allocates no auxiliary literal`() {
        val p = parse("(declare-const p Bool) (declare-const q Bool) (assert (or p q))")
        assertEquals(2, p.numBoolVars, "only the declared bools should exist")
    }

    @Test
    fun `an asserted or still refutes the assignment that falsifies every disjunct`() {
        val text = "(declare-const p Bool) (declare-const q Bool) (assert (or p q)) (assert (not p)) (assert (not q))"
        assertTrue(!sat(text), "no disjunct can hold so the model must be refuted")
    }

    @Test
    fun `an asserted or over comparisons accepts exactly the stated values`() {
        val decl = "(declare-const x Int) (assert (>= x 0)) (assert (<= x 5))"
        val parsed = parse("$decl (assert (or (= x 1) (= x 4)))")
        val accepted = (0L..5L).filter { v ->
            val domains = Array(parsed.numIntVars) { i -> if (i == 0) IntDomain(v, v) else parsed.intDomains[i] }
            BacktrackSolver(parsed.withIntDomains(domains).bake()).solve(BacktrackParams()) is SolveResult.Sat
        }
        assertEquals(listOf(1L, 4L), accepted, "only the disjoined values should survive")
    }

    @Test
    fun `a nested or under an asserted and is posted as its own clause`() {
        val p = parse("(declare-const a Bool) (declare-const b Bool) (declare-const c Bool) (assert (and c (or a b)))")
        val clauses = p.factors.filterIsInstance<Clause>()
        assertEquals(3, p.numBoolVars, "the conjunction and disjunction should add no auxiliary")
        assertTrue(clauses.any { it.literals.size == 2 }, "the disjunction should appear as a binary clause")
    }

    @Test
    fun `an empty or is unsatisfiable`() {
        assertTrue(!sat("(declare-const p Bool) (assert (or))"), "an empty disjunction is false")
    }
}
