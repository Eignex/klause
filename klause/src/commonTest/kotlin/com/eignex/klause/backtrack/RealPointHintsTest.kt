package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RealPointHintsTest {
    @Test
    fun `comparison preferences should preserve strict and exact boundaries`() {
        val cases = listOf(
            Triple(LinearOp.LE, false, listOf(1, 1, 0)),
            Triple(LinearOp.LE, true, listOf(1, 0, 0)),
            Triple(LinearOp.GE, false, listOf(0, 1, 1)),
            Triple(LinearOp.GE, true, listOf(0, 0, 1)),
            Triple(LinearOp.EQ, false, listOf(0, 1, 0)),
            Triple(LinearOp.NE, false, listOf(1, 0, 1)),
        )
        for ((op, strict, expected) in cases) {
            val source = Problem(1, 0, emptyArray(), arrayOf(
                ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                    op, BigFraction.ZERO, strict = strict, activator = 0),
            ), numRealVars = 1)
            for ((index, value) in listOf(-1L, 0L, 1L).withIndex()) {
                val hints = realPointHints(source, listOf(BigFraction.ofLong(value)), { null }, Cancellation.Never)

                assertEquals(expected[index], assertNotNull(hints)[0])
            }
        }
    }

    @Test
    fun `disagreeing shared heads should preserve independent guard preferences`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.GE, BigFraction.ONE, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ZERO, activator = 1),
        ), numRealVars = 1)

        val hints = realPointHints(source, listOf(BigFraction.ZERO), { null }, Cancellation.Never)

        assertEquals(listOf(-2, 1), assertNotNull(hints).toList())
    }

    @Test
    fun `guard preferences should leave unconditional source checking to completion`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.GE, BigFraction.ONE),
        ), numRealVars = 1)
        val point = listOf(BigFraction.ZERO)

        val hints = realPointHints(source, point, { null }, Cancellation.Never)

        assertEquals(1, assertNotNull(hints)[0])
        val candidate = Sample(booleanArrayOf(true), longArrayOf(), doubleArrayOf(0.0), exactReals = point)
        assertFalse(verifyRealCoordinates(source, candidate) is Verification.Accepted<*, *>)
    }

    @Test
    fun `stopped point evaluation should withhold all preferences`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO, activator = 0),
        ), numRealVars = 1)

        val hints = realPointHints(source, listOf(BigFraction.ZERO), { null }, Cancellation { true })

        assertNull(hints)
        assertEquals(1, assertNotNull(realPointHints(source, listOf(BigFraction.ZERO),
            { null }, Cancellation.Never))[0])
    }

    @Test
    fun `invalid point dimensions or scalar widths should withhold preferences`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO, activator = 0),
        ), numRealVars = 1)
        val huge = BigFraction.of(BIG_ONE shl 4096, BIG_ONE)

        for (point in listOf(emptyList(), listOf(huge))) {
            val hints = realPointHints(source, point, { null }, Cancellation.Never)

            assertNull(hints)
        }
    }
}
