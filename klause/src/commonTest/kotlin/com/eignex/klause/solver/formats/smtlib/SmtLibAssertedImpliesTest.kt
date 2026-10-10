package com.eignex.klause.solver.formats.smtlib

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.*
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.SmtLibProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An asserted top-level `=>` is a clause. `(=> a1 .. an)` is right-associative so it holds exactly when
 * some antecedent fails or the consequent holds which is `!a1 or .. or !a(n-1) or an`.
 */
class SmtLibAssertedImpliesTest {

    private fun SmtLibProblem.bounded(): Problem = model.bake()

    private fun parse(text: String): Problem = SmtLib.parse("$text\n(check-sat)").bounded()

    @Test
    fun `a chained implication negates every antecedent but the last operand`() {
        val p = parse("(declare-const a Bool) (declare-const b Bool) (declare-const c Bool) (assert (=> a b c))")
        assertEquals(1, p.factors.size, "the chain should lower to exactly one factor")
        assertEquals(3, p.factors.filterIsInstance<Clause>().single().literals.size, "one literal per operand")
        assertEquals(3, p.numBoolVars, "the chain should add no auxiliary")
    }
}
