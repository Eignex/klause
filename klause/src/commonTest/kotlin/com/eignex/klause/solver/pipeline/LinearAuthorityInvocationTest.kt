package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearAuthorityInvocationTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val scope = SourceScope(SourceRevision("conditioned-linear", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))

    @Test
    fun `integer strict positivity reaches an attained integer minimum`() {
        val source = SourceProofContext(scope, setOf(integer), mapOf("positive" to SourcePremise(
            SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true))),
            mapOf(integer to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        val claim = assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), claim.value)
    }

    @Test
    fun `a source optimum requires both guarded Boolean branches`() {
        val source = SourceProofContext(scope, setOf(bool, real), mapOf(
            "false" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE), mapOf(0 to false)),
            "true" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.ZERO), mapOf(0 to true))), mapOf(real to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        val claim = assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), claim.value)
    }

    @Test
    fun `incomplete branch coverage retains the incumbent without a global optimum`() {
        val source = SourceProofContext(scope, setOf(bool, real), mapOf(
            "false" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE), mapOf(0 to false)),
            "true" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.ZERO), mapOf(0 to true))), mapOf(real to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams(maxLeaves = 1)).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        assertNull(result?.terminal)
        assertNotNull(result?.incumbent)
        assertNull(result?.bound)
    }

    @Test
    fun `an infeasible guarded branch contributes its checked refutation to global coverage`() {
        val source = SourceProofContext(scope, setOf(bool, real), mapOf(
            "false" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE), mapOf(0 to false)),
            "true" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.ZERO), mapOf(0 to true))), mapOf(real to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
    }

    @Test
    fun `an attained sibling at the global limit establishes attainment`() {
        val source = SourceProofContext(scope, setOf(bool, real), mapOf(
            "false" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.ZERO, strict = true), mapOf(0 to false)),
            "true" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.ZERO), mapOf(0 to true))), mapOf(real to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
    }

    @Test
    fun `strict branch coverage and one approach establish a global unattained limit`() {
        val source = SourceProofContext(scope, setOf(bool, real), mapOf(
            "false" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.ZERO, strict = true), mapOf(0 to false)),
            "true" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE), mapOf(0 to true))), mapOf(real to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        val claim = assertIs<SourceClaim.Limit>(assertNotNull(result?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), claim.value)
    }

    @Test
    fun `cancelled invocation preserves an accepted external incumbent`() {
        val source = SourceProofContext(scope, setOf(real), emptyMap(), mapOf(real to BigFraction.ONE))
        val sample = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), exactReals = listOf(BigFraction.ONE))
        var stop = false
        val callbacks = ArrayList<Sample>()
        val invocation = LinearAuthorityInvocation(source, TheoryParams(), external = { sample })

        val result = invocation.use {
            it.runSlice(Cancellation { stop }, Long.MAX_VALUE, -1) { accepted, _ ->
                callbacks += accepted
                stop = true
            }
        }

        assertNull(result?.terminal)
        assertEquals(sample, result?.incumbent)
        assertEquals(listOf(sample), callbacks)
    }

    @Test
    fun `cancellation after acceptance retains a checked global bound`() {
        val source = SourceProofContext(scope, setOf(real), mapOf("lower" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))),
            mapOf(real to BigFraction.ONE))
        var stop = false
        val invocation = LinearAuthorityInvocation(source, TheoryParams())

        val result = invocation.use {
            it.runSlice(Cancellation { stop }, Long.MAX_VALUE, -1) { _, _ -> stop = true }
        }

        assertNull(result?.terminal)
        assertNotNull(result?.incumbent)
        val bound = assertIs<SourceClaim.Bound>(assertNotNull(result?.bound).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), bound.value)
    }
}
