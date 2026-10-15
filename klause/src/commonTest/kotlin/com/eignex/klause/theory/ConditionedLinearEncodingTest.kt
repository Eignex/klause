package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ConditionedLinearEncodingTest {
    @Test
    fun `a late encoder decline discards the complete finite cover`() {
        val product = RealProduct(0, 0, 1, 1.0, 1.0)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(product), numRealVars = 2,
            realLower = doubleArrayOf(1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        var installations = 0
        var decline: EncoderDecline? = null
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (factor !== product || target != EncodingTarget.LINEAR) selected
            else Encoder(EncoderSupport.CONDITIONED, selected.requiredIntegers) { view, encoding ->
                installations++
                if (encoding.fixedInteger(SourceIntId(0)) == bigIntOf(1)) EncoderDecline.UNSUPPORTED
                else selected.install(view, encoding)
            }
        })

        val relation = providers.prepare(source).linearTheoryModel(EncodingTarget.LINEAR,
            onDecline = { decline = it }, completeFiniteConditions = true)

        assertNull(relation)
        assertEquals(2, installations)
        assertEquals(EncoderDecline.UNSUPPORTED, decline)
    }

    @Test
    fun `a stop after the final case prevents publishing the finite cover`() {
        val product = RealProduct(0, 0, 1, 1.0, 1.0)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(product), numRealVars = 2,
            realLower = doubleArrayOf(1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        var stopped = false
        var decline: EncoderDecline? = null
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (factor !== product || target != EncodingTarget.LINEAR) selected
            else Encoder(EncoderSupport.CONDITIONED, selected.requiredIntegers) { view, encoding ->
                val result = selected.install(view, encoding)
                if (encoding.fixedInteger(SourceIntId(0)) == bigIntOf(1)) stopped = true
                result
            }
        })

        val relation = providers.prepare(source).linearTheoryModel(EncodingTarget.LINEAR,
            cancellation = Cancellation { stopped }, onDecline = { decline = it }, completeFiniteConditions = true)

        assertNull(relation)
        assertEquals(EncoderDecline.RESOURCE, decline)
    }

    @Test
    fun `a source with an open condition cannot borrow its fallback box for a cover`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(RealProduct(0, 0, 1, 1.0, 1.0)),
            openIntHi = booleanArrayOf(true), numRealVars = 2,
            realLower = doubleArrayOf(1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))

        val supported = FactorProjectionProviders.Default.prepare(source).conditionedLinearSupported(0)

        assertFalse(supported)
    }

    @Test
    fun `an oversized finite condition withholds complete linear coverage`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 4096)), arrayOf(RealProduct(0, 0, 1, 1.0, 1.0)),
            numRealVars = 2, realLower = doubleArrayOf(1.0, 0.0), realUpper = doubleArrayOf(1.0, 4096.0))

        val supported = FactorProjectionProviders.Default.prepare(source).conditionedLinearSupported(0)

        assertFalse(supported)
    }
}
