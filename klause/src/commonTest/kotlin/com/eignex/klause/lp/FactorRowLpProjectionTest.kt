package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FactorRowLpProjectionTest {

    private class RecordingBuilder(private val pin: Boolean? = null) : RelaxationBuilder {
        data class IntegerRow(val coefficients: List<Long>, val bound: Long)
        data class RealRow(val strict: Boolean)

        val integerRows = mutableListOf<IntegerRow>()
        val realRows = mutableListOf<RealRow>()
        var bigMRows = 0

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
        override fun declaredDomain(intVar: Int): IntDomain = IntDomain(0, 10)
        override fun auxColumn(lo: Long, hi: Long, presence: LongArray?): Int = 300
        override fun hullEnabled(): Boolean = true
        override fun boolRow(
            literals: IntArray,
            weights: LongArray?,
            op: LinearOp,
            bound: Long,
            contribution: Contribution,
        ) = error("unused")
        override fun row(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, contribution: Contribution) =
            error("unused")
    }

    @Test
    fun `an unconditional row retains its exact integer constants`() {
        val builder = RecordingBuilder()

        Linear(longArrayOf(Long.MAX_VALUE), intArrayOf(0), LinearOp.LE, Long.MAX_VALUE).emitLpRelaxation(builder)

        assertEquals(listOf(RecordingBuilder.IntegerRow(listOf(Long.MAX_VALUE), Long.MAX_VALUE)), builder.integerRows)
    }

    @Test
    fun `a reified integer row retains its big M relaxation`() {
        val builder = RecordingBuilder()

        ReifiedLinear(1, longArrayOf(2), intArrayOf(0), LinearOp.LE, 5).emitLpRelaxation(builder)

        assertEquals(2, builder.bigMRows)
    }

    @Test
    fun `a strict real row remains strict in the LP`() {
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
    fun `a pinned real-valued integer row emits its reified LP row`() {
        val builder = RecordingBuilder(pin = true)
        val factor = ReifiedRealLinear(
            aux = 1,
            vars = intArrayOf(0),
            intCoeffs = doubleArrayOf(0.5),
            realVars = IntArray(0),
            realCoeffs = DoubleArray(0),
            op = LinearOp.LE,
            bound = 2.5,
        )

        factor.emitLpRelaxation(builder)

        assertEquals(listOf(RecordingBuilder.RealRow(strict = false)), builder.realRows)
    }

    @Test
    fun `a wide row rounds outward while an unsupported factor emits no row`() {
        val huge = BigInteger.fromLong(Long.MAX_VALUE) * 4
        val wideBuilder = RecordingBuilder()
        val unsupportedBuilder = RecordingBuilder()

        Linear(intArrayOf(0), arrayOf(huge), LinearOp.LE, huge).emitLpRelaxation(wideBuilder)
        AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2).emitLpRelaxation(unsupportedBuilder)

        assertEquals(1, wideBuilder.realRows.size)
        assertTrue(unsupportedBuilder.integerRows.isEmpty())
        assertTrue(unsupportedBuilder.realRows.isEmpty())
    }

    @Test
    fun `a wide row reuses its rounding across emissions`() {
        val huge = BigInteger.fromLong(Long.MAX_VALUE) * 4
        val builder = RecordingBuilder()
        val projection = LinearLpProjection()
        val factor = Linear(intArrayOf(0), arrayOf(huge), LinearOp.LE, huge)

        factor.emitLpRelaxation(builder, projection)
        factor.emitLpRelaxation(builder, projection)

        assertEquals(1, projection.cachedWideRoundingCount)
        assertEquals(2, builder.realRows.size)
    }
}
