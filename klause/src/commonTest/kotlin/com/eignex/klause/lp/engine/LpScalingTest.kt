package com.eignex.klause.lp.engine

import com.eignex.klause.lp.bounding.trailModel
import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpScalingTest {

    @Test
    fun `power of two view maps structural and logical coordinates`() {
        val model = mixedScaleModel()

        val view = LpScalingView.create(model)

        assertTrue(view.applied)
        assertTrue(view.metrics.afterMatrixRatio < view.metrics.beforeMatrixRatio)
        for (row in 0 until model.m) {
            assertEquals(-view.rowExponents[row], view.columnExponents[model.n + row])
        }
        for (column in 0 until model.n) {
            var k = view.colPtr[column]
            model.forEachInColumnD(column) { row, source ->
                val expected = source * 2.0.pow(view.rowExponents[row] + view.columnExponents[column])
                assertEquals(expected, view.colVal[k++], 0.0)
            }
        }
        val structural = 1.25
        assertEquals(
            structural * 2.0.pow(view.columnExponents[0]),
            view.sourceCoordinate(0, structural),
            0.0,
        )
        val scaledDual = -0.75
        assertEquals(scaledDual * 2.0.pow(view.rowExponents[0]), view.sourceDual(0, scaledDual), 0.0)
        val scaledDirection = DoubleArray(model.numVars) { it + 0.5 }
        val sourceDirection = view.sourceDirection(scaledDirection)
        for (column in scaledDirection.indices) {
            assertEquals(
                scaledDirection[column] * 2.0.pow(view.columnExponents[column]),
                sourceDirection[column],
                0.0,
            )
        }
    }

    @Test
    fun `logical costs and bounds use the reciprocal row scale`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, ExactLpNumber.ofIeee(1.0 / 1_048_576.0))),
                listOf(ExactLpEntry(0, ExactLpNumber.of(1_048_576L))),
            ),
            listOf(ExactLpNumber.of(4L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(8L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(8L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(16L)))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(
                listOf(ExactLpNumber.of(3L), ExactLpNumber.of(5L), ExactLpNumber.of(7L)),
                constant = ExactLpNumber.of(11L),
                scale = ExactLpNumber.of(2L),
                externalConstant = ExactLpNumber.of(13L),
            ),
        )
        val model = requireNotNull(LpExactState(source).toWorkingModel())

        val view = LpScalingView.create(model)
        val logical = model.n
        val factor = 2.0.pow(view.columnExponents[logical])

        assertTrue(view.applied)
        assertEquals(-view.rowExponents[0], view.columnExponents[logical])
        assertEquals(model.costD(logical) * factor, view.costD(logical), 0.0)
        assertEquals(model.upperD(logical) / factor, view.upperD(logical), 0.0)
        assertEquals(model.objConstantD, 11.0)
        assertEquals(model.objectiveD(model.objConstantD), 18.5)
    }

    @Test
    fun `free and upper only source bounds map through structural scales`() {
        val builder = LpBuilder()
        val free = builder.addRealVar(null, null, cost = -2.0)
        val upperOnly = builder.addRealVar(null, 8.0, cost = 3.0)
        builder.addRealRow(
            intArrayOf(free, upperOnly),
            doubleArrayOf(1e-6, 1e6),
            Relation.LE,
            4.0,
        )
        val model = builder.build(Sense.MINIMIZE)

        val view = LpScalingView.create(model)

        assertTrue(view.applied)
        for (column in intArrayOf(free, upperOnly)) {
            val factor = 2.0.pow(view.columnExponents[column])
            assertEquals(model.lowerD(column) / factor, view.lowerD(column), 0.0)
            assertEquals(model.upperD(column) / factor, view.upperD(column), 0.0)
            assertEquals(model.costD(column) * factor, view.costD(column), 0.0)
        }
    }

    @Test
    fun `scaling has an explicit off switch`() {
        val view = LpScalingView.create(mixedScaleModel(), LpScalingOptions(enabled = false))

        assertFalse(view.applied)
        assertEquals(LpScalingDecline.DISABLED, view.metrics.decline)
        assertTrue(view.rowExponents.all { it == 0 })
        assertTrue(view.columnExponents.all { it == 0 })
    }

    @Test
    fun `nonfinite matrix input falls back atomically`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        val y = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x, y), doubleArrayOf(Double.NaN, 1_000_000.0), Relation.LE, 1.0)

        val view = LpScalingView.create(builder.build(Sense.MINIMIZE))

        assertFalse(view.applied)
        assertTrue(view.metrics.eligible)
        assertEquals(LpScalingDecline.NONFINITE_SOURCE, view.metrics.decline)
        assertTrue(view.rowExponents.all { it == 0 })
        assertTrue(view.columnExponents.all { it == 0 })
    }

    @Test
    fun `unsafe scaled cost falls back without a partial matrix`() {
        val builder = LpBuilder()
        val small = builder.addRealVar(0.0, 1.0, cost = 1e300)
        val large = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(small, large), doubleArrayOf(1e-300, 1e300), Relation.LE, 1.0)
        val model = builder.build(Sense.MINIMIZE)

        val view = LpScalingView.create(model)

        assertFalse(view.applied)
        assertEquals(LpScalingDecline.UNSAFE_TRANSFORM, view.metrics.decline)
        assertEquals(model.costD(small), view.costD(small), 0.0)
        var k = view.colPtr[small]
        model.forEachInColumnD(small) { _, value -> assertEquals(value, view.colVal[k++], 0.0) }
    }

    @Test
    fun `exact projection loss is not reported as safe scaling`() {
        val tiny = ExactLpNumber.of(BigFraction.of(BigInteger.ONE, BigInteger.ONE shl 2000))
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1_000_000L)))),
            listOf(tiny),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val model = requireNotNull(LpExactState(source).toWorkingModel())

        val view = LpScalingView.create(model)
        val disabled = LpScalingView.create(model, LpScalingOptions(enabled = false))

        assertFalse(view.applied)
        assertEquals(LpScalingDecline.PROJECTION_LOSS, view.metrics.decline)
        val refreshed = requireNotNull(disabled.refresh(model))
        assertEquals(LpScalingDecline.DISABLED, refreshed.metrics.decline)
        assertEquals(0, refreshed.metrics.fallbacks)
    }

    @Test
    fun `exact bound width is formed before scaling`() {
        val zero = ExactLpNumber.of(0L)
        val lower = ExactLpNumber.of(9007199254740992L)
        val upper = ExactLpNumber.of(9007199254740993L)
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, ExactLpNumber.ofIeee(1e-6))),
                listOf(ExactLpEntry(0, ExactLpNumber.ofIeee(1e6))),
            ),
            listOf(zero),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(lower), ExactLpSide(upper))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero, zero)),
        )
        val view = LpScalingView.create(requireNotNull(LpExactState(source).toWorkingModel()))

        assertTrue(view.applied)
        assertEquals(2.0.pow(-view.columnExponents[0]), view.boundRangeD(0), 0.0)
    }

    @Test
    fun `refresh reads mutated numerical inputs while older views retain their vectors`() {
        for (enabled in listOf(false, true)) {
            for (exact in listOf(false, true)) {
                for (changed in listOf("rhs", "cost", "upper", "all")) {
                    val inputModel = mixedScaleModel()
                    val model = if (exact) {
                        assertNotNull(LpExactState(assertNotNull(inputModel.trailModel())).toWorkingModel())
                    } else {
                        inputModel
                    }
                    val view = LpScalingView.create(model, LpScalingOptions(enabled = enabled))
                    assertEquals(enabled, view.applied)
                    val oldRhs = view.rhsD(0)
                    val oldCost = view.costD(0)
                    val oldUpper = view.upperD(0)
                    val input = assertNotNull(model.doubleView)
                    if (changed in listOf("rhs", "all")) input.rhs[0] *= 2.0
                    if (changed in listOf("cost", "all")) input.cost[0] *= 2.0
                    if (changed in listOf("upper", "all")) input.upper[0] *= 2.0

                    val next = assertNotNull(view.refresh(model))

                    assertSame(model, next.source)
                    assertEquals(model.rhsD(0) * 2.0.pow(view.rowExponents[0]), next.rhsD(0))
                    assertEquals(model.costD(0) * 2.0.pow(view.columnExponents[0]), next.costD(0))
                    assertEquals(model.upperD(0) * 2.0.pow(-view.columnExponents[0]), next.upperD(0))
                    assertEquals(oldRhs, view.rhsD(0))
                    assertEquals(oldCost, view.costD(0))
                    assertEquals(oldUpper, view.upperD(0))
                    input.rhs[0] *= 2.0
                    val last = assertNotNull(next.refresh(model))
                    assertEquals(next.rhsD(0) * 2.0, last.rhsD(0))
                    assertEquals(oldRhs, view.rhsD(0))
                }
            }
        }
    }

    @Test
    fun `scope and auxiliary refresh bind their own exact source and active vectors`() {
        for (enabled in listOf(false, true)) {
            val trail = LpBoundTrail(assertNotNull(mixedScaleModel().trailModel()))
            val original = assertNotNull(trail.state.toWorkingModel())
            val view = LpScalingView.create(original, LpScalingOptions(enabled = enabled))
            assertTrue(trail.push())
            val scoped = assertNotNull(trail.state.toWorkingModel())
            val pushed = assertNotNull(view.refresh(scoped))
            assertSame(scoped, pushed.source)
            assertSame(trail.state, pushed.source.exactState)
            val lower = ExactLpSide(ExactLpNumber.of(1L))
            val upper = ExactLpSide(ExactLpNumber.of(6L))
            val working = LpWorkingModel.overrides(
                trail.state,
                objective = ExactLpObjective(List(trail.state.model.numVars) { ExactLpNumber.of(4L) }),
                bounds = List(trail.state.model.numVars) { ExactLpBounds(lower, if (it == 1) null else upper) },
                rhs = listOf(ExactLpNumber.of(8L)),
            )
            val model = assertNotNull(working.state.toWorkingModel())

            val next = assertNotNull(pushed.refresh(model))

            assertSame(model, next.source)
            assertSame(working.state, next.source.exactState)
            assertEquals(8.0 * 2.0.pow(view.rowExponents[0]), next.rhsD(0))
            for (j in 0 until model.numVars) {
                assertEquals(4.0 * 2.0.pow(view.columnExponents[j]), next.costD(j))
                assertEquals(2.0.pow(-view.columnExponents[j]), next.lowerD(j))
                val expectedUpper = if (enabled && j == 1) 0.0 else model.upperD(j) * 2.0.pow(-view.columnExponents[j])
                assertEquals(expectedUpper, next.upperD(j))
                assertEquals(view.costD(j).toRawBits(), pushed.costD(j).toRawBits())
                assertEquals(view.lowerD(j).toRawBits(), pushed.lowerD(j).toRawBits())
                assertEquals(view.upperD(j).toRawBits(), pushed.upperD(j).toRawBits())
            }
            assertEquals(view.metrics, next.metrics)
        }
    }

    @Test
    fun `identity refresh preserves raw zeros and nonfinite payloads`() {
        for (value in listOf(
            -0.0,
            0.0,
            Double.MIN_VALUE,
            Double.POSITIVE_INFINITY,
            Double.fromBits(0x7ff8000000000042L),
        )) {
            val model = mixedScaleModel()
            val view = LpScalingView.create(model, LpScalingOptions(enabled = false))
            val previous = view.costD(0)
            assertNotNull(model.doubleView).cost[0] = value

            val next = assertNotNull(view.refresh(model))

            assertEquals(value.toRawBits(), next.costD(0).toRawBits())
            assertEquals(value.toRawBits(), assertNotNull(next.refresh(model)).costD(0).toRawBits())
            assertEquals(previous.toRawBits(), view.costD(0).toRawBits())
        }
    }

    @Test
    fun `unsafe later scaling leaves every earlier view vector unchanged`() {
        val model = mixedScaleModel()
        val view = LpScalingView.create(model)
        assertTrue(view.applied)
        val before = listOf(view.rhsD(0), view.costD(0), view.lowerD(0), view.upperD(0))
        val input = assertNotNull(model.doubleView)
        input.rhs[0] *= 2.0
        input.cost[0] *= 2.0
        input.upper[0] *= 2.0
        input.cost[model.numVars - 1] = Double.NaN

        assertNull(view.refresh(model))

        assertEquals(before, listOf(view.rhsD(0), view.costD(0), view.lowerD(0), view.upperD(0)))
    }

    @Test
    fun `matching vectors cannot bypass exact projection and constant checks`() {
        for (changed in listOf("rhs", "cost", "upper", "origin", "constant")) {
            val state = LpExactState(assertNotNull(mixedScaleModel().trailModel()))
            val model = assertNotNull(state.toWorkingModel())
            val view = LpScalingView.create(model)
            assertTrue(view.applied)
            val input = assertNotNull(model.doubleView)
            when (changed) {
                "rhs" -> input.rhs[0] = 0.0
                "cost" -> input.cost[0] = 0.0
                "upper" -> input.upper[0] = 0.0
                "origin" -> input.loShift[0] = 0.0
                "constant" -> input.objConstant = Double.POSITIVE_INFINITY
            }

            assertNull(view.refresh(model))

            assertSame(state, view.source.exactState)
            assertEquals(state.model.objective.cost(0).approximation * 2.0.pow(view.columnExponents[0]), view.costD(0))
        }
    }

    private fun mixedScaleModel(): LpModel {
        val builder = LpBuilder()
        val small = builder.addRealVar(5.0, 15.0, cost = 3.0)
        val large = builder.addRealVar(0.0, 4.0, cost = 2.0)
        builder.addRealRow(
            intArrayOf(small, large),
            doubleArrayOf(1.0 / 1_048_576.0, 1_048_576.0),
            Relation.GE,
            2_097_157.0,
        )
        return builder.build(Sense.MINIMIZE)
    }
}
