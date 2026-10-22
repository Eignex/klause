package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntDomain
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
import kotlin.test.assertTrue

class RealPointHintsTest {
    @Test
    fun `retraction should retain Boolean guesses and discard integer guesses`() {
        val hints = RealPointHints(3, 1, 1, Cancellation.Never)
        hints.replace(intArrayOf(1, -1, 0), longArrayOf(4))
        assertEquals(0, hints.preferredBooleanVariable { true })

        assertTrue(hints.retract())

        assertEquals(1L, hints.booleanValue(0))
        assertNull(hints.integerValue(0))
        assertTrue(hints.needsRefresh)
        assertEquals(2, hints.preferredBooleanVariable { true })
        assertNull(hints.preferredBooleanVariable { true })
    }

    @Test
    fun `replacement should refresh retained guesses and restart variable selection`() {
        val hints = RealPointHints(2, 1, 1, Cancellation.Never)
        hints.replace(intArrayOf(1, 0), longArrayOf(4))
        assertEquals(0, hints.preferredBooleanVariable { true })
        hints.retract()

        hints.replace(intArrayOf(0, 1), longArrayOf(2))

        assertFalse(hints.needsRefresh)
        assertEquals(0L, hints.booleanValue(0))
        assertEquals(2L, hints.integerValue(0))
        assertEquals(0, hints.preferredBooleanVariable { true })
    }

    @Test
    fun `retraction should discard guesses without replacement workspace`() {
        val hints = RealPointHints(32768, 0, 1, Cancellation.Never)
        hints.replace(IntArray(32768), longArrayOf())

        assertFalse(hints.retract())

        assertFalse(hints.available)
        assertTrue(hints.needsRefresh)
        assertNull(hints.booleanValue(0))
        assertNull(hints.preferredBooleanVariable { true })
    }

    @Test
    fun `retained variable proposals should respect containing cancellation`() {
        var stopped = false
        val hints = RealPointHints(1, 0, 1, Cancellation { stopped })
        hints.replace(intArrayOf(1), longArrayOf())
        hints.retract()
        stopped = true

        assertNull(hints.preferredBooleanVariable { true })
    }

    @Test
    fun `point mapping should charge retained guesses against row workspace`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO, activator = 0),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))

        assertNull(realPointHints(source, listOf(BigFraction.ZERO), { null }, Cancellation.Never,
            retainedEntries = 65533))
        assertEquals(1, assertNotNull(realPointHints(source, listOf(BigFraction.ZERO),
            { null }, Cancellation.Never, retainedEntries = 65532))[0])
    }

    @Test
    fun `integer proposals should ceil fractional coordinates within live domain bounds`() {
        val source = Problem(1, 1, arrayOf(IntDomain(-5, 5)), emptyArray(),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val half = BigFraction.ofLong(2).reciprocal()
        val huge = BigFraction.of(BIG_ONE shl 4095, BIG_ONE)
        for ((value, expected) in listOf(half.negated() to 0L, half to 1L, huge to 2L, huge.negated() to -2L)) {
            val hints = integerPointHints(source, listOf(BigFraction.ZERO, value),
                { IntDomain(-2, 2) }, Cancellation.Never)

            assertEquals(listOf(expected), assertNotNull(hints).toList())
        }
    }

    @Test
    fun `integer proposals should withhold stopped or invalid coordinate vectors`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), emptyArray(),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val huge = BigFraction.of(BIG_ONE shl 4096, BIG_ONE)

        assertNull(integerPointHints(source, List(2) { BigFraction.ZERO },
            { IntDomain(0, 1) }, Cancellation { true }))
        for (point in listOf(listOf(BigFraction.ZERO), listOf(BigFraction.ZERO, huge))) {
            assertNull(integerPointHints(source, point, { IntDomain(0, 1) }, Cancellation.Never))
        }
    }

    @Test
    fun `binary channel preferences should round a shared integer point consistently`() {
        val source = Problem(4, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 0L),
            ReifiedLinear(1, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1L),
            ReifiedLinear(2, longArrayOf(1), intArrayOf(0), LinearOp.NE, 0L),
            ReifiedLinear(3, longArrayOf(1), intArrayOf(0), LinearOp.NE, 1L),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val third = BigFraction.ofLong(3).reciprocal()
        for ((point, bit) in listOf(BigFraction.ZERO to 0, third to 0,
            BigFraction.ofLong(2).reciprocal() to 0, (third + third) to 1, BigFraction.ONE to 1)) {

            val hints = realPointHints(source, listOf(BigFraction.ZERO, point), { null }, Cancellation.Never)

            assertEquals(listOf(1 - bit, bit, bit, 1 - bit), assertNotNull(hints).toList())
        }
    }

    @Test
    fun `nonbinary integer preferences should retain exact comparison truth`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) for (bound in listOf(0L, 1L)) {
            val source = Problem(1, 1, arrayOf(IntDomain(0, 2)), arrayOf(
                ReifiedLinear(0, longArrayOf(1), intArrayOf(0), op, bound),
            ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
            val third = BigFraction.ofLong(3).reciprocal()
            var rounded = 0

            val hints = realPointHints(source, listOf(BigFraction.ZERO, third + third),
                { null }, Cancellation.Never, onRoundedBinary = { rounded++ })

            assertEquals(listOf(if (op == LinearOp.EQ) 0 else 1), assertNotNull(hints).toList())
            assertEquals(0, rounded)
        }
    }

    @Test
    fun `rounded channel preferences should not establish an integer witness`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1L),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofIntVar(0)),
                listOf(BigFraction.ONE, BigFraction.ofLong(3)),
                LinearOp.EQ, BigFraction.ofLong(2)),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val third = BigFraction.ofLong(3).reciprocal()
        val point = listOf(BigFraction.ZERO, third + third)

        val hints = realPointHints(source, point, { null }, Cancellation.Never)

        assertEquals(listOf(1), assertNotNull(hints).toList())
        val candidate = Sample(booleanArrayOf(true), longArrayOf(1), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))
        assertFalse(verifyRealCoordinates(source, candidate) is Verification.Accepted<*, *>)
        assertEquals(listOf(BigFraction.ZERO, third + third), point)
    }

    @Test
    fun `stopped binary rounding should withhold all preferences`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1L),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        var stopped = false
        var rounded = 0

        val hints = realPointHints(source, listOf(BigFraction.ZERO, BigFraction.ofLong(2).reciprocal()),
            { null }, Cancellation { stopped }, onRoundedBinary = { rounded++; stopped = true })

        assertNull(hints)
        assertEquals(1, rounded)
    }

    @Test
    fun `mixed guard preferences should use real then integer coordinates without rounding`() {
        val source = Problem(2, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofIntVar(0)),
                listOf(BigFraction.ONE, BigFraction.ofLong(2)), LinearOp.EQ, BigFraction.ZERO, activator = 0),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofIntVar(0)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.GE, BigFraction.ZERO, activator = 1),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val point = listOf(BigFraction.MINUS_ONE, BigFraction.ofLong(2).reciprocal())

        val hints = realPointHints(source, point, { null }, Cancellation.Never)

        assertEquals(listOf(1, 0), assertNotNull(hints).toList())
        assertNull(realPointHints(source, point.take(1), { null }, Cancellation.Never))
    }

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
            ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
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
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))

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
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
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
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))

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
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val huge = BigFraction.of(BIG_ONE shl 4096, BIG_ONE)

        for (point in listOf(emptyList(), listOf(huge))) {
            val hints = realPointHints(source, point, { null }, Cancellation.Never)

            assertNull(hints)
        }
    }
}
