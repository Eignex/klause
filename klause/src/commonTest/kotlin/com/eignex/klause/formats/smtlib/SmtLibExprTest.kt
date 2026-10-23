package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibExprTest {
    private fun parse(text: String): BakedProblem = SmtLib.parse("$text\n(check-sat)").model.bake()

    @Test
    fun `an asserted or should post a single clause over its disjuncts`() {
        val p = parse("(declare-const p Bool) (declare-const q Bool) (declare-const r Bool) (assert (or p q r))")

        assertEquals(1, p.factors.size)
        assertEquals(3, p.factors.filterIsInstance<Clause>().single().literals.size)
    }

    @Test
    fun `an asserted disjunction should post signed conjuncts shared through aliases`() {
        val source = SmtLib.parse(
            """
                (declare-const p Bool) (declare-const q Bool) (declare-const r Bool)
                (assert (let ((shared (not p))) (or (and shared q) (and r shared))))
            """.trimIndent(),
        )
        val common = Lit.make(source.boolVarNames.getValue("p"), false)
        val clauses = source.model.factors.filterIsInstance<Clause>()

        assertTrue(clauses.any { it.literals.contentEquals(intArrayOf(common)) })
        assertEquals(8, clauses.size)
    }

    @Test
    fun `an asserted disjunction should expose its shared exact equality literal`() {
        val source = SmtLib.parse(
            """
                (declare-const x Real) (declare-const p Bool) (declare-const q Bool)
                (assert (let ((shared (= x (/ 1.0 3.0)))) (or (and shared p) (and q shared))))
            """.trimIndent(),
        )
        val comparisons = source.model.factors.filterIsInstance<ExactLinear>()
        val clauses = source.model.factors.filterIsInstance<Clause>()
        val common = clauses.single { it.literals.size == 1 }.literals.single()

        assertEquals(2, comparisons.size)
        for (comparison in comparisons) {
            val side = Lit.make(comparison.activator, true)
            assertTrue(clauses.any { it.literals.contentEquals(intArrayOf(Lit.negate(common), side)) })
        }
    }

    @Test
    fun `common disjunct consequences should preserve every source truth assignment`() {
        for (p in listOf(false, true)) for (q in listOf(false, true)) for (r in listOf(false, true)) {
            val source = parse(
                """
                    (declare-const p Bool) (declare-const q Bool) (declare-const r Bool)
                    (assert (= p $p)) (assert (= q $q)) (assert (= r $r))
                    (assert (let ((shared (not p))) (or (and shared q) (and r shared))))
                """.trimIndent(),
            )

            val result = BacktrackSolver(source).solve()

            if (!p && (q || r)) assertIs<SolveResult.Sat>(result) else assertIs<SolveResult.Unsat>(result)
        }
    }

    @Test
    fun `a conjunction macro should retain its body semantics inside asserted disjuncts`() {
        val source = parse(
            """
                (define-fun and ((left Bool) (right Bool)) Bool right)
                (declare-const p Bool) (declare-const q Bool) (declare-const r Bool)
                (assert (not p)) (assert q)
                (assert (or (and p q) (and p r)))
            """.trimIndent(),
        )

        val result = BacktrackSolver(source).solve()

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `nonconjunctive disjuncts should withhold shared atom consequences`() {
        for (expression in listOf("(and p q) true", "false (and p q)", "(not (and p q)) (and p r)",
            "(and p q) (let ((p true)) (and p r))")) {
            val disjuncts = SExprReader(expression).readAll()

            val common = commonAssertedAtoms(disjuncts, Cancellation.Never)

            assertTrue(common.isEmpty())
        }
    }

    @Test
    fun `common atom scans should decline their entry limit without partial units`() {
        val disjuncts = SExprReader("(and p q) (and r p)").readAll()

        val admitted = commonAssertedAtoms(disjuncts, Cancellation.Never, entryLimit = 21)
        val declined = commonAssertedAtoms(disjuncts, Cancellation.Never, entryLimit = 20)

        assertEquals(listOf("p"), admitted)
        assertTrue(declined.isEmpty())
    }

    @Test
    fun `common atom scans should observe cancellation after a shared prefix`() {
        var charged = 0L
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 12L
            override fun charge(units: Long) { charged += units }
        }
        val disjuncts = SExprReader("(and p q) (and r p)").readAll()

        assertFailsWith<PolynomialSourceInterrupted> {
            commonAssertedAtoms(disjuncts, token)
        }
    }

    @Test
    fun `a chained implication should negate every antecedent but the last operand`() {
        val p = parse("(declare-const a Bool) (declare-const b Bool) (declare-const c Bool) (assert (=> a b c))")

        assertEquals(1, p.factors.size)
        assertEquals(3, p.factors.filterIsInstance<Clause>().single().literals.size)
        assertEquals(3, p.numBoolVars)
    }

    @Test
    fun `directly asserted Boolean bindings should retain outer local values`() {
        val source = parse(
            "(declare-const p Bool) (assert (not p)) " +
                "(assert (let ((p true)) (let ((bound p)) (let ((p false)) bound))))",
        )

        val result = BacktrackSolver(source).solve()

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `reified Boolean bindings should retain their enclosing values`() {
        val source = parse(
            "(declare-const p Bool) (assert (not p)) " +
                "(assert (let ((bound p)) (let ((p true)) (not bound))))",
        )

        val result = BacktrackSolver(source).solve()

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `simultaneous Boolean binding values should exclude their peer bindings`() {
        val source = parse(
            "(declare-const p Bool) (assert p) (assert (let ((p false) (bound p)) bound))",
        )

        val result = BacktrackSolver(source).solve()

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `shared Boolean bindings should retain their value across caller scopes`() {
        val source = parse(
            """
                (declare-const p Bool)
                (assert (not p))
                (assert (let ((bound p))
                    (and (let ((p true)) (not bound)) (let ((p false)) (not bound)))))
            """.trimIndent(),
        )

        val result = BacktrackSolver(source).solve()

        assertIs<SolveResult.Sat>(result)
    }
}
