package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearOptimizationExecutionTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(SourceRevision("public-linear", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))

    @Test
    fun `an omitted affine objective cannot establish a zero optimum`() {
        val context = SourceProofContext(scope, setOf(real), emptyMap())

        val result = OpenTheoryPipeline.optimizeLinear(context.withLinearAuthority())

        assertEquals(TerminationReason.Unsupported, result.reason)
        assertNull(result.terminal)
        assertNull(result.value)
    }

    @Test
    fun `sparse source coordinates decline before witness construction`() {
        val sparse = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val context = SourceProofContext(scope, setOf(sparse), emptyMap(), mapOf(sparse to BigFraction.ONE))

        val result = OpenTheoryPipeline.optimizeLinear(context)

        assertEquals(TerminationReason.Unsupported, result.reason)
        assertNull(result.terminal)
    }

    @Test
    fun `public linear execution retains checkable limit evidence and accepted source values`() {
        val context = SourceProofContext(scope, setOf(real), mapOf("positive" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true))),
            mapOf(real to BigFraction.ONE))
        val accepted = ArrayList<BigFraction>()

        val result = OpenTheoryPipeline.optimizeLinear(context) { _, value -> accepted += value }

        val claim = assertIs<SourceClaim.Limit>(assertNotNull(result.terminal).claim)
        assertEquals(SourceEvidenceCheck.Checked, result.evidence.verify(claim, context.withLinearAuthority()))
        assertEquals(result.value, accepted.last())
        assertEquals(1L, result.stats.openTheory.openTheoryChecks)
    }

    @Test
    fun `callback failure retires the native invocation before another slice`() {
        val context = SourceProofContext(scope, setOf(real), mapOf("lower" to SourcePremise(
            SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))),
            mapOf(real to BigFraction.ONE))
        val invocation = LinearAuthorityInvocation(context, TheoryParams())

        val failure = assertFailsWith<IllegalArgumentException> {
            invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ ->
                throw IllegalArgumentException("publication failed")
            }
        }

        assertEquals("publication failed", failure.message)
        assertFailsWith<IllegalStateException> {
            invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }
        invocation.close()
    }
}
