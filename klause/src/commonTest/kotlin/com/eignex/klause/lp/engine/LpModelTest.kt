package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpModelTest {
    @Test
    fun `adjacent integral authority retains distinct legacy keys`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(9007199254740992L))))),
            emptyList(), ExactLpObjective(listOf(zero)))
        val next = model.copy(columns = listOf(model.column(0).copy(bounds = ExactLpBounds(
            ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(9007199254740993L))))))

        assertFalse(assertNotNull(exactLpStateKey(model)).contentEquals(assertNotNull(exactLpStateKey(next))))
        assertFalse(model.sameAuthority(next))
        assertEquals(9007199254740993L, assertNotNull(next.toLegacy()).upper[0])
    }

    @Test
    fun `nonintegral values in each numeric slot decline the legacy bridge`() {
        val zero = ExactLpNumber.of(0L)
        val third = ExactLpNumber.of(BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3)))
        val huge = ExactLpNumber.of(BigFraction.of(BigInteger.ONE shl 80, BigInteger.ONE))
        for (value in listOf(third, huge, ExactLpNumber.ofIeee(0.1))) {
            for (slot in 0..5) {
                val model = ExactLpModel(
                    listOf(listOf(ExactLpEntry(0, if (slot == 0) value else ExactLpNumber.of(1L)))),
                    listOf(if (slot == 1) value else zero),
                    listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(if (slot == 2) value else zero)),
                        if (slot == 3) value else zero), ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
                    listOf(ExactLpRow()), ExactLpObjective(listOf(if (slot == 4) value else zero, zero),
                        if (slot == 5) value else zero),
                )

                assertNull(model.toLegacy(), "slot $slot")
                assertNull(exactLpStateKey(model))
                assertTrue(model.sameAuthority(model.copy()))
            }
        }
    }

    @Test
    fun `source metadata and objective units participate in exact authority`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(listOf(emptyList()), listOf(zero),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds())), listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)))
        val variants = listOf(
            model.copy(columns = listOf(model.column(0).copy(tag = 7), model.column(1))),
            model.copy(columns = listOf(model.column(0).copy(integral = false), model.column(1))),
            model.copy(rows = listOf(ExactLpRow(strict = true))),
            model.copy(rows = listOf(ExactLpRow(global = false, premises = ExactLpPremises(emptyList(), listOf(7))))),
            model.copy(objective = ExactLpObjective(listOf(zero, zero), scale = ExactLpNumber.of(2L))),
            model.copy(objective = ExactLpObjective(listOf(zero, zero), externalConstant = ExactLpNumber.of(1L))),
            model.copy(objective = ExactLpObjective(listOf(zero, zero), sense = Sense.MAXIMIZE)),
            model.recentered(listOf(ExactLpNumber.ofIeee(-0.0))),
        )

        for (variant in variants) assertFalse(model.sameAuthority(variant))
    }

    @Test
    fun `integral rebind retains compact matrix and objective authority`() {
        val model = LpBuilder().apply {
            val x = addVar(2L, 8L, cost = 3L)
            addRow(intArrayOf(x), longArrayOf(2L), Relation.LE, 12L)
        }.build(Sense.MINIMIZE)

        val rebound = model.rebind(longArrayOf(3L), longArrayOf(7L))

        assertNull(rebound.bigView)
        assertNull(rebound.doubleView)
        assertTrue(model.csc === rebound.csc)
        assertTrue(model.cost === rebound.cost)
        assertContentEquals(longArrayOf(6L), rebound.rhs)
        assertContentEquals(longArrayOf(4L, 0L), rebound.upper)
        assertEquals(9L, rebound.objConstant)
    }

    @Test
    fun `strict real metadata survives same origin rebind and objective copies`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 8.0, cost = 2.0)
            addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.LE, 3.0, strict = true)
        }.build(Sense.MINIMIZE)

        val copies = listOf(
            model.rebind(longArrayOf(0L), longArrayOf(4L)),
            model.withSingleColumnObjective(0, -1L, 0),
            model.withRowObjective(intArrayOf(0), longArrayOf(3L), intArrayOf(0)),
        )

        for (copy in copies) {
            assertContentEquals(model.rowStrict, copy.rowStrict)
            assertContentEquals(model.colContinuous, copy.colContinuous)
            assertContentEquals(doubleArrayOf(0.5), assertNotNull(copy.doubleView).colVal)
            assertContentEquals(doubleArrayOf(3.0), copy.doubleView.rhs)
        }
        assertEquals(2.0, model.costD(0))
    }

    @Test
    fun `real recenter and rounded widths are rejected by legacy rebind`() {
        val model = LpBuilder().apply { addRealVar(0.0, 8.0) }.build(Sense.MINIMIZE)

        assertFailsWith<IllegalArgumentException> { model.rebind(longArrayOf(1L), longArrayOf(4L)) }
        assertFailsWith<IllegalArgumentException> { model.rebind(longArrayOf(0L), longArrayOf(9007199254740993L)) }
    }

    @Test
    fun `objective helpers do not mutate prior models`() {
        val model = LpBuilder().apply { addVar(2L, 5L, cost = 3L) }.build(Sense.MINIMIZE)
        val before = exactLpStateKey(model)

        val single = model.withSingleColumnObjective(0, -1L, 0)
        val row = single.withRowObjective(intArrayOf(0), longArrayOf(7L), intArrayOf(0))

        assertContentEquals(before, exactLpStateKey(model))
        assertEquals(3L, model.cost[0])
        assertEquals(-1L, single.cost[0])
        assertEquals(7L, row.cost[0])
    }

    @Test
    fun `exact authority retains rationals and large integers across copies`() {
        val values = listOf(
            BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3)),
            BigFraction.ofLong(9007199254740992L),
            BigFraction.ofLong(9007199254740993L),
            BigFraction.of(BigInteger.ONE shl 80, BigInteger.ONE),
        )
        for (value in values) {
            val number = ExactLpNumber.of(value)
            val model = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, number))), listOf(number),
                listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(number))), ExactLpColumn(ExactLpBounds())),
                listOf(ExactLpRow()), ExactLpObjective(listOf(number, ExactLpNumber.of(0L)), number),
            )

            val copy = model.copy()

            assertTrue(model.sameAuthority(copy))
            assertEquals(value, copy.entries(0).single().number.value)
            assertEquals(value, copy.rhs(0).value)
            assertEquals(value, copy.column(0).bounds.upper?.number?.value)
            assertEquals(value, copy.objective.cost(0).value)
            assertNull(copy.toLegacy())
        }
    }

    @Test
    fun `binary input retains its raw bits and differs from decimal authority`() {
        val binary = ExactLpNumber.ofIeee(0.1)
        val decimal = ExactLpNumber.of(BigFraction.of(BigInteger.ONE, BigInteger.fromInt(10)))
        val negativeZero = ExactLpNumber.ofIeee(-0.0)

        assertFalse(binary.value == decimal.value)
        assertEquals(0.1.toRawBits(), binary.ieeeBits)
        assertEquals(BigFraction.ZERO, negativeZero.value)
        assertEquals((-0.0).toRawBits(), negativeZero.ieeeBits)
        assertFalse(negativeZero == ExactLpNumber.ofIeee(0.0))
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { ExactLpNumber.ofIeee(value) }
        }
    }

    @Test
    fun `bounds sharing a double are not exactly fixed`() {
        val one = ExactLpNumber.of(1L)
        val next = ExactLpNumber.of(BigFraction.ONE + BigFraction.of(BigInteger.ONE, BigInteger.ONE shl 54))
        val bounds = ExactLpBounds(ExactLpSide(one), ExactLpSide(next))

        assertEquals(one.value.toDouble(), next.value.toDouble())
        assertTrue(bounds.consistent)
        assertFalse(bounds.fixed)
    }

    @Test
    fun `recenter preserves source equations and both objective constants`() {
        val zero = ExactLpNumber.of(0L)
        val third = ExactLpNumber.of(BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3)))
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(2L)))), listOf(ExactLpNumber.of(10L)),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(9L)))) ,
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            listOf(ExactLpRow(strict = true)),
            ExactLpObjective(listOf(ExactLpNumber.of(3L), zero), ExactLpNumber.of(5L), ExactLpNumber.of(2L),
                ExactLpNumber.of(7L), Sense.MAXIMIZE),
        )

        val next = model.recentered(listOf(third))
        val oldPoint = listOf(BigFraction.ONE, BigFraction.ofLong(8L))
        val newPoint = listOf(BigFraction.ONE - third.value, BigFraction.ofLong(8L))

        assertEquals(model.objective.sourceValue(oldPoint), next.objective.sourceValue(newPoint))
        assertEquals(BigFraction.ofLong(-11L), next.objective.sourceValue(newPoint))
        assertEquals(BigFraction.ofLong(10L) - third.value * BigFraction.ofLong(2L), next.rhs(0).value)
        assertEquals(third.value.negated(), next.column(0).bounds.lower?.number?.value)
        assertEquals(ExactLpNumber.of(6L), next.objective.constant)
        assertEquals(ExactLpNumber.of(7L), next.objective.externalConstant)
        assertTrue(next.row(0).strict)
        assertFalse(model.sameAuthority(next))
    }

    @Test
    fun `snapshots own lists and bridge arrays`() {
        val zero = ExactLpNumber.of(0L)
        val entries = mutableListOf(ExactLpEntry(0, ExactLpNumber.of(2L)))
        val rhs = mutableListOf(ExactLpNumber.of(8L))
        val costs = mutableListOf(ExactLpNumber.of(3L), zero)
        val premises = mutableListOf(ExactLpPremise(4, true, ExactLpNumber.of(9L)))
        val model = ExactLpModel(
            listOf(entries), rhs,
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(4L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            listOf(ExactLpRow(global = false, premises = ExactLpPremises(premises))), ExactLpObjective(costs),
        )
        val copy = model.copy()
        val legacy = assertNotNull(model.toLegacy())

        entries.clear()
        rhs[0] = zero
        costs[0] = zero
        premises.clear()
        legacy.csc.colVal[0] = -100L
        legacy.rowPremises[0]!!.thresholds[0] = -100L

        assertTrue(model.sameAuthority(copy))
        val fresh = assertNotNull(model.toLegacy())
        assertEquals(2L, fresh.csc.colVal[0])
        assertEquals(8L, fresh.rhs[0])
        assertEquals(3L, fresh.cost[0])
        assertEquals(9L, fresh.rowPremises[0]!!.thresholds[0])
    }

    @Test
    fun `bridge preserves an independent model constant through integral rebind`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(4L))), ExactLpNumber.of(2L))),
            emptyList(), ExactLpObjective(listOf(ExactLpNumber.of(3L)), ExactLpNumber.of(11L)),
        )

        val rebound = assertNotNull(model.toLegacy()).rebind(longArrayOf(3L), longArrayOf(5L))

        assertEquals(14L, rebound.objConstant)
        assertFailsWith<IllegalArgumentException> { model.copy(columns = listOf(model.column(0).copy(origin = zero))) }
    }
}
