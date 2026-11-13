package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturedRealToleranceTest {
    private val tenth = SourceTolerance(SemanticRational.of(bigIntOf(1), bigIntOf(10)))

    private fun source(vararg factors: Factor, tolerance: SourceTolerance = tenth, reals: Int = 1) = Problem(
        0, 0, emptyArray<IntDomain>(), arrayOf(*factors), numRealVars = reals,
        realLower = DoubleArray(reals) { 0.0 }, realUpper = DoubleArray(reals) { 1.0 },
        semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
            feasibilityTolerance = tolerance).resolve(),
    )

    private fun row(op: LinearOp, bound: Double, strict: Boolean = false) =
        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), op, bound, strict)

    private fun point(vararg reals: Double) = Sample(booleanArrayOf(), longArrayOf(), reals)

    @Test
    fun `a binary64 completion within the slack of a linear row should be accepted`() {
        val accepts = assertNotNull(capturedRealAcceptance(source(row(LinearOp.EQ, 0.25)), Cancellation.Never))

        assertTrue(accepts(point(0.3)))
    }

    @Test
    fun `a completion beyond the slack of a linear row should be refused`() {
        val accepts = assertNotNull(capturedRealAcceptance(source(row(LinearOp.EQ, 0.25)), Cancellation.Never))

        assertFalse(accepts(point(0.5)))
    }

    @Test
    fun `a completion beyond the slack of a real declaration should be refused`() {
        val wide = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 9.0)
        val accepts = assertNotNull(capturedRealAcceptance(source(wide), Cancellation.Never))

        assertFalse(accepts(point(1.5)))
    }

    @Test
    fun `a strict row should hold exactly at the accepted point`() {
        val accepts = assertNotNull(
            capturedRealAcceptance(source(row(LinearOp.LE, 0.25, strict = true)), Cancellation.Never),
        )

        assertFalse(accepts(point(0.25)))
    }

    @Test
    fun `a finite membership should admit a value within the slack of a member`() {
        val domain = RealFiniteDomain(0, doubleArrayOf(0.25, 0.75))
        val accepts = assertNotNull(capturedRealAcceptance(source(domain), Cancellation.Never))

        assertTrue(accepts(point(0.8)))
    }

    @Test
    fun `a finite membership should refuse a value between its members`() {
        val domain = RealFiniteDomain(0, doubleArrayOf(0.25, 0.75))
        val accepts = assertNotNull(capturedRealAcceptance(source(domain), Cancellation.Never))

        assertFalse(accepts(point(0.5)))
    }

    @Test
    fun `a nonlinear real relation should leave completion exact`() {
        val product = source(ContinuousProduct(0, 0, 1), reals = 2)

        assertNull(capturedRealAcceptance(product, Cancellation.Never))
    }

    @Test
    fun `a zero tolerance should leave completion exact`() {
        val exact = source(row(LinearOp.EQ, 0.25), tolerance = SourceTolerance())

        assertNull(capturedRealAcceptance(exact, Cancellation.Never))
    }
}
