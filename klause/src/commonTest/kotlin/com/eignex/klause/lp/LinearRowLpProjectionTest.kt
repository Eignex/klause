package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearRowLpProjectionTest {

    /** [openLo] / [openHi] mark the endpoints of the fixed `[0, 10]` root box as ones the finite lane
     *  invented rather than ones the model states. */
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
    fun `unit-weight Boolean factors retain the compact LP representation`() {
        for (factor in listOf<Factor>(Clause(intArrayOf(0, 2)), Cardinality.exactlyOne(intArrayOf(0, 2)))) {
            val builder = RecordingBuilder()

            factor.emitLpRelaxation(builder)

            assertTrue(builder.booleanWeights.isNotEmpty())
            for (weights in builder.booleanWeights) assertNull(weights)
        }
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
    fun `a wide row rounds outward while an unsupported factor emits no row`() {
        val huge = bigIntOf(Long.MAX_VALUE) * bigIntOf(4)
        val wideBuilder = RecordingBuilder()
        val unsupportedBuilder = RecordingBuilder()

        Linear(intArrayOf(0), arrayOf(huge), LinearOp.LE, huge).emitLpRelaxation(wideBuilder)
        AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2).emitLpRelaxation(unsupportedBuilder)

        assertEquals(1, wideBuilder.realRows.size)
        assertTrue(unsupportedBuilder.integerRows.isEmpty())
        assertTrue(unsupportedBuilder.realRows.isEmpty())
    }

}
