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

class LpScopedRowsTest {
    @Test
    fun `persistent rows survive interleaved scopes and compaction preserves exact source maps`() {
        val zero = ExactLpNumber.of(0L)
        val third = ExactLpNumber.of(BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3)))
        val ieee = ExactLpNumber.ofIeee(-0.0)
        val premises = ExactLpPremises(listOf(ExactLpPremise(42, false, third)), listOf(17))
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(), third, false, 42)),
            emptyList(),
            ExactLpObjective(listOf(third), third, third, third, Sense.MAXIMIZE),
        )
        val trail = LpBoundTrail(source)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(third)), tag = 71)
        assertTrue(trail.push())
        assertTrue(trail.append(LpScopedRow(10, listOf(0 to third), third, logical), scoped = true))
        assertTrue(
            trail.append(
                LpScopedRow(
                    11,
                    listOf(0 to ieee),
                    third,
                    logical,
                    ExactLpRow(false, premises = premises),
                    third,
                ),
                scoped = false,
            ),
        )
        assertTrue(trail.assertBound(2, true, ExactLpSide(zero, premises = premises), 101))
        assertTrue(trail.push())
        assertTrue(trail.append(LpScopedRow(12, listOf(0 to third), third, logical), scoped = true))
        assertTrue(trail.append(LpScopedRow(13, listOf(0 to third), ieee, logical), scoped = false))

        assertTrue(trail.pop(1))
        assertEquals(listOf(10L, 11L, 13L), trail.state.rows.entries().filter { it.active }.map { it.id })
        assertTrue(trail.pop(0))
        assertEquals(listOf(11L, 13L), trail.state.rows.entries().filter { it.active }.map { it.id })
        val before = trail.state
        val remap = LpRowRemap(1, before.rows)
        assertTrue(trail.compact())

        assertEquals(-1, remap.row(0))
        assertEquals(0, remap.row(1))
        assertEquals(1, remap.column(2))
        assertEquals(0, remap.column(0))
        assertEquals(listOf(11L, 13L), trail.state.rows.entries().map { it.id })
        assertEquals(13L, trail.state.rows.lastId)
        assertEquals(ieee, trail.state.baseModel.entries(0)[0].number)
        assertEquals(third, trail.state.baseModel.rhs(0))
        assertEquals(ieee, trail.state.baseModel.rhs(1))
        assertEquals(premises, trail.state.baseModel.row(0).premises)
        assertFalse(trail.state.baseModel.row(0).global)
        assertEquals(logical, trail.state.baseModel.column(1))
        assertEquals(source.column(0), trail.state.baseModel.column(0))
        assertEquals(third, trail.state.model.objective.cost(1))
        assertEquals(third, trail.state.model.objective.constant)
        assertEquals(third, trail.state.model.objective.scale)
        assertEquals(third, trail.state.model.objective.externalConstant)
        assertEquals(Sense.MAXIMIZE, trail.state.model.objective.sense)
        assertFalse(trail.append(LpScopedRow(12, emptyList(), zero, logical), scoped = true))
        assertFalse(before.sameMatrix(trail.state))
        assertEquals(4, before.model.m)
    }

    @Test
    fun `deactivation removes both sides strictness and integer restrictions from every exact reader`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val trail = LpBoundTrail(source)
        val row = LpScopedRow(
            7,
            listOf(0 to one),
            zero,
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ExactLpRow(strict = true),
        )
        assertTrue(trail.append(row, scoped = false))
        val active = trail.state
        assertNotNull(active.conflict)
        assertFalse(active.model.column(1).bounds.fixed)

        assertTrue(trail.deactivate(7))

        val model = assertNotNull(trail.state.toWorkingModel())
        assertEquals(ExactLpBounds(), model.exactBounds(1))
        assertFalse(model.hasFiniteLower(1))
        assertFalse(model.hasFiniteUpper(1))
        assertFalse(model.rowStrict[0])
        assertFalse(trail.state.model.column(1).integral)
        assertNull(trail.state.activeSide(1, false))
        assertNull(trail.state.activeSide(1, true))
        assertNull(trail.state.conflict)
        assertTrue(active.sameMatrix(trail.state))
        assertNotNull(checkedLpWitness(model, listOf(BigFraction.ZERO)))
        assertNotNull(checkedLpWitness(model, listOf(BigFraction.ONE)))
        assertNull(exactLagrangian(model, listOf(BigFraction.ONE)))
        assertFalse(sourceFarkasValid(model, longArrayOf(1)))
        assertNull(integerCertify(model, doubleArrayOf(1.0)))
        assertTrue(trail.state.baseModel.row(0).strict)
        assertTrue(trail.state.baseModel.column(1).integral)
    }

    @Test
    fun `priced logicals and surviving assertions block removal until explicitly released`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(
            trail.append(
                LpScopedRow(
                    1,
                    listOf(0 to one),
                    one,
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                    cost = one,
                ),
                scoped = true,
            ),
        )
        val priced = trail.state
        assertFalse(trail.pop(0))
        assertFalse(trail.deactivate(1))
        assertSame(priced, trail.state)
        assertTrue(trail.replaceObjective(ExactLpObjective(listOf(one, zero))))
        assertTrue(trail.assertBound(1, true, ExactLpSide(one), 8))
        val asserted = trail.state
        assertFalse(trail.deactivate(1))
        assertSame(asserted, trail.state)

        assertTrue(trail.pop(0))

        assertTrue(trail.state.assertions.isEmpty())
        assertFalse(trail.state.rows.row(0).active)
        assertFalse(trail.assertBound(1, false, ExactLpSide(zero), 9))
        assertFalse(trail.replaceObjective(ExactLpObjective(listOf(one, one))))
        assertTrue(trail.compact())
        assertEquals(one, trail.state.model.objective.cost(0))
    }

    @Test
    fun `logical assertions remap and keep coordinates when structural columns recenter`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val trail = LpBoundTrail(source)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        assertTrue(trail.append(LpScopedRow(3, listOf(0 to one), one, logical), scoped = false))
        assertTrue(trail.append(LpScopedRow(4, listOf(0 to one), one, logical), scoped = false))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(2, true, ExactLpSide(one), 77))
        assertTrue(trail.assertBound(2, true, ExactLpSide(ExactLpNumber.of(2L)), 78))
        assertTrue(trail.deactivate(3))

        assertTrue(trail.compact())
        assertTrue(trail.recenter(listOf(one)))

        assertEquals(listOf(1, 1), trail.state.assertions.map { it.column })
        assertEquals(listOf(77L, 78L), trail.state.assertions.map { it.witness })
        assertEquals(one, trail.state.assertions.first().side.number)
        assertEquals(zero, trail.state.baseModel.rhs(0))
        assertEquals(one, trail.state.model.objective.constant)
        assertEquals(listOf(0), trail.state.scopes)
        assertTrue(trail.pop(0))
        assertTrue(trail.state.assertions.isEmpty())
        assertEquals(4L, trail.state.rows.row(0).id)
    }

    @Test
    fun `invalid cancelled overflowing and duplicate appends retain the original state`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        val logical = ExactLpColumn(ExactLpBounds())
        val huge = ExactLpNumber.of(BigFraction.of(BigInteger.ONE shl 2048, BigInteger.ONE))
        val tiny = ExactLpNumber.of(BigFraction.of(BigInteger.ONE, BigInteger.ONE shl 2048))
        val cases = listOf(
            LpScopedRow(-1, emptyList(), zero, logical),
            LpScopedRow(0, listOf(1 to one), zero, logical),
            LpScopedRow(0, listOf(0 to one, 0 to one), zero, logical),
            LpScopedRow(0, listOf(0 to tiny), zero, logical),
            LpScopedRow(0, listOf(0 to one), huge, logical),
            LpScopedRow(0, emptyList(), zero, logical, ExactLpRow(strict = true)),
            LpScopedRow(0, emptyList(), zero, logical.copy(origin = one)),
        )
        for (row in cases) {
            val trail = LpBoundTrail(source)
            val before = trail.state
            assertFalse(trail.append(row, scoped = false))
            assertSame(before, trail.state)
        }
        val row = LpScopedRow(Long.MAX_VALUE, listOf(0 to one), zero, logical)
        val trail = LpBoundTrail(source)
        val before = trail.state
        var polls = 0
        assertFalse(trail.append(row, false, Cancellation { ++polls >= 3 }))
        assertSame(before, trail.state)
        assertTrue(trail.append(row, scoped = false))
        assertTrue(trail.deactivate(row.id))
        assertTrue(trail.compact())
        assertFalse(trail.append(row, scoped = false))
        for (state in listOf(
            LpExactState(source, matrixRevision = Long.MAX_VALUE),
            LpExactState(source, boundRevision = Long.MAX_VALUE),
            LpExactState(source, objectiveRevision = Long.MAX_VALUE),
            LpExactState(source, rowRevision = Long.MAX_VALUE),
        )) {
            val exhausted = LpBoundTrail(state)
            assertFalse(exhausted.append(row, scoped = false))
            assertSame(state, exhausted.state)
        }
    }
}
