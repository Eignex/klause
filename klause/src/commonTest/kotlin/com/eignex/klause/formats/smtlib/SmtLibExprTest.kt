package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals

class SmtLibExprTest {
    private fun parse(text: String): Problem = SmtLib.parse("$text\n(check-sat)").model.bake()

    @Test
    fun `an asserted or should post a single clause over its disjuncts`() {
        val p = parse("(declare-const p Bool) (declare-const q Bool) (declare-const r Bool) (assert (or p q r))")

        assertEquals(1, p.factors.size)
        assertEquals(3, p.factors.filterIsInstance<Clause>().single().literals.size)
    }

    @Test
    fun `a chained implication should negate every antecedent but the last operand`() {
        val p = parse("(declare-const a Bool) (declare-const b Bool) (declare-const c Bool) (assert (=> a b c))")

        assertEquals(1, p.factors.size)
        assertEquals(3, p.factors.filterIsInstance<Clause>().single().literals.size)
        assertEquals(3, p.numBoolVars)
    }
}
