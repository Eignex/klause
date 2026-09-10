package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpBoundTrailTest {
    @Test
    fun `weaker active witnesses survive arbitrary backjumps`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(model)
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(1L)), 1L))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 2L))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(zero), 3L))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 4L))
        val deepest = trail.state

        assertTrue(trail.pop(1))

        assertEquals(listOf(1L, 2L), trail.state.assertions.map { it.witness })
        assertEquals(2L, trail.state.activeSide(0, false)?.witness)
        assertEquals(4, deepest.assertions.size)
        assertEquals(4L, deepest.activeSide(0, false)?.witness)
        assertEquals(1, trail.state.depth)
        assertEquals(5L, trail.state.boundRevision)
        assertEquals(1L, trail.state.popRevision)
        assertTrue(trail.pop(0))
        assertEquals(1L, trail.state.activeSide(0, false)?.witness)
    }

    @Test
    fun `strict equal endpoint conflicts retain both exact witnesses`() {
        val zero = ExactLpNumber.of(0L)
        val premise = ExactLpPremises(listOf(ExactLpPremise(7, false, zero)), listOf(5))
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(zero)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(model)
        assertTrue(trail.push())

        assertTrue(trail.assertBound(0, false, ExactLpSide(zero, strict = true, premises = premise), 9L))

        val conflict = assertNotNull(trail.state.conflict)
        assertEquals(9L, conflict.lower.witness)
        assertEquals(-2L, conflict.upper.witness)
        assertEquals(premise, conflict.lower.side.premises)
        assertTrue(conflict.lower.side.strict)
        assertTrue(trail.pop(0))
        assertNull(trail.state.conflict)
        assertNull(trail.state.model.column(0).bounds.lower)
    }

    @Test
    fun `same double bounds select by exact value and never manufacture fixedness`() {
        val low = ExactLpNumber.of(9007199254740992L)
        val high = ExactLpNumber.of(9007199254740993L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(low), ExactLpSide(high)))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(0L))),
        )
        val trail = LpBoundTrail(model)
        assertEquals(low.value.toDouble(), high.value.toDouble())
        assertFalse(trail.state.model.column(0).bounds.fixed)

        assertTrue(trail.assertBound(0, false, ExactLpSide(high), 1L))

        assertTrue(trail.state.model.column(0).bounds.fixed)
        assertEquals(1L, trail.state.activeSide(0, false)?.witness)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, true, ExactLpSide(low), 2L))
        assertEquals(1L, assertNotNull(trail.state.conflict).lower.witness)
        assertEquals(2L, assertNotNull(trail.state.conflict).upper.witness)
    }

    @Test
    fun `equal witnesses keep the oldest strongest premise`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(model)
        assertTrue(trail.assertBound(0, false, ExactLpSide(zero), 1L))
        assertEquals(-1L, trail.state.activeSide(0, false)?.witness)

        assertTrue(trail.assertBound(0, false, ExactLpSide(zero, strict = true), 2L))
        assertTrue(trail.assertBound(0, false, ExactLpSide(zero, strict = true), 3L))

        assertEquals(2L, trail.state.activeSide(0, false)?.witness)
        assertEquals(listOf(1L, 2L, 3L), trail.state.assertions.map { it.witness })
    }

    @Test
    fun `invalid and cancelled operations leave the complete state unchanged`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            listOf(zero),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val trail = LpBoundTrail(model)
        val before = trail.state
        var polls = 0
        val during = Cancellation { ++polls >= 3 }

        assertFalse(trail.assertBound(0, false, ExactLpSide(zero), 1L, during))

        assertSame(before, trail.state)
        assertEquals(3, polls)
        assertFalse(trail.assertBound(1, false, ExactLpSide(zero), 1L))
        assertFalse(trail.assertBound(0, false, ExactLpSide(zero), -1L))
        assertFalse(trail.pop(1))
        assertFalse(trail.recenter(emptyList()))
        assertFalse(trail.replaceObjective(ExactLpObjective(listOf(zero))))
        assertFalse(trail.push(Cancellation { true }))
        assertSame(before, trail.state)
    }

    @Test
    fun `objective replacement and recenter persist through pop with source witnesses`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(ExactLpNumber.of(8L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        val trail = LpBoundTrail(model)
        val premise = ExactLpPremises(listOf(ExactLpPremise(42, false, ExactLpNumber.of(2L))))
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L), premises = premise), 1L))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(4L)), 2L))
        assertTrue(trail.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.of(3L), zero))))

        assertTrue(trail.recenter(listOf(one)))
        assertTrue(trail.pop(0))

        assertEquals(one, trail.state.model.column(0).origin)
        assertEquals(one, trail.state.model.column(0).bounds.lower?.number)
        assertEquals(premise, trail.state.model.column(0).bounds.lower?.premises)
        assertEquals(ExactLpNumber.of(7L), trail.state.model.rhs(0))
        assertEquals(ExactLpNumber.of(3L), trail.state.model.objective.constant)
        assertEquals(ExactLpNumber.of(3L), trail.state.model.objective.cost(0))
        assertEquals(2L, trail.state.objectiveRevision)
    }

    @Test
    fun `IEEE assertions and premises reject changed origins without erasing authority`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val sides = listOf(
            ExactLpSide(ExactLpNumber.ofIeee(0.5)),
            ExactLpSide(zero, premises = ExactLpPremises(listOf(ExactLpPremise(3, true, ExactLpNumber.ofIeee(0.5))))),
        )
        for (side in sides) {
            val trail = LpBoundTrail(model)
            assertTrue(trail.assertBound(0, false, side, 1L))
            val before = trail.state

            assertFalse(trail.recenter(listOf(ExactLpNumber.of(1L))))

            assertSame(before, trail.state)
            assertTrue(trail.recenter(listOf(zero)))
            assertEquals(side, trail.state.assertions.single().side)
        }
    }

    @Test
    fun `overflowing projection rejects assertion before publication`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(model)
        val before = trail.state
        val huge = ExactLpNumber.of(BigFraction.of(BigInteger.ONE shl 2048, BigInteger.ONE))

        assertFalse(trail.assertBound(0, false, ExactLpSide(huge), 1L))

        assertSame(before, trail.state)
    }
}
