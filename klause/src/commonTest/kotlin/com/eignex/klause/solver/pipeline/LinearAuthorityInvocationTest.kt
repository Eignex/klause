package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.TerminationReason
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
    private val scope = SourceScope(
        SourceRevision("conditioned-linear", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE),
    )

    @Test
    fun `sparse frontier should close coordinate conflicts outside exact primal admission`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val reals = (0 until 127).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val source = SourceProofContext(scope, (listOf(integer, other) + reals).toSet(), mapOf(
            "first" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2)),
                BigFraction.MINUS_ONE)),
            "second" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ofLong(-2)),
                BigFraction.MINUS_ONE)),
        ), mapOf(integer to BigFraction.ONE, other to BigFraction.ONE))

        val result = LinearAuthorityInvocation(source, TheoryParams(), boundOrdered = true).use { invocation ->
            assertNotNull(invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> })
        }

        val terminal = assertNotNull(result.terminal)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(2)), assertIs<SourceClaim.Optimal>(terminal.claim).value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        val stages = assertNotNull(result.stats.smt.completeSearchStages)
        assertEquals(2L, stages["numericalCoordinateConflicts"])
        assertEquals(1L, stages["numericalLpPreparations"])
    }

    @Test
    fun `bound frontier should preserve complete sibling proofs across slices`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        for (sense in ObjectiveSense.entries) {
            val direction = if (sense == ObjectiveSense.MINIMIZE) BigFraction.ONE else BigFraction.MINUS_ONE
            val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", sense)),
                setOf(integer, other), mapOf(
                    "first" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2) * direction),
                        BigFraction.MINUS_ONE)),
                    "second" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ofLong(-2) * direction),
                        BigFraction.MINUS_ONE)),
                ), mapOf(integer to BigFraction.ONE, other to BigFraction.ONE))

            LinearAuthorityInvocation(source, TheoryParams(), boundOrdered = true).use { invocation ->
                var result: LinearInvocationResult? = null
                repeat(32) {
                    if (result == null) result = invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { _, _ -> }
                }

                val terminal = assertNotNull(assertNotNull(result).terminal)
                assertEquals(SourceValue.Rational(BigFraction.ofLong(2) * direction),
                    assertIs<SourceClaim.Optimal>(terminal.claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
            }
        }
    }

    @Test
    fun `bound frontier should withhold a global bound for an unchecked Boolean sibling`() {
        val source = SourceProofContext(scope, setOf(bool, real), mapOf(
            "false" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE), mapOf(0 to false)),
            "true" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.ofLong(-10)), mapOf(0 to true)),
        ), mapOf(real to BigFraction.ONE))

        LinearAuthorityInvocation(source, TheoryParams(), boundOrdered = true).use { invocation ->
            invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { _, _ -> }
            invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { _, _ -> }
            val snapshot = invocation.snapshot(TerminationReason.BudgetExhausted)

            assertNotNull(snapshot.incumbent)
            assertNull(snapshot.bound)
            assertNull(snapshot.terminal)
        }
    }

    @Test
    fun `directional history should preserve exhaustive fractional sibling proofs`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        for (sense in ObjectiveSense.entries) {
            val direction = if (sense == ObjectiveSense.MINIMIZE) BigFraction.ONE else BigFraction.MINUS_ONE
            val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", sense)),
                setOf(integer, other), mapOf(
                    "first" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2) * direction),
                        BigFraction.MINUS_ONE)),
                    "second" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ofLong(-2) * direction),
                        BigFraction.MINUS_ONE)),
                ), mapOf(integer to BigFraction.ONE, other to BigFraction.ONE))

            val result = assertNotNull(LinearAuthorityInvocation(source, TheoryParams(), useBranchHistory = true).use {
                it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
            })

            val terminal = assertNotNull(result.terminal)
            val claim = assertIs<SourceClaim.Optimal>(terminal.claim)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(2) * direction), claim.value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        }
    }

    @Test
    fun `a checked incumbent should close a dominated fractional sibling`() {
        for (sense in ObjectiveSense.entries) {
            val direction = if (sense == ObjectiveSense.MINIMIZE) BigFraction.ONE else BigFraction.MINUS_ONE
            val source = SourceProofContext(
                scope.copy(objective = SourceObjective("x", sense)),
                setOf(bool, integer),
                mapOf(
                    "false" to SourcePremise(
                        SourceInequality(mapOf(integer to direction.negated()), BigFraction.MINUS_ONE),
                        mapOf(0 to false),
                    ),
                    "true" to SourcePremise(
                        SourceInequality(mapOf(integer to BigFraction.ofLong(-2) * direction), BigFraction.ofLong(-3)),
                        mapOf(0 to true),
                    ),
                ),
                mapOf(integer to BigFraction.ONE),
            )

            val result = assertNotNull(LinearAuthorityInvocation(source, TheoryParams(maxLeaves = 2)).use {
                it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
            })

            val terminal = assertNotNull(result.terminal)
            val claim = assertIs<SourceClaim.Optimal>(terminal.claim)
            assertEquals(SourceValue.Rational(direction), claim.value)
            assertEquals(TerminationReason.SearchExhausted, result.reason)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(terminal))
        }
    }

    @Test
    fun `integer strict positivity should reach an attained integer minimum`() {
        val source = SourceProofContext(
            scope,
            setOf(integer),
            mapOf(
                "positive" to SourcePremise(
                    SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
                ),
            ),
            mapOf(integer to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        val claim = assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), claim.value)
    }

    @Test
    fun `a source optimum should require both guarded Boolean branches`() {
        val source = SourceProofContext(
            scope,
            setOf(bool, real),
            mapOf(
                "false" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.MINUS_ONE,
                    ),
                    mapOf(0 to false),
                ),
                "true" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                    ),
                    mapOf(0 to true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        val claim = assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), claim.value)
    }

    @Test
    fun `incomplete branch coverage should keep the incumbent without a global optimum`() {
        val source = SourceProofContext(
            scope,
            setOf(bool, real),
            mapOf(
                "false" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.MINUS_ONE,
                    ),
                    mapOf(0 to false),
                ),
                "true" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                    ),
                    mapOf(0 to true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams(maxLeaves = 1)).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        assertNull(result?.terminal)
        assertNotNull(result?.incumbent)
        assertNull(result.bound)
    }

    @Test
    fun `an infeasible guarded branch should contribute its checked refutation to global coverage`() {
        val source = SourceProofContext(
            scope,
            setOf(bool, real),
            mapOf(
                "false" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE), mapOf(0 to false)),
                "true" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                    ),
                    mapOf(0 to true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
    }

    @Test
    fun `an attained sibling at the global limit should establish attainment`() {
        val source = SourceProofContext(
            scope,
            setOf(bool, real),
            mapOf(
                "false" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                        strict = true,
                    ),
                    mapOf(0 to false),
                ),
                "true" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                    ),
                    mapOf(0 to true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        assertIs<SourceClaim.Optimal>(assertNotNull(result?.terminal).claim)
    }

    @Test
    fun `strict branch coverage and one approach should establish a global unattained limit`() {
        val source = SourceProofContext(
            scope,
            setOf(bool, real),
            mapOf(
                "false" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.ZERO,
                        strict = true,
                    ),
                    mapOf(0 to false),
                ),
                "true" to SourcePremise(
                    SourceInequality(
                        mapOf(real to BigFraction.MINUS_ONE),
                        BigFraction.MINUS_ONE,
                    ),
                    mapOf(0 to true),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )

        val result = LinearAuthorityInvocation(source, TheoryParams()).use {
            it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _, _ -> }
        }

        val claim = assertIs<SourceClaim.Limit>(assertNotNull(result?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), claim.value)
    }

    @Test
    fun `a cancelled invocation should preserve an accepted external incumbent`() {
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
    fun `cancellation after acceptance should keep a checked global bound`() {
        val source = SourceProofContext(
            scope,
            setOf(real),
            mapOf(
                "lower" to SourcePremise(
                    SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
                ),
            ),
            mapOf(real to BigFraction.ONE),
        )
        var stop = false
        val invocation = LinearAuthorityInvocation(source, TheoryParams())

        val result = invocation.use {
            it.runSlice(Cancellation { stop }, Long.MAX_VALUE, -1) { _, _ -> stop = true }
        }

        assertNull(result?.terminal)
        assertEquals(TerminationReason.Cancelled, result?.reason)
        assertNotNull(result?.incumbent)
        val bound = assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), bound.value)
    }

    @Test
    fun `closing a paused traversal should keep its checked root bound and incumbent`() {
        val source = SourceProofContext(
            scope,
            setOf(integer),
            mapOf(
                "positive" to SourcePremise(
                    SourceInequality(mapOf(integer to BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
                ),
            ),
            mapOf(integer to BigFraction.ONE),
        )
        val invocation = LinearAuthorityInvocation(source, TheoryParams())

        val paused = invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, 1) { _, _ -> }
        invocation.close()
        val snapshot = invocation.snapshot(TerminationReason.Cancelled)

        assertNull(paused)
        assertNull(snapshot.terminal)
        assertNotNull(snapshot.incumbent)
        val bound = assertIs<SourceClaim.Bound>(assertNotNull(snapshot.bound).claim)
        assertEquals(SourceValue.Rational(BigFraction.ZERO), bound.value)
        assertEquals(TerminationReason.Cancelled, snapshot.reason)
    }
}
