package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An asserted top-level `=>` is a clause. `(=> a1 .. an)` is right-associative so it holds exactly when
 * some antecedent fails or the consequent holds which is `!a1 or .. or !a(n-1) or an`.
 */
class SmtLibAssertedImpliesTest {

    private fun SmtLibProblem.bounded(): Problem =
        deferredBounds?.run(Cancellation.Never)?.let { problem.withIntDomains(it.domains) } ?: problem

    private fun parse(text: String): Problem = SmtLib.parse("$text\n(check-sat)").bounded()

    private fun sat(text: String): Boolean =
        BacktrackSolver(parse(text).bake()).solve(BacktrackParams()) is SolveResult.Sat

    @Test
    fun `an asserted implication posts a single binary clause`() {
        val p = parse("(declare-const a Bool) (declare-const b Bool) (assert (=> a b))")
        assertEquals(1, p.factors.size, "the implication should lower to exactly one factor")
        assertEquals(2, p.factors.filterIsInstance<Clause>().single().literals.size, "one literal per operand")
        assertEquals(2, p.numBoolVars, "only the declared bools should exist")
    }

    @Test
    fun `a chained implication negates every antecedent but the last operand`() {
        val p = parse("(declare-const a Bool) (declare-const b Bool) (declare-const c Bool) (assert (=> a b c))")
        assertEquals(1, p.factors.size, "the chain should lower to exactly one factor")
        assertEquals(3, p.factors.filterIsInstance<Clause>().single().literals.size, "one literal per operand")
        assertEquals(3, p.numBoolVars, "the chain should add no auxiliary")
    }

    @Test
    fun `an implication with a true antecedent forces its consequent`() {
        assertTrue(!sat("(declare-const a Bool) (declare-const b Bool) (assert (=> a b)) (assert a) (assert (not b))"))
        assertTrue(sat("(declare-const a Bool) (declare-const b Bool) (assert (=> a b)) (assert a) (assert b)"))
    }

    @Test
    fun `an implication with a false antecedent constrains nothing`() {
        val text = "(declare-const a Bool) (declare-const b Bool) (assert (=> a b)) (assert (not a)) (assert (not b))"
        assertTrue(sat(text), "a failed antecedent leaves the consequent free")
    }

    @Test
    fun `an implication over comparisons keeps its meaning`() {
        val decl = "(declare-const x Int) (assert (>= x 0)) (assert (<= x 5))"
        assertTrue(!sat("$decl (assert (=> (>= x 3) (<= x 1))) (assert (= x 4))"), "4 satisfies only the antecedent")
        assertTrue(sat("$decl (assert (=> (>= x 3) (<= x 1))) (assert (= x 0))"), "0 fails the antecedent")
    }

    /** An atom over the two bools and the one integer of [PREAMBLE] paired with its own semantics. */
    private class Atom(val text: String, val holds: (Boolean, Boolean, Long) -> Boolean)

    private companion object {
        const val PREAMBLE =
            "(declare-const p Bool) (declare-const q Bool) (declare-const x Int) " +
                "(assert (>= x 0)) (assert (<= x 3))"

        val ATOMS = listOf(
            Atom("p") { p, _, _ -> p },
            Atom("(not p)") { p, _, _ -> !p },
            Atom("q") { _, q, _ -> q },
            Atom("(not q)") { _, q, _ -> !q },
            Atom("(= x 1)") { _, _, x -> x == 1L },
            Atom("(<= x 1)") { _, _, x -> x <= 1L },
            Atom("(>= x 2)") { _, _, x -> x >= 2L },
        )
    }

    /** Whether some assignment satisfies both `a implies b` and [c]. */
    private fun brute(a: Atom, b: Atom, c: Atom): Boolean {
        for (p in listOf(false, true)) {
            for (q in listOf(false, true)) {
                for (x in 0L..3L) {
                    if ((!a.holds(p, q, x) || b.holds(p, q, x)) && c.holds(p, q, x)) return true
                }
            }
        }
        return false
    }

    /**
     * The clause posting has to mean exactly what the reified implication meant. Every ordered operand
     * pair drawn from [ATOMS] is asserted against every third atom as a side condition and the verdict
     * compared with an enumeration of the whole assignment space. Ordered rather than unordered because
     * an implication is not symmetric so a dropped negation would survive an unordered sweep.
     */
    @Test
    fun `an asserted implication agrees with an enumeration of the assignment space`() {
        var checked = 0
        for (a in ATOMS) {
            for (b in ATOMS) {
                if (a === b) continue
                for (c in ATOMS) {
                    val text = "$PREAMBLE (assert (=> ${a.text} ${b.text})) (assert ${c.text})"
                    assertEquals(brute(a, b, c), sat(text), "disagreed on (=> ${a.text} ${b.text}) with ${c.text}")
                    checked++
                }
            }
        }
        assertEquals(294, checked, "every ordered operand pair should meet every side condition")
    }
}
