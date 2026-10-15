package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.floorBigInt
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearRowLpProjectionTest {

    private class RecordingBuilder(
        private val pin: Boolean? = null,
        private val openLo: Boolean = false,
        private val openHi: Boolean = false,
    ) : RelaxationBuilder {
        data class IntegerRow(val coefficients: List<Long>, val bound: Long)
        data class RealRow(val strict: Boolean)

        val integerRows = mutableListOf<IntegerRow>()
        val realRows = mutableListOf<RealRow>()
        var bigMRows = 0
        val booleanWeights = mutableListOf<LongArray?>()

        override fun linearRow(
            op: LinearOp,
            intVars: IntArray,
            coeffs: LongArray,
            bound: Long,
            contribution: Contribution,
        ) {
            integerRows += IntegerRow(coeffs.toList(), bound)
        }

        override fun realRow(
            columns: IntArray,
            coeffs: DoubleArray,
            op: LinearOp,
            rhs: Double,
            strict: Boolean,
            premiseLits: IntArray,
        ) {
            realRows += RealRow(strict)
        }

        override fun bigMRow(
            columns: IntArray,
            coeffs: LongArray,
            op: LinearOp,
            rhs: Long,
            global: Boolean,
            maxSide: Boolean,
        ) {
            bigMRows++
        }

        override fun intColumn(intVar: Int): Int = intVar
        override fun boolColumn(boolVar: Int): Int = 100 + boolVar
        override fun realColumn(realVar: Int): Int = 200 + realVar
        override fun liveBool(boolVar: Int): Boolean? = pin
        override fun liveDomain(intVar: Int): IntDomain = IntDomain(0, 10)
        override fun rootDomain(intVar: Int): IntDomain = IntDomain(0, 10)
        override fun statesLowerBound(intVar: Int): Boolean = !openLo
        override fun statesUpperBound(intVar: Int): Boolean = !openHi
        override fun auxColumn(lo: Long, hi: Long, presence: LongArray?, definition: LpAuxiliaryColumn?): Int = 300
        override fun hullEnabled(): Boolean = true
        override fun boolRow(
            literals: IntArray,
            weights: LongArray?,
            op: LinearOp,
            bound: Long,
            contribution: Contribution,
        ) {
            booleanWeights += weights
        }
        override fun row(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, contribution: Contribution) =
            error("unused")
    }

    @Test
    fun `unit-weight Boolean factors should keep the compact LP representation`() {
        for (factor in listOf<Factor>(Clause(intArrayOf(0, 2)), Cardinality.exactlyOne(intArrayOf(0, 2)))) {
            val builder = RecordingBuilder()

            factor.emitLpRelaxation(builder)

            assertTrue(builder.booleanWeights.isNotEmpty())
            for (weights in builder.booleanWeights) assertNull(weights)
        }
    }

    @Test
    fun `reified integer row should keep its big M relaxation`() {
        val builder = RecordingBuilder()

        ReifiedLinear(1, longArrayOf(2), intArrayOf(0), LinearOp.LE, 5).emitLpRelaxation(builder)

        assertEquals(2, builder.bigMRows)
    }

    @Test
    fun `strict real row should stay strict in the LP`() {
        val builder = RecordingBuilder()
        val factor = Linear(
            intVars = intArrayOf(0),
            intCoeffs = doubleArrayOf(0.5),
            realVars = intArrayOf(0),
            realCoeffs = doubleArrayOf(1.0),
            op = LinearOp.LE,
            bound = 2.5,
            strict = true,
        )

        factor.emitLpRelaxation(builder)

        assertEquals(listOf(RecordingBuilder.RealRow(strict = true)), builder.realRows)
    }

    @Test
    fun `wide row should round outward and unsupported factor should emit no row`() {
        val huge = bigIntOf(Long.MAX_VALUE) * bigIntOf(4)
        val wideBuilder = RecordingBuilder()
        val unsupportedBuilder = RecordingBuilder()

        Linear(intArrayOf(0), arrayOf(huge), LinearOp.LE, huge).emitLpRelaxation(wideBuilder)
        AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2).emitLpRelaxation(unsupportedBuilder)

        assertEquals(1, wideBuilder.realRows.size)
        assertTrue(unsupportedBuilder.integerRows.isEmpty())
        assertTrue(unsupportedBuilder.realRows.isEmpty())
    }

    private class CoreRowRecordingBuilder : RelaxationBuilder {
        data class Row(
            val op: LinearOp,
            val vars: List<Int>,
            val coeffs: List<Long>,
            val bound: Long,
            val contribution: Contribution,
        )

        val rows = mutableListOf<Row>()

        override fun linearRow(
            op: LinearOp,
            intVars: IntArray,
            coeffs: LongArray,
            bound: Long,
            contribution: Contribution,
        ) {
            rows += Row(op, intVars.toList(), coeffs.toList(), bound, contribution)
        }

        override fun boolRow(
            literals: IntArray,
            weights: LongArray?,
            op: LinearOp,
            bound: Long,
            contribution: Contribution,
        ) = error("unused")

        override fun hullEnabled(): Boolean = true
        override fun intColumn(intVar: Int): Int = error("unused")
        override fun boolColumn(boolVar: Int): Int = error("unused")
        override fun auxColumn(
            lo: Long,
            hi: Long,
            presence: LongArray?,
            definition: com.eignex.klause.lp.LpAuxiliaryColumn?,
        ): Int = error(
            "unused",
        )
        override fun liveDomain(intVar: Int): IntDomain = error("unused")
        override fun rootDomain(intVar: Int): IntDomain = error("unused")
        override fun statesLowerBound(intVar: Int): Boolean = true
        override fun statesUpperBound(intVar: Int): Boolean = true
        override fun row(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, contribution: Contribution) =
            error("unused")

        override fun bigMRow(
            columns: IntArray,
            coeffs: LongArray,
            op: LinearOp,
            rhs: Long,
            global: Boolean,
            maxSide: Boolean,
        ) = error("unused")
    }

    @Test
    fun `a linear constraint should linearize to a single core row over its terms`() {
        val linear = Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 5)
        val builder = CoreRowRecordingBuilder()

        linear.emitLpRelaxation(builder)

        assertEquals(1, builder.rows.size)
        assertEquals(LinearOp.LE, builder.rows[0].op)
        assertEquals(5L, builder.rows[0].bound)
        assertEquals(Contribution.CORE, builder.rows[0].contribution)
        assertEquals(linear.vars.toList(), builder.rows[0].vars)
        assertEquals(checkNotNull(linear.integerConstants).coeffs.toList(), builder.rows[0].coeffs)
    }

    private class WideRow(val cols: List<Int>, val coeffs: List<Double>, val op: LinearOp, val rhs: Double)

    private class WideRowRecordingBuilder(
        private val boxes: Map<Int, IntDomain>,
        private val openLo: Set<Int> = emptySet(),
        private val openHi: Set<Int> = emptySet(),
    ) : RelaxationBuilder {
        val realRows = mutableListOf<WideRow>()

        private var nextAux = 1000
        val auxBounds = mutableMapOf<Int, Pair<Long, Long>>()

        override fun intColumn(intVar: Int): Int = intVar
        override fun rootDomain(intVar: Int): IntDomain = boxes.getValue(intVar)
        override fun statesLowerBound(intVar: Int): Boolean = intVar !in openLo
        override fun statesUpperBound(intVar: Int): Boolean = intVar !in openHi
        override fun auxColumn(
            lo: Long,
            hi: Long,
            presence: LongArray?,
            definition: com.eignex.klause.lp.LpAuxiliaryColumn?,
        ): Int {
            val c = nextAux++
            auxBounds[c] = lo to hi
            return c
        }

        override fun realRow(
            columns: IntArray,
            coeffs: DoubleArray,
            op: LinearOp,
            rhs: Double,
            strict: Boolean,
            premiseLits: IntArray,
        ) {
            realRows += WideRow(columns.toList(), coeffs.toList(), op, rhs)
        }

        override fun linearRow(
            op: LinearOp,
            intVars: IntArray,
            coeffs: LongArray,
            bound: Long,
            contribution: Contribution,
        ) = error("unused")
        override fun boolRow(
            literals: IntArray,
            weights: LongArray?,
            op: LinearOp,
            bound: Long,
            contribution: Contribution,
        ) = error("unused")
        override fun hullEnabled(): Boolean = true
        override fun boolColumn(boolVar: Int): Int = error("unused")
        override fun liveDomain(intVar: Int): IntDomain = error("unused")
        override fun row(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, contribution: Contribution) =
            error("unused")
        override fun bigMRow(
            columns: IntArray,
            coeffs: LongArray,
            op: LinearOp,
            rhs: Long,
            global: Boolean,
            maxSide: Boolean,
        ) = error("unused")
    }

    // 2^64 + 1 is not exactly representable as a Double, so rounding direction matters.
    private val w = parseBigInt("18446744073709551617")

    @Test
    fun `a wide row over a sign-straddling variable should split into nonnegative parts`() {
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.LE, w)
        val b = WideRowRecordingBuilder(mapOf(0 to IntDomain(-5, 5)))
        row.emitLpRelaxation(b)
        assertEquals(2, b.realRows.size)
        val link = b.realRows.first { it.op == LinearOp.EQ }
        assertEquals(listOf(1.0, -1.0, 1.0), link.coeffs)
        assertEquals(0.0, link.rhs)
        assertEquals(setOf(0L to 5L), b.auxBounds.values.toSet())
        val outer = b.realRows.first { it.op == LinearOp.LE }
        assertEquals(2, outer.cols.size)
        assertTrue(floorBigInt(outer.coeffs[0]) <= w)
        assertTrue(floorBigInt(-outer.coeffs[1]) >= w)
        assertTrue(floorBigInt(outer.rhs) >= w)
    }

    @Test
    fun `a wide row over a column the model leaves open should emit no row`() {
        // Splitting would cap the column at an endpoint the model never stated.
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.LE, w)
        listOf(
            WideRowRecordingBuilder(mapOf(0 to IntDomain(-5, 5)), openHi = setOf(0)),
            WideRowRecordingBuilder(mapOf(0 to IntDomain(-5, 5)), openLo = setOf(0)),
            WideRowRecordingBuilder(mapOf(0 to IntDomain(0, 10)), openLo = setOf(0)),
        ).forEach { b ->
            row.emitLpRelaxation(b)
            assertEquals(0, b.realRows.size)
            assertTrue(b.auxBounds.isEmpty())
        }
    }

    @Test
    fun `a coefficient past the Double range should emit no relaxation row`() {
        val huge = BIG_ONE.shl(2000)
        val row = Linear(intArrayOf(0), arrayOf(huge), LinearOp.LE, huge)
        val b = WideRowRecordingBuilder(mapOf(0 to IntDomain(0, 10)))
        row.emitLpRelaxation(b)
        assertEquals(0, b.realRows.size)
    }

    @Test
    fun `a coefficient in the top Double exponent band should emit no row when it rounds to infinity`() {
        // 2^1024 - 2^969 exceeds the largest finite Double 2^1024 - 2^971 at the same bit length.
        val overflowing = BIG_ONE.shl(1024) - BIG_ONE.shl(969)
        val row = Linear(intArrayOf(0), arrayOf(overflowing), LinearOp.LE, overflowing)
        val b = WideRowRecordingBuilder(mapOf(0 to IntDomain(0, 10)))
        row.emitLpRelaxation(b)
        assertEquals(0, b.realRows.size)
    }

    @Test
    fun `a wide equality should emit a bracketing pair of outer rows`() {
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.EQ, w)
        val b = WideRowRecordingBuilder(mapOf(0 to IntDomain(0, 10)))
        row.emitLpRelaxation(b)
        assertEquals(2, b.realRows.size)
        assertEquals(setOf(LinearOp.LE, LinearOp.GE), b.realRows.map { it.op }.toSet())
    }
}
