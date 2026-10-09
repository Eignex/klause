package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
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
    fun `builder source bounds preserve open sides and exact mixed endpoints`() {
        val builder = LpBuilder().apply {
            addVar(2L, 5L)
            addFreeVar(null, 7L)
            addOpenAboveVar(-3L)
            addRealVar(0.1, 0.3)
            addRealVar(null, null)
        }
        val expectedLower = listOf(
            BigFraction.ofLong(2L),
            null,
            BigFraction.ofLong(-3L),
            BigFraction.ofDouble(0.1),
            null,
        )
        val expectedUpper = listOf(
            BigFraction.ofLong(5L),
            BigFraction.ofLong(7L),
            null,
            BigFraction.ofDouble(0.3),
            null,
        )
        val bounds = List(builder.varCount) { builder.sourceBounds(it) }
        val model = builder.build(Sense.MINIMIZE)

        for (column in bounds.indices) {
            assertEquals(expectedLower[column], bounds[column].lower?.number?.value)
            assertEquals(expectedUpper[column], bounds[column].upper?.number?.value)
            val normalized = model.exactBounds(column)
            val origin = model.exactShift(column)
            assertEquals(expectedLower[column], normalized.lower?.let { it.number.value + origin })
            assertEquals(expectedUpper[column], normalized.upper?.let { it.number.value + origin })
        }
    }

    @Test
    fun `objective helpers clear every previous structural and logical cost`() {
        val source = LpBuilder().apply {
            addVar(0L, 2L, cost = 7L)
            addVar(0L, 2L, cost = 11L)
            addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.LE, 2L)
        }.build(Sense.MINIMIZE)
        source.cost[2] = 13L

        val single = source.withSingleColumnObjective(1, -1L)
        val row = source.withRowObjective(intArrayOf(0), longArrayOf(3L))

        assertContentEquals(longArrayOf(0L, -1L, 0L), single.cost)
        assertContentEquals(longArrayOf(3L, 0L, 0L), row.cost)
        assertContentEquals(longArrayOf(7L, 11L, 13L), source.cost)
    }

    @Test
    fun `mixed rows preserve coalescing shifts and maximizing costs`() {
        val model = LpBuilder().apply {
            val x = addVar(2L, 7L, cost = 3L)
            val y = addRealVar(-1.0, 2.5, cost = -0.5)
            addRealRow(intArrayOf(x, x, y), doubleArrayOf(2.0, -1.0, 0.5), Relation.GE, 4.0)
            addRow(intArrayOf(x, x), longArrayOf(3L, -1L), Relation.EQ, 6L)
        }.build(Sense.MAXIMIZE)

        val view = assertNotNull(model.doubleView)

        assertContentEquals(intArrayOf(0, 2, 3), view.colPtr)
        assertContentEquals(intArrayOf(0, 1, 0), view.rowIdx)
        assertContentEquals(doubleArrayOf(-1.0, 2.0, -0.5), view.colVal)
        assertContentEquals(doubleArrayOf(-2.5, 2.0), view.rhs)
        assertContentEquals(doubleArrayOf(-3.0, 0.5, 0.0, 0.0), view.cost)
        assertContentEquals(doubleArrayOf(5.0, 3.5, 0.0, 0.0), view.upper)
        assertContentEquals(doubleArrayOf(2.0, -1.0), view.loShift)
        assertEquals(-6.5, view.objConstant)
    }

    @Test
    fun `IEEE recenter cannot erase source authority by deriving integral values`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(ExactLpNumber.ofIeee(2.0)),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(
                        ExactLpSide(ExactLpNumber.ofIeee(1.0)),
                        ExactLpSide(ExactLpNumber.ofIeee(3.0)),
                    ),
                ),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero), ExactLpNumber.ofIeee(0.0)),
        )

        assertFailsWith<IllegalArgumentException> { model.recentered(listOf(one)) }
        assertTrue(model.sameAuthority(model.recentered(listOf(zero))))
        assertEquals(2.0.toRawBits(), model.rhs(0).ieeeBits)
        assertNull(model.toLegacy())
        assertNotNull(exactLpStateKey(model))
    }

    @Test
    fun `integral recenter can produce a lossless normalized bridge`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(ExactLpNumber.of(2L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(one), ExactLpSide(ExactLpNumber.of(3L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )

        val recentered = model.recentered(listOf(one))
        val legacy = assertNotNull(recentered.toLegacy())

        assertEquals(1L, legacy.rhs[0])
        assertEquals(2L, legacy.upper[0])
        assertEquals(1L, legacy.objConstant)
        assertNotNull(exactLpStateKey(recentered))
    }

    @Test
    fun `adjacent integral authority retains distinct legacy keys`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(9007199254740992L))))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val next = model.copy(
            columns = listOf(
                model.column(0).copy(
                    bounds = ExactLpBounds(
                        ExactLpSide(zero),
                        ExactLpSide(ExactLpNumber.of(9007199254740993L)),
                    ),
                ),
            ),
        )

        assertFalse(assertNotNull(exactLpStateKey(model)).contentEquals(assertNotNull(exactLpStateKey(next))))
        assertFalse(model.sameAuthority(next))
        assertEquals(9007199254740993L, assertNotNull(next.toLegacy()).upper[0])
    }

    @Test
    fun `nonintegral values in each numeric slot decline the legacy bridge`() {
        val zero = ExactLpNumber.of(0L)
        val third = ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(3)))
        val huge = ExactLpNumber.of(BigFraction.of(BIG_ONE shl 80, BIG_ONE))
        for (value in listOf(third, huge, ExactLpNumber.ofIeee(0.1))) {
            for (slot in 0..5) {
                val model = ExactLpModel(
                    listOf(listOf(ExactLpEntry(0, if (slot == 0) value else ExactLpNumber.of(1L)))),
                    listOf(if (slot == 1) value else zero),
                    listOf(
                        ExactLpColumn(
                            ExactLpBounds(ExactLpSide(zero), ExactLpSide(if (slot == 2) value else zero)),
                            if (slot == 3) value else zero,
                        ),
                        ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                    ),
                    listOf(ExactLpRow()),
                    ExactLpObjective(
                        listOf(if (slot == 4) value else zero, zero),
                        if (slot == 5) value else zero,
                    ),
                )

                assertNull(model.toLegacy(), "slot $slot")
                assertNotNull(exactLpStateKey(model))
                assertTrue(model.sameAuthority(model.copy()))
            }
        }
    }

    @Test
    fun `source metadata and objective units participate in exact authority`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            listOf(zero),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val variants = listOf(
            model.copy(columns = listOf(model.column(0).copy(tag = 7), model.column(1))),
            model.copy(columns = listOf(model.column(0).copy(integral = false), model.column(1))),
            model.copy(rows = listOf(ExactLpRow(strict = true))),
            model.copy(rows = listOf(ExactLpRow(global = false, premises = ExactLpPremises(emptyList(), listOf(7))))),
            model.copy(objective = ExactLpObjective(listOf(zero, zero), scale = ExactLpNumber.of(2L))),
            model.copy(objective = ExactLpObjective(listOf(zero, zero), externalConstant = ExactLpNumber.of(1L))),
            model.copy(objective = ExactLpObjective(listOf(zero, zero), sense = Sense.MAXIMIZE)),
            ExactLpModel(
                listOf(emptyList()),
                listOf(zero),
                listOf(model.column(0).copy(origin = ExactLpNumber.ofIeee(-0.0)), model.column(1)),
                listOf(ExactLpRow()),
                model.objective,
            ),
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
            model.withSingleColumnObjective(0, -1L),
            model.withRowObjective(intArrayOf(0), longArrayOf(3L)),
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

        val single = model.withSingleColumnObjective(0, -1L)
        val row = single.withRowObjective(intArrayOf(0), longArrayOf(7L))

        assertContentEquals(before, exactLpStateKey(model))
        assertEquals(3L, model.cost[0])
        assertEquals(-1L, single.cost[0])
        assertEquals(7L, row.cost[0])
    }

    @Test
    fun `exact authority retains rationals and large integers across copies`() {
        val values = listOf(
            BigFraction.of(BIG_ONE, bigIntOf(3)),
            BigFraction.ofLong(9007199254740992L),
            BigFraction.ofLong(9007199254740993L),
            BigFraction.of(BIG_ONE shl 80, BIG_ONE),
        )
        for (value in values) {
            val number = ExactLpNumber.of(value)
            val model = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, number))),
                listOf(number),
                listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(number))), ExactLpColumn(ExactLpBounds())),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(number, ExactLpNumber.of(0L)), number),
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
        val decimal = ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(10)))
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
        val next = ExactLpNumber.of(BigFraction.ONE + BigFraction.of(BIG_ONE, BIG_ONE shl 54))
        val bounds = ExactLpBounds(ExactLpSide(one), ExactLpSide(next))

        assertEquals(one.value.toDouble(), next.value.toDouble())
        assertTrue(bounds.consistent)
        assertFalse(bounds.fixed)
    }

    @Test
    fun `recenter preserves source equations and both objective constants`() {
        val zero = ExactLpNumber.of(0L)
        val third = ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(3)))
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(2L)))),
            listOf(ExactLpNumber.of(10L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(9L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow(strict = true)),
            ExactLpObjective(
                listOf(ExactLpNumber.of(3L), zero),
                ExactLpNumber.of(5L),
                ExactLpNumber.of(2L),
                ExactLpNumber.of(7L),
                Sense.MAXIMIZE,
            ),
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
            listOf(entries),
            rhs,
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(4L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow(global = false, premises = ExactLpPremises(premises))),
            ExactLpObjective(costs),
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
            listOf(emptyList()),
            emptyList(),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(4L))),
                    ExactLpNumber.of(2L),
                ),
            ),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(3L)), ExactLpNumber.of(11L)),
        )

        val rebound = assertNotNull(model.toLegacy()).rebind(longArrayOf(3L), longArrayOf(5L))

        assertEquals(14L, rebound.objConstant)
        assertFailsWith<IllegalArgumentException> { model.copy(columns = listOf(model.column(0).copy(origin = zero))) }
    }

    @Test
    fun `a Farkas ray is checked against the exact shift where binary64 rounds it`() {
        // A + U + B + V >= 0 over B in [-4.2e18, -4e18]: shifting by B's lower bound puts 4.2e18 + 300 on the
        // right-hand side, which binary64 rounds up by 212 — enough to make the feasible model look infeasible.
        for ((vUpper, infeasible) in listOf(-100.0 to false, -250.0 to true)) {
            val model = LpBuilder().apply {
                val a = addRealVar(0.0, 4e18, cost = 1.0)
                val u = addRealVar(0.0, 200.0)
                val b = addRealVar(-4.2e18, -4e18)
                val v = addRealVar(-300.0, vUpper)
                addRealRow(intArrayOf(a, u, b, v), doubleArrayOf(1.0, 1.0, 1.0, 1.0), Relation.GE, 0.0)
            }.build(Sense.MINIMIZE)

            assertEquals(infeasible, sourceFarkasValid(model, longArrayOf(-1L)), "V <= $vUpper")
        }
    }

    @Test
    fun `a shift from the open-side stand-in that rounds is read exactly`() {
        val model = LpBuilder().apply {
            val a = addRealVar(null, 4e3, cost = 1.0)
            val v = addRealVar(-300.0, -100.0)
            addRealRow(intArrayOf(a, v), doubleArrayOf(0.1, 1.0), Relation.GE, 0.0)
        }.build(Sense.MINIMIZE)

        assertTrue(model.finiteExactInput())
        assertEquals(
            exactDouble(0.1) * exactDouble(-LP_UNBOUNDED_PROBE.toDouble()) + exactDouble(-300.0),
            model.exactRhs(0),
        )
    }

    @Test
    fun `a repeated column whose summed coefficient rounds keeps the model from exact certification`() {
        val model = LpBuilder().apply {
            val a = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(a, a), doubleArrayOf(1.0, 1e-30), Relation.LE, 1.0)
        }.build(Sense.MINIMIZE)

        assertFalse(model.finiteExactInput())
    }

    @Test
    fun `an integer right-hand side past binary64 integers keeps its exact value for certification`() {
        val model = LpBuilder().apply {
            addRealVar(0.0, 1.0)
            val x = addVar(0L, 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, PAST_BINARY64)
        }.build(Sense.MINIMIZE)

        assertTrue(model.finiteExactInput())
        assertEquals(BigFraction.ofLong(PAST_BINARY64), model.exactRhs(0))
    }

    @Test
    fun `an integer upper bound past binary64 integers keeps its exact value for certification`() {
        val model = LpBuilder().apply {
            addRealVar(0.0, 1.0)
            addVar(0L, PAST_BINARY64)
        }.build(Sense.MINIMIZE)

        assertTrue(model.finiteExactInput())
        assertEquals(BigFraction.ofLong(PAST_BINARY64), model.exactUpper(1))
    }

    @Test
    fun `a double view column is fixed only when its exact width is zero`() {
        val lo = 1L shl 60
        for ((hi, fixed) in listOf(lo to true, lo + 1L to false)) {
            val model = LpBuilder().apply {
                addRealVar(0.0, 1.0)
                addVar(lo, hi)
            }.build(Sense.MINIMIZE)

            assertEquals(fixed, model.fixed(1), "[$lo, $hi]")
        }
    }

    @Test
    fun `a double view rounds a column's exact upper bound to an integer in the asked direction`() {
        val lo = 1L shl 60
        val real = LpBuilder().apply {
            addVar(0L, 1L)
            addRealVar(0.0, 2.5)
        }.build(Sense.MINIMIZE)
        val roundedInteger = LpBuilder().apply {
            addRealVar(0.0, 1.0)
            addVar(lo, lo + 1L)
        }.build(Sense.MINIMIZE)
        val cases = listOf(
            Triple(real, true, 3L),
            Triple(real, false, 2L),
            Triple(roundedInteger, true, 1L),
            Triple(roundedInteger, false, 1L),
        )

        for ((model, outward, expected) in cases) {
            assertEquals(expected, checkNotNull(model.doubleView).integerUpper(1, outward), "outward=$outward")
        }
    }

    @Test
    fun `integer model data past binary64 integers keeps the model from exact certification`() {
        val variants = mapOf<String, LpBuilder.() -> Unit>(
            "coefficient" to { addRow(intArrayOf(addVar(0L, 1L)), longArrayOf(PAST_BINARY64), Relation.LE, 1L) },
            "cost" to { addVar(0L, 1L, cost = PAST_BINARY64) },
            "lower bound" to { addVar(PAST_BINARY64, PAST_BINARY64 + 1L) },
        )
        for ((name, variant) in variants) {
            val model = LpBuilder().apply {
                addRealVar(0.0, 1.0)
                variant()
            }.build(Sense.MINIMIZE)

            assertFalse(model.finiteExactInput(), name)
        }
    }

    @Test
    fun `a row objective coefficient past binary64 integers keeps the model from exact certification`() {
        val model = LpBuilder().apply {
            addRealVar(0.0, 1.0)
            addVar(0L, 1L)
        }.build(Sense.MINIMIZE)

        assertFalse(model.withRowObjective(intArrayOf(1), longArrayOf(PAST_BINARY64)).finiteExactInput())
    }

    @Test
    fun `the free integer stand-in does not keep the model from exact certification`() {
        val model = LpBuilder().apply {
            addRealVar(0.0, 1.0)
            val x = addFreeVar(null, null)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 5L)
        }.build(Sense.MINIMIZE)

        assertTrue(model.finiteExactInput())
        assertEquals(BigFraction.ofLong(5L) - exactDouble(-LP_UNBOUNDED_PROBE.toDouble()), model.exactRhs(0))
    }

    private companion object {
        const val PAST_BINARY64: Long = (1L shl 53) + 1L
    }
}
