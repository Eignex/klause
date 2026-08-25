package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmtLibConstructionTest {

    @Test
    fun `an asserted equality constructs its normalized linear row`() {
        val parsed = SmtLib.parse("(declare-const x Int) (assert (= (+ x 2) 5))")

        assertEquals(mapOf("x" to 0), parsed.intVarNames)
        val row = parsed.model.factors.single() as Linear
        assertContentEquals(intArrayOf(0), row.vars)
        assertContentEquals(longArrayOf(1), row.integerConstants!!.coeffs)
        assertEquals(LinearOp.EQ, row.op)
        assertEquals(3L, row.integerConstants!!.bound)
    }

    @Test
    fun `a duplicate declaration is rejected before it can create an unreachable variable`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int) (declare-const x Bool)")
        }

        assertTrue(error.message!!.contains("duplicate declaration of 'x'"))
    }

    @Test
    fun `a malformed command reports its expected argument count`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int unexpected)")
        }

        assertTrue(error.message!!.contains("declare-const expects 2 arguments"))
    }

    @Test
    fun `a malformed ite reports a format error instead of an index failure`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int) (assert (= x (ite true 1)))")
        }

        assertTrue(error.message!!.contains("ite"))
    }

    @Test
    fun `a macro rejects unsupported parameter sorts`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(define-fun f ((x BitVec)) Int x)")
        }

        assertTrue(error.message!!.contains("unsupported sort 'BitVec'"))
    }

    @Test
    fun `an unknown command is rejected instead of being ignored`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int) (asert (= x 1))")
        }

        assertTrue(error.message!!.contains("unsupported command 'asert'"))
    }
}
