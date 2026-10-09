package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpScopedRowsTest {
    @Test
    fun `layout compaction remaps assertions and keeps suspended ancestor coordinates`() {
        val source = LpBuilder().apply {
            addVar(0L, 4L, cost = 1L)
            addVar(0L, 4L)
            addVar(0L, 4L)
        }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val two = ExactLpNumber.of(2L)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        val premise = ExactLpPremises(emptyList(), listOf(41))
        assertTrue(trail.assertBound(1, false, ExactLpSide(one), 10L))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(one, premises = premise), 11L))
        assertTrue(trail.assertBound(1, true, ExactLpSide(two), 12L))
        assertTrue(trail.append(LpScopedRow(0L, listOf(2 to one), two, logical), true))
        assertTrue(trail.push())
        assertTrue(trail.suspend(setOf(0L)))
        assertTrue(trail.append(LpScopedRow(1L, listOf(0 to one), two, logical), true))
        assertTrue(trail.append(LpScopedRow(2L, listOf(1 to one), two, logical), true))
        assertTrue(trail.suspend(setOf(2L)))
        assertTrue(trail.assertBound(4, true, ExactLpSide(one), 13L))
        val before = trail.state
        val remap = LpLayoutRemap(3, before.rows, listOf(0, 2))

        assertTrue(trail.compact(remap))

        assertEquals(2, trail.state.model.n)
        assertEquals(listOf(0L, 1L), trail.state.rows.entries().map { it.id })
        assertEquals(listOf(0, 1), trail.state.scopes)
        assertEquals(listOf(11L, 13L), trail.state.assertions.map { it.witness })
        assertEquals(listOf(0, 3), trail.state.assertions.map { it.column })
        assertEquals(premise, trail.state.activeSide(0, false)?.side?.premises)
        assertEquals(before.model.column(2), trail.state.model.column(1))
        assertFalse(trail.state.rows.row(0).active)
        assertEquals(listOf(ExactLpEntry(0, one)), trail.state.model.entries(1))
        assertTrue(trail.pop(1))
        assertTrue(trail.state.rows.row(0).active)
        assertFalse(trail.state.rows.row(1).active)
        assertEquals(listOf(11L), trail.state.assertions.map { it.witness })
        assertTrue(trail.pop(0))
        assertTrue(trail.state.assertions.isEmpty())
        assertEquals(zero, trail.state.model.column(0).bounds.lower?.number)
    }

    @Test
    fun `column removal preserves feasibility in strict shifted and rational integer intervals`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val half = ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(2)))
        val quarter = ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(4)))
        val huge = ExactLpNumber.of(BigFraction.of(bigIntOf(Long.MAX_VALUE) * bigIntOf(16), BIG_ONE))
        val variants = listOf(
            ExactLpColumn(ExactLpBounds(ExactLpSide(quarter), ExactLpSide(half))) to false,
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(half.value.negated())),
                ExactLpSide(ExactLpNumber.of(quarter.value.negated())))) to false,
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(-1L), true), ExactLpSide(zero, true))) to false,
            ExactLpColumn(ExactLpBounds(ExactLpSide(quarter), ExactLpSide(half)), integral = false) to true,
            ExactLpColumn(ExactLpBounds(ExactLpSide(half), ExactLpSide(half)), origin = half) to true,
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), origin = half) to false,
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero, true), ExactLpSide(one, true))) to false,
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero, true), ExactLpSide(ExactLpNumber.of(2L), true))) to true,
            ExactLpColumn(ExactLpBounds(ExactLpSide(one), ExactLpSide(zero))) to false,
            ExactLpColumn(ExactLpBounds(ExactLpSide(huge), ExactLpSide(huge))) to true,
            ExactLpColumn(ExactLpBounds(ExactLpSide(quarter, true))) to true,
        )
        for ((column, removable) in variants) {
            val source = ExactLpModel(listOf(emptyList()), emptyList(), listOf(column), emptyList(),
                ExactLpObjective(listOf(zero)))
            val trail = LpBoundTrail(source)
            val before = trail.state

            val accepted = trail.compact(LpLayoutRemap(1, before.rows, emptyList()))

            assertEquals(removable, accepted)
            if (removable) assertEquals(0, trail.state.model.n) else assertSame(before, trail.state)
        }
    }

    @Test
    fun `priced columns and suspended row support cannot be discarded`() {
        for (priced in listOf(false, true)) {
            val source = LpBuilder().apply { addVar(0L, 3L, cost = if (priced) 1L else 0L) }
                .build(Sense.MINIMIZE)
            val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
            assertTrue(trail.push())
            assertTrue(trail.append(LpScopedRow(0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(2L),
                ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))))), true))
            assertTrue(trail.push())
            assertTrue(trail.suspend(setOf(0L)))
            val before = trail.state

            assertFalse(trail.compact(LpLayoutRemap(1, before.rows, emptyList())))

            assertSame(before, trail.state)
            assertTrue(trail.pop(1))
            assertTrue(trail.state.rows.row(0).active)
        }
    }

    @Test
    fun `a mixed batch rejects missing or conditional permanent rows without changing authority`() {
        val source = LpBuilder().apply { addVar(0L, 3L) }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        assertTrue(trail.push())
        val before = trail.state
        val row = LpScopedRow(
            0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(2L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))), ExactLpRow(global = false),
        )

        assertFalse(trail.append(emptyList(), listOf(row), true, permanentRows = setOf(1L)))
        assertFalse(trail.append(emptyList(), listOf(row), true, permanentRows = setOf(0L)))
        assertFalse(trail.append(emptyList(), emptyList(), true, permanentRows = setOf(0L)))

        assertSame(before, trail.state)
    }

    @Test
    fun `compaction retires replaced rows created in the current scope`() {
        val source = LpBuilder().apply { addVar(0L, 3L) }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        val zero = ExactLpNumber.of(0L)
        val minusOne = ExactLpNumber.of(-1L)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        assertTrue(trail.push())
        assertTrue(trail.append(LpScopedRow(1, listOf(0 to minusOne), minusOne, logical), true))
        assertTrue(trail.suspend(setOf(1)))
        assertTrue(trail.append(LpScopedRow(2, listOf(0 to minusOne), ExactLpNumber.of(-2L), logical), true))

        assertTrue(trail.compact())

        assertEquals(1, trail.state.model.m)
        assertNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ONE)))
        assertNotNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ofLong(2L))))
        assertTrue(trail.pop(0))
        assertNotNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ZERO)))
    }

    @Test
    fun `surviving logical bounds prevent suspension of their row`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 2L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 2L)
        }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        assertTrue(trail.assertBound(1, true, ExactLpSide(ExactLpNumber.of(1L)), 7))
        assertTrue(trail.push())
        val before = trail.state

        assertFalse(trail.suspend(setOf(0)))

        assertSame(before, trail.state)
        assertNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ZERO)))
        assertNotNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ONE)))
    }

    @Test
    fun `appended structural columns preserve logical assertions and source coordinates through pop`() {
        val one = ExactLpNumber.of(1L)
        val third = ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(3)))
        val zero = ExactLpNumber.of(0L)
        val source = LpBuilder().apply {
            val x = addVar(0L, 3L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 2L)
        }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        assertTrue(trail.assertBound(1, true, ExactLpSide(one), 7))
        assertTrue(trail.push())
        val column = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)), third, false, 8)
        val metadata = ExactLpRow(false, premises = ExactLpPremises(emptyList(), listOf(3)))
        val row = LpScopedRow(9, listOf(0 to one, 1 to third), one, ExactLpColumn(ExactLpBounds()), metadata)

        assertTrue(trail.append(listOf(LpStructuralColumn(column, third)), listOf(row), true))

        assertEquals(column, trail.state.baseModel.column(1))
        assertEquals(third, trail.state.model.objective.cost(1))
        assertEquals(metadata, trail.state.model.row(1))
        assertEquals(2, trail.state.assertions.single().column)
        assertEquals(7L, trail.state.assertions.single().witness)
        assertEquals(one, trail.state.model.column(2).bounds.upper?.number)
        assertEquals(third, trail.state.model.entries(1).single().number)
        assertNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ZERO, third.value)))
        assertNotNull(checkedLpWitness(
            assertNotNull(trail.state.toWorkingModel()),
            listOf(BigFraction.ONE, third.value),
        ))
        assertTrue(trail.pop(0))
        assertFalse(trail.state.rows.row(1).active)
        assertEquals(one, trail.state.model.column(2).bounds.upper?.number)
        assertEquals(2, trail.state.model.n)
        assertNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ZERO, third.value)))
    }

    @Test
    fun `invalid structural batches leave every prior assertion and row unchanged`() {
        val one = ExactLpNumber.of(1L)
        val source = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        val row = LpScopedRow(2, listOf(1 to one), one, ExactLpColumn(ExactLpBounds()))
        val before = trail.state

        assertFalse(trail.append(emptyList(), listOf(row), false))
        assertFalse(trail.append(listOf(LpStructuralColumn(ExactLpColumn(ExactLpBounds()))), listOf(row, row), false))
        assertFalse(trail.append(emptyList(), emptyList(), false, Cancellation { true }))

        assertSame(before, trail.state)
    }

    @Test
    fun `row replacements restore the enclosing relaxation on nested and sibling pops`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 10L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        val zero = ExactLpNumber.of(0L)
        val negativeOne = ExactLpNumber.of(-1L)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))
        assertTrue(trail.push())
        assertTrue(trail.suspend(setOf(0)))
        assertTrue(trail.append(LpScopedRow(1, listOf(0 to negativeOne), ExactLpNumber.of(-3L), logical), true))
        assertTrue(trail.push())
        assertTrue(trail.suspend(setOf(1)))
        assertTrue(trail.append(LpScopedRow(2, listOf(0 to negativeOne), ExactLpNumber.of(-5L), logical), true))
        assertNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ofLong(4))))

        assertTrue(trail.pop(1))

        assertNotNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ofLong(3))))
        assertNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ofLong(2))))
        assertTrue(trail.pop(0))
        assertTrue(trail.push())
        assertTrue(trail.suspend(setOf(0)))
        assertTrue(trail.append(LpScopedRow(3, listOf(0 to negativeOne), ExactLpNumber.of(-7L), logical), true))
        assertNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ofLong(6))))
        assertTrue(trail.pop(0))
        assertNotNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ONE)))
        assertNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ZERO)))
    }

    @Test
    fun `compaction retains suspended rows until their enclosing scope is restored`() {
        val source = LpBuilder().apply {
            val x = addVar(0L, 10L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 9L)
        }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(source.authoritativeModel()))
        assertTrue(trail.deactivate(1))
        assertTrue(trail.push())
        assertTrue(trail.suspend(setOf(0)))

        assertTrue(trail.compact())

        assertEquals(1, trail.state.model.m)
        assertEquals(0L, trail.state.rows.row(0).id)
        assertNotNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ZERO)))
        assertTrue(trail.pop(0))
        assertNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ZERO)))
        assertNotNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ONE)))
    }

    @Test
    fun `suspension restores strictness and declines invalid or cancelled edits atomically`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)), integral = false),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow(strict = true)),
            ExactLpObjective(listOf(zero, zero)),
        )
        val trail = LpBoundTrail(source)
        assertFalse(trail.suspend(setOf(0)))
        assertTrue(trail.push())
        val before = trail.state
        assertFalse(trail.suspend(setOf(0, 7)))
        assertFalse(trail.suspend(setOf(0), Cancellation { true }))
        assertSame(before, trail.state)
        assertTrue(trail.suspend(setOf(0)))
        assertNotNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ONE)))

        assertTrue(trail.pop(0))

        assertTrue(trail.state.model.row(0).strict)
        assertNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ONE)))
        assertNotNull(checkedLpWitness(assertNotNull(trail.state.toWorkingModel()), listOf(BigFraction.ZERO)))
    }

    @Test
    fun `persistent rows survive interleaved scopes and compaction preserves exact source maps`() {
        val zero = ExactLpNumber.of(0L)
        val third = ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(3)))
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
        val remap = LpLayoutRemap(1, before.rows)
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
        assertEquals(BigFraction.ZERO, exactLagrangian(model, listOf(BigFraction.ONE)))
        assertFalse(sourceFarkasValid(model, longArrayOf(1)))
        val certificate = assertNotNull(integerCertify(model, doubleArrayOf(1.0)))
        assertEquals(0L, certificate.objectiveBoundCeil(0L))
        assertFalse(certificate.dualNonzeroRow(0))
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
        val huge = ExactLpNumber.of(BigFraction.of(BIG_ONE shl 2048, BIG_ONE))
        val tiny = ExactLpNumber.of(BigFraction.of(BIG_ONE, BIG_ONE shl 2048))
        val cases = listOf(
            LpScopedRow(-1, emptyList(), zero, logical),
            LpScopedRow(0, listOf(1 to one), zero, logical),
            LpScopedRow(0, listOf(0 to one, 0 to one), zero, logical),
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
        val underflow = LpBoundTrail(source)
        assertTrue(underflow.append(LpScopedRow(0, listOf(0 to tiny), zero, logical), scoped = false))
        val projected = assertNotNull(underflow.state.toWorkingModel())
        assertEquals(tiny, underflow.state.model.entries(0).single().number)
        assertEquals(0.0, assertNotNull(projected.doubleView).colVal.single())
        assertEquals(LpMatrixProjectionStatus(1, 0), underflow.state.matrixProjectionStatus)
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
