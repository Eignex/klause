package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpExactStateTest {
    @Test
    fun `working projections preserve rational authority and source metadata`() {
        val zero = ExactLpNumber.of(0L)
        val third = ExactLpNumber.of(BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3)))
        val premises = ExactLpPremises(listOf(ExactLpPremise(8, true, third)))
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, third))),
            listOf(third),
            listOf(
                ExactLpColumn(ExactLpBounds(upper = ExactLpSide(third, strict = true)), third, false, 42),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow(false, true, premises)),
            ExactLpObjective(listOf(third, zero), third, third, third, Sense.MAXIMIZE),
        )
        val state = LpExactState(model)

        val working = assertNotNull(state.toWorkingModel())

        assertSame(state, working.exactState)
        assertTrue(model.sameAuthority(assertNotNull(working.exactState).model))
        assertEquals(1.0 / 3.0, working.costD(0))
        assertEquals(1.0 / 3.0, working.rhsD(0))
        assertEquals(1.0 / 3.0, working.loShiftD(0))
        assertEquals(0L, working.cost[0])
        assertEquals(0L, working.rhs[0])
        assertEquals(0L, working.csc.colVal[0])
        assertEquals(42, working.tag[0])
        assertTrue(working.colContinuous[0])
        assertFalse(working.rowGlobal[0])
        assertTrue(working.rowStrict[0])
        assertEquals(Sense.MAXIMIZE, working.sense)
    }

    @Test
    fun `nonzero matrix and cost underflow decline instead of changing the problem`() {
        val zero = ExactLpNumber.of(0L)
        val tiny = ExactLpNumber.of(BigFraction.of(BigInteger.ONE, BigInteger.ONE shl 2048))
        for (matrixUnderflow in listOf(false, true)) {
            val model = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, if (matrixUnderflow) tiny else zero))),
                listOf(zero),
                listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds())),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(if (matrixUnderflow) zero else tiny, zero)),
            )

            assertNull(LpExactState(model).toWorkingModel())
        }
    }

    @Test
    fun `IEEE subnormal coefficients project from input bits`() {
        val zero = ExactLpNumber.ofIeee(-0.0)
        val tiny = ExactLpNumber.ofIeee(Double.MIN_VALUE)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, tiny))),
            listOf(tiny),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero))), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(tiny, zero)),
        )

        val working = assertNotNull(LpExactState(model).toWorkingModel())

        assertEquals(Double.MIN_VALUE, working.costD(0))
        assertEquals(Double.MIN_VALUE, working.rhsD(0))
        assertEquals(Double.MIN_VALUE, assertNotNull(working.doubleView).colVal.single())
        assertEquals((-0.0).toRawBits(), working.costD(1).toRawBits())
        assertEquals((-0.0).toRawBits(), model.column(0).bounds.lower?.number?.ieeeBits)
    }

    @Test
    fun `snapshots own assertion and scope collections`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val assertions = mutableListOf(LpBoundAssertion(0, false, ExactLpSide(zero), 7L, 1))
        val scopes = mutableListOf(0)
        val state = LpExactState(model, assertions, scopes, boundRevision = 1L)

        assertions.clear()
        scopes.clear()

        assertEquals(7L, state.assertions.single().witness)
        assertEquals(listOf(0), state.scopes)
        assertEquals(1, state.depth)
        assertEquals(zero, state.model.column(0).bounds.lower?.number)
    }

    @Test
    fun `empty scopes and weaker witnesses distinguish otherwise equal boxes`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(model)
        val root = trail.state
        assertTrue(trail.push())
        val pushed = trail.state
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(-1L)), 1L))

        assertFalse(root.fullAuthorityEquals(pushed))
        assertFalse(pushed.fullAuthorityEquals(trail.state))
        assertTrue(root.model.sameAuthority(trail.state.model))
        assertTrue(root.sameMatrix(trail.state))
        assertTrue(trail.pop(0))
        assertFalse(root.fullAuthorityEquals(trail.state))
        assertTrue(root.model.sameAuthority(trail.state.model))
    }

    @Test
    fun `matrix compatibility includes exact input identity and row premises`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val state = LpExactState(model)
        val ieee = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.ofIeee(1.0)))),
            listOf(one),
            List(2) { model.column(it) },
            listOf(model.row(0)),
            model.objective,
        )

        assertFalse(state.sameMatrix(LpExactState(ieee)))
        assertFalse(state.sameMatrix(LpExactState(model.copy(rows = listOf(ExactLpRow(global = false))))))
        assertTrue(state.sameMatrix(LpExactState(model.recentered(listOf(one)))))
        assertTrue(state.sameMatrix(LpExactState(model.copy(objective = ExactLpObjective(listOf(one, one))))))
    }
}
