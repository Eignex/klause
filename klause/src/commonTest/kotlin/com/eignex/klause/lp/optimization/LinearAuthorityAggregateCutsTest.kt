package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityAggregateCutsTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val y = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(SourceRevision("aggregation", "exact-affine"))
    private val left = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to BigFraction.ONE),
        BigFraction.ofLong(3))
    private val right = SourceInequality(mapOf(y to BigFraction.ofLong(2), real to BigFraction.MINUS_ONE),
        BigFraction.ZERO)

    @Test
    fun `automatic source pairs should strengthen a checked bound across an unbounded continuous coordinate`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real, residual), mapOf("left" to SourcePremise(mixed),
                "right" to SourcePremise(right), "bound" to SourcePremise(bound)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE, residual to BigFraction.ONE))

        for ((aggregate, selection, expected) in listOf(
            Triple(false, false, BigFraction.ofLong(-3) * BigFraction.ofLong(2).reciprocal()),
            Triple(true, false, BigFraction.MINUS_ONE), Triple(true, true, BigFraction.MINUS_ONE),
        )) {
            LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = aggregate).use { authority ->
                val result = if (selection) authority.advanceWithPointCuts(source, Cancellation.Never)
                    else authority.advance(source, Cancellation.Never, cuts = true)

                assertEquals(SourceValue.Rational(expected),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
                if (aggregate) assertTrue(CutFamily.SOURCE_AGGREGATION in result.cutBoundFamilies)
            }
        }
    }

    @Test
    fun `point selection should query the current recipe before preparing aggregate pairs`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), mapOf("left" to SourcePremise(left), "right" to SourcePremise(right)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))
        val capture = LinearAuthorityCapture(4, 10_000L, 1_000_000L, Cancellation.Never)

        val result = LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.capture = capture
            authority.advanceWithPointCuts(source, Cancellation.Never)
        }

        assertTrue(capture.records.first().cuts.isEmpty())
        assertEquals(SourceValue.Rational(BigFraction.MINUS_ONE),
            assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
    }

    @Test
    fun `a checked current optimum should bypass optional pair discovery`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("x", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), mapOf("left" to SourcePremise(left), "right" to SourcePremise(right),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE))),
            mapOf(x to BigFraction.MINUS_ONE))
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            val result = authority.advanceWithPointCuts(source, Cancellation.Never)

            assertEquals(null, authority.cutObservations()["numericalCutAggregatePairAttempts"])
            assertIs<SourceClaim.Optimal>(assertNotNull(result.evidence).claim)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.evidence)))
        }
    }

    @Test
    fun `retiring a fully selected aggregate family should preserve its numerical owner`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val triangle = (2..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = linkedMapOf(
            "left" to SourcePremise(SourceInequality(left.coefficients + (residual to BigFraction.ONE), left.rhs)),
            "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE), BigFraction.ONE)),
        )
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, real, residual) + triangle, premises,
            (triangle + x + y).associateWith { BigFraction.ONE })
        val child = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO, y to BigFraction.ZERO))))
        val capture = LinearAuthorityCapture(8, 10_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.capture = capture
            authority.advanceWithPointCuts(source, Cancellation.Never)
            val preparations = authority.preparations
            repeat(3) { authority.advanceWithPointCuts(child, Cancellation.Never) }
            assertNull(authority.cutObservations()["numericalCutSelectionRetiredInstalled"])

            val result = authority.advanceWithPointCuts(child, Cancellation.Never)

            assertEquals(1L, authority.cutObservations()["numericalCutSelectionRetiredInstalled"])
            assertEquals(preparations, authority.preparations)
            assertEquals(false, capture.records.last().cuts.single().installed)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(child.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `retiring candidates should retain their base bound across repeated pauses`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val triangle = (2..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = linkedMapOf(
            "left" to SourcePremise(SourceInequality(left.coefficients + (residual to BigFraction.ONE), left.rhs)),
            "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE), BigFraction.ONE)),
        )
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, real, residual) + triangle, premises,
            (triangle + x + y).associateWith { BigFraction.ONE })
        val child = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO, y to BigFraction.ZERO))))
        val funding = SourceLpFunding(4_000_000L, 8_000_000L).apply { grant(4_000_000L) }
        var pause = false
        var blocked = false
        val budget = SourceLpBudget(funding = funding, onWork = {
            if (pause && !blocked && it.operations > 0L) {
                blocked = true
                funding.grant(0L)
            }
        })
        LinearAuthority(budget, retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, Cancellation.Never)
            repeat(3) { authority.advanceWithPointCuts(child, Cancellation.Never) }
            val operations = budget.operations
            pause = true

            repeat(2) {
                val paused = authority.advanceWithPointCuts(child, Cancellation.Never)

                assertTrue(paused.paused)
                assertEquals(1L, authority.cutObservations()["numericalCutSelectionRetiredInstalled"])
                assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                    assertIs<SourceClaim.Bound>(assertNotNull(paused.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(child.withLinearAuthority()).verify(assertNotNull(paused.bound)))
            }

            funding.grant(4_000_000L)
            val completed = authority.advanceWithPointCuts(child, Cancellation.Never)

            assertFalse(completed.paused)
            assertEquals(operations + 1, budget.operations)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                assertIs<SourceClaim.Bound>(assertNotNull(completed.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(child.withLinearAuthority()).verify(assertNotNull(completed.bound)))
        }
    }

    @Test
    fun `an ordinary recipe should replace a retained point selected layout`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val triangle = (2..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = linkedMapOf(
            "left" to SourcePremise(SourceInequality(left.coefficients + (residual to BigFraction.ONE), left.rhs)),
            "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE), BigFraction.ONE)),
        )
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE), BigFraction.ONE))
        }
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MAXIMIZE)),
            setOf(x, y, real, residual) + triangle, premises,
            (triangle + x + y).associateWith { BigFraction.ONE })
        val child = source.atScope(source.scope.copy(condition = SourceCondition(
            upper = mapOf(x to BigFraction.ZERO, y to BigFraction.ZERO))))
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, Cancellation.Never)
            authority.advanceWithPointCuts(child, Cancellation.Never)

            val result = authority.advance(source, Cancellation.Never, cuts = true)

            assertEquals(SourceValue.Rational(BigFraction.ofLong(3)),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `installed aggregate cuts should retain their checked numerical recipe across scoped queries`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val triangle = (2..4).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val premises = linkedMapOf("left" to SourcePremise(mixed), "right" to SourcePremise(right),
            "bound" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)))
        for ((index, coordinate) in triangle.withIndex()) {
            premises["nonnegative $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.ZERO))
            premises["edge $index"] = SourcePremise(SourceInequality(
                mapOf(coordinate to BigFraction.ONE, triangle[(index + 1) % 3] to BigFraction.ONE),
                BigFraction.ONE))
        }
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real, residual) + triangle, premises,
            (triangle + x + y).associateWith { BigFraction.MINUS_ONE } + (residual to BigFraction.ONE))
        val child = source.atScope(source.scope.copy(condition = SourceCondition(upper = mapOf(x to BigFraction.ZERO))))
        val capture = LinearAuthorityCapture(4, 10_000L, 1_000_000L, Cancellation.Never)
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advance(source, Cancellation.Never, cuts = true)
            authority.capture = capture
            authority.advanceWithPointCuts(source, Cancellation.Never)
            val attempts = assertNotNull(authority.cutObservations()["numericalCutAggregatePairAttempts"])
            assertEquals(1L, authority.cutObservations()["numericalCutSelectionAdoptedInstalled"])
            assertEquals(1, capture.records.size)

            val result = authority.advanceWithPointCuts(child, Cancellation.Never)

            assertEquals(attempts, authority.cutObservations()["numericalCutAggregatePairAttempts"])
            assertEquals(2, capture.records.size)
            assertEquals(SourceValue.Rational(BigFraction.ofLong(-5) * BigFraction.ofLong(2).reciprocal()),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertTrue(CutFamily.SOURCE_AGGREGATION in result.cutBoundFamilies)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(child.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `repeated selected queries should reuse their completed pair generation`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real, residual), mapOf("left" to SourcePremise(mixed),
                "right" to SourcePremise(right), "bound" to SourcePremise(bound)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE, residual to BigFraction.ONE))
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, Cancellation.Never)
            val attempts = assertNotNull(authority.cutObservations()["numericalCutAggregatePairAttempts"])

            val result = authority.advanceWithPointCuts(source, Cancellation.Never)

            assertEquals(attempts, authority.cutObservations()["numericalCutAggregatePairAttempts"])
            assertEquals(SourceValue.Rational(BigFraction.MINUS_ONE),
                assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
            assertEquals(SourceEvidenceCheck.Checked,
                SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
        }
    }

    @Test
    fun `point directed pair preparation should retain a later separating proposal before its pool fills`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val continuous = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, a, b, continuous), linkedMapOf(
            "first left" to SourcePremise(SourceInequality(
                mapOf(a to BigFraction.ofLong(2), continuous to BigFraction.ONE), BigFraction.ofLong(3))),
            "first right" to SourcePremise(SourceInequality(
                mapOf(b to BigFraction.ofLong(2), continuous to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "left" to SourcePremise(left), "right" to SourcePremise(right)))
        val point = mapOf(a to 0.0, b to 0.0, continuous to 0.0, x to 0.75, y to 0.75, real to 1.5)

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            maximumCandidates = 1, point = point).single()

        assertEquals(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), cut.inequality.coefficients)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `point directed aggregate bounds should combine different established endpoint sides`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val mixed = SourceInequality(left.coefficients + mapOf(
            residual to BigFraction.MINUS_ONE, other to BigFraction.MINUS_ONE), left.rhs)
        val source = SourceProofContext(scope, setOf(x, y, real, residual, other), linkedMapOf(
            "left" to SourcePremise(mixed), "right" to SourcePremise(right),
            "lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE),
                BigFraction.ofLong(2))),
            "other lower" to SourcePremise(SourceInequality(mapOf(other to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
            "other upper" to SourcePremise(SourceInequality(mapOf(other to BigFraction.ONE),
                BigFraction.ofLong(2))),
        ))
        val point = mapOf(x to 1.25, y to 1.25, real to 2.5, residual to 2.0, other to 0.0)

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never, point = point).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE,
            other to BigFraction.MINUS_ONE), BigFraction.ofLong(2)), cut.inequality)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `a farther stated endpoint should separate when nearer aggregate rounding does not`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(5),
                    otherReal to BigFraction.ONE, real to BigFraction.ofLong(4L * sign)), BigFraction.ofLong(7))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(5),
                    otherReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                    if (sign < 0) BigFraction.ZERO else BigFraction.ONE)),
                "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE),
                    if (sign < 0) BigFraction.ONE else BigFraction.ZERO)),
            ))

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                maximumCandidates = 1, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = 2),
                point = mapOf(x to 1.88, y to 0.0, real to -0.6 * sign, otherReal to 0.0))

            val cut = cuts.single()
            assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE,
                real to BigFraction.ofLong(4L * sign) * BigFraction.ofLong(3).reciprocal()), BigFraction.ONE),
                cut.inequality)
            assertEquals(setOf(0, 1, if (sign < 0) 2 else 3), cut.owners)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `interrupting a preferred endpoint derivation should withhold its partial proposal`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(5),
                otherReal to BigFraction.ONE, real to BigFraction.ofLong(-4)), BigFraction.ofLong(7))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(5),
                otherReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ONE)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, _ -> if (name == "AggregatePreferredSideAttempts") stopped = true },
            maximumCandidates = 1, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = 2),
            point = mapOf(x to 1.88, y to 0.0, real to 0.6, otherReal to 0.0))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `an alternative endpoint should share the spent generation work allowance`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(5),
                otherReal to BigFraction.ONE, real to BigFraction.ofLong(-4)), BigFraction.ofLong(7))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(5),
                otherReal to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ONE)),
        ))
        val point = mapOf(x to 1.88, y to 0.0, real to 0.6, otherReal to 0.0)
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = 2)
        var work = 0L
        var beforePreferred = 0L
        LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never, observe = { name, count ->
            if (name == "AggregateWork") work += count
            if (name == "AggregatePreferredSideAttempts") beforePreferred = work
        }, maximumCandidates = 1, limits = limits, point = point)
        assertTrue(beforePreferred > 0L)
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            maximumCandidates = 1, limits = limits.copy(work = beforePreferred), point = point)

        assertTrue(cuts.isEmpty())
        assertTrue((observations["AggregateWork"] ?: 0L) <= beforePreferred)
    }


    @Test
    fun `integer recipes should strengthen pure numerical bounds only when enabled`() {
        for (paired in listOf(false, true)) {
            val premises = if (paired) linkedMapOf(
                "first" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    y to BigFraction.ONE), BigFraction.ofLong(2))),
                "second" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                    y to BigFraction.ofLong(2)), BigFraction.ofLong(2))),
            ) else linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
                "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            )
            val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
                setOf(x, y), premises, mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))
            for (enabled in listOf(false, true)) {
                val result = LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = enabled).use {
                    it.advanceWithPointCuts(source, Cancellation.Never)
                }

                val expected = if (enabled) BigFraction.MINUS_ONE else if (paired)
                    BigFraction.ofLong(-4) * BigFraction.ofLong(3).reciprocal()
                    else BigFraction.ofLong(-3) * BigFraction.ofLong(2).reciprocal()
                assertEquals(SourceValue.Rational(expected),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
                if (enabled) assertTrue((if (paired) CutFamily.SOURCE_AGGREGATION else CutFamily.AGGREGATION_MIR)
                    in result.cutBoundFamilies)
            }
        }
    }

    @Test
    fun `integer row recipes should cite either stated integer endpoint`() {
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    y to BigFraction.ofLong(3L * sign)), BigFraction.ofLong(3))),
                "endpoint" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(-2L * sign)),
                    BigFraction.ZERO)),
            ))

            val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
                point = mapOf(x to 0.75, y to 0.5 * sign)).single()

            assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(sign)),
                BigFraction.ONE), cut.inequality)
            assertEquals(setOf(0, 1), cut.owners)
            assertEquals(CutFamily.AGGREGATION_MIR, cut.family)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            assertTrue(LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never).isEmpty())
        }
    }

    @Test
    fun `integer endpoint lookup should ignore unrelated bounds within the production allowance`() {
        val premises = linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        )
        val coordinates = mutableSetOf(x, y)
        repeat(700) { index ->
            val id = SourceCoordinate(SourceCoordinate.Kind.Integer, index + 2)
            coordinates += id
            premises["unrelated $index"] = SourcePremise(SourceInequality(
                mapOf(id to BigFraction.MINUS_ONE), BigFraction.ZERO))
        }
        val source = SourceProofContext(scope, coordinates, premises)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = limits, point = mapOf(x to 0.75, y to 0.5)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(0, 1), cut.owners)
        assertEquals(1L, observations["AggregateIntegerRowNormalized"])
        assertEquals(1L, observations["AggregateIntegerBoundScanComplete"])
        assertEquals(2L, observations["AggregateIntegerBoundCoordinates"])
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `integer endpoint lookup should reuse checked coordinates across row recipes`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "second" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                y to BigFraction.ofLong(2)), BigFraction.ofLong(3))),
            "lower x" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "lower y" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.6, y to 0.6))

        assertEquals(2L, observations["AggregateIntegerRowEligible"])
        assertEquals(1L, observations["AggregateIntegerBoundScanComplete"])
        assertEquals(2L, observations["AggregateIntegerBoundCoordinates"])
        assertTrue(cuts.all { cut -> SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)) ==
                SourceEvidenceCheck.Checked })
    }

    @Test
    fun `integer endpoint lookup should extend checked coordinates without changing source owners`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val source = SourceProofContext(scope, setOf(x, y, a, b), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower y" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "second" to SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower b" to SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.5, a to 0.75, b to 0.5))

        assertEquals(setOf(0, 1), cuts.first().owners)
        assertEquals(setOf(2, 3), cuts[1].owners)
        assertEquals(2L, observations["AggregateIntegerBoundScanComplete"])
        assertEquals(4L, observations["AggregateIntegerBoundCoordinates"])
        assertTrue(cuts.all { cut -> SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)) ==
                SourceEvidenceCheck.Checked })
    }

    @Test
    fun `interrupting an integer endpoint scan should withhold its partially retained bounds`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        var stopped = false
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateIntegerBoundRetained") stopped = true
            }, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.75, y to 0.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
        assertNull(observations["AggregateIntegerBoundScanComplete"])
        assertNull(observations["AggregateIntegerBoundCoordinates"])
    }

    @Test
    fun `an empty half recipe should leave production allowance for a later useful row`() {
        val unit = (2 until 27).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val premises = linkedMapOf("empty" to SourcePremise(SourceInequality(
            unit.associateWith { BigFraction.ONE }, BigFraction.ofLong(25))))
        unit.forEachIndexed { index, id ->
            premises["l$index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
        }
        premises["useful"] = SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
            y to BigFraction.ofLong(3)), BigFraction.ofLong(3)))
        premises["lower y"] = SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO))
        val coordinates = (unit + listOf(x, y)).toMutableSet()
        repeat(900) { index ->
            val id = SourceCoordinate(SourceCoordinate.Kind.Integer, index + 27)
            coordinates += id
            premises["u$index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
        }
        val source = SourceProofContext(scope, coordinates, premises)
        val point = unit.associateWith { 0.0 } + mapOf(x to 0.75, y to 0.5)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = limits, point = point).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(26, 27), cut.owners)
        assertTrue((observations["AggregateHalfPreviewNoIntegerActivity"] ?: 0L) > 0L)
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `an empty half preview should respect either stated endpoint direction`() {
        for (sign in listOf(-1L, 1L)) {
            val coefficient = BigFraction.ofLong(sign)
            val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to coefficient, y to coefficient),
                    if (sign > 0L) BigFraction.ofLong(2) else BigFraction.ZERO)),
                "x side" to SourcePremise(SourceInequality(mapOf(x to coefficient.negated()), BigFraction.ZERO)),
                "y side" to SourcePremise(SourceInequality(mapOf(y to coefficient.negated()), BigFraction.ZERO)),
            ))
            val observations = mutableMapOf<String, Long>()

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 0.0))

            assertTrue(cuts.isEmpty())
            assertEquals(1L, observations["AggregateHalfPreviewNoIntegerActivity"])
            assertEquals(1L, observations["AggregateHalfNoIntegerActivity"])
        }
    }

    @Test
    fun `an empty half preview should preserve missing endpoint refusal`() {
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y), mapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(sign),
                    y to BigFraction.ofLong(sign)), BigFraction.ofLong(2))),
            ))
            val observations = mutableMapOf<String, Long>()

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 0.0))

            assertTrue(cuts.isEmpty())
            assertNull(observations["AggregateHalfPreviewNoIntegerActivity"])
            assertEquals(1L, observations["AggregateHalfMissingIntegerBounds"])
        }
    }

    @Test
    fun `interrupting a half preview should withhold the unfinished recipe`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE),
                BigFraction.ofLong(2))),
            "lower x" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "lower y" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, _ -> if (name == "AggregateHalfPreviewCoordinates") stopped = true },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 0.0))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `integer row normalization should retain nonunit rational scaling`() {
        val half = BigFraction.ofLong(2).reciprocal()
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                y to BigFraction.ofLong(3) * half), BigFraction.ofLong(3) * half)),
            "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.75, y to 0.5)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(1L, observations["AggregateIntegerRowScaled"])
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `completed integer endpoint batches should preserve allowance for a cross batch row`() {
        val groups = (0 until 2).map { group ->
            (0 until 25).map { index -> SourceCoordinate(SourceCoordinate.Kind.Integer, group * 25 + index) }
        }
        val premises = linkedMapOf<String, SourcePremise>()
        groups.forEachIndexed { group, ids ->
            premises["row $group"] = SourcePremise(SourceInequality(ids.associateWith { BigFraction.ONE },
                BigFraction.ofLong(25)))
            ids.forEachIndexed { index, id ->
                premises["l$group $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                    BigFraction.ZERO))
                premises["u$group $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.ONE),
                    BigFraction.ONE))
            }
        }
        val a = groups[0].first()
        val b = groups[1].first()
        premises["useful"] = SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
            b to BigFraction.ofLong(3)), BigFraction.ofLong(3)))
        val coordinates = groups.flatten().toMutableSet()
        repeat(700) { index ->
            val id = SourceCoordinate(SourceCoordinate.Kind.Integer, index + 50)
            coordinates += id
            premises["extra $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
        }
        val source = SourceProofContext(scope, coordinates, premises)
        val point = groups.flatten().associateWith { 0.0 } + mapOf(a to 0.75, b to 0.5)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = limits, point = point).single()

        assertEquals(SourceInequality(mapOf(a to BigFraction.ONE, b to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(102, 52), cut.owners)
        assertEquals(2L, observations["AggregateIntegerBoundSourceScanComplete"])
        assertEquals(2L, observations["AggregateIntegerBoundScanComplete"])
        assertEquals(50L, observations["AggregateIntegerBoundCoordinates"])
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `stopping before integer endpoint batch publication should withhold its recipe`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateIntegerBoundSourceScanComplete") stopped = true
            }, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.75, y to 0.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
        assertEquals(1L, observations["AggregateIntegerBoundSourceScanComplete"])
        assertNull(observations["AggregateIntegerBoundScanComplete"])
    }

    @Test
    fun `singleton inspection reuse should leave production allowance for a later disjoint row`() {
        val groups = (0 until 3).map { group ->
            (0 until 25).map { index -> SourceCoordinate(SourceCoordinate.Kind.Integer, group * 25 + index + 2) }
        }
        val premises = linkedMapOf<String, SourcePremise>()
        groups.forEachIndexed { group, ids ->
            premises["row $group"] = SourcePremise(SourceInequality(ids.associateWith { BigFraction.ONE },
                BigFraction.ofLong(25)))
            ids.forEachIndexed { index, id ->
                premises["l$group $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                    BigFraction.ZERO))
                premises["u$group $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.ONE),
                    BigFraction.ONE))
            }
        }
        premises["useful"] = SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
            y to BigFraction.ofLong(3)), BigFraction.ofLong(3)))
        premises["lower y"] = SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO))
        val coordinates = (groups.flatten() + listOf(x, y)).toMutableSet()
        repeat(700) { index ->
            val id = SourceCoordinate(SourceCoordinate.Kind.Integer, index + 77)
            coordinates += id
            premises["extra $index"] = SourcePremise(SourceInequality(mapOf(id to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
        }
        val source = SourceProofContext(scope, coordinates, premises)
        val point = groups.flatten().associateWith { 0.0 } + mapOf(x to 0.75, y to 0.5)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = limits, point = point)

        val cut = assertNotNull(cuts.find { it.owners == setOf(153, 154) })
        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(1L, observations["AggregateIntegerBoundSnapshotComplete"])
        assertTrue((observations["AggregateIntegerBoundSnapshotReuseAttempts"] ?: 0L) >= 3L)
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `cancelling a reused singleton scan should withhold the unfinished recipe`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val source = SourceProofContext(scope, setOf(x, y, a, b), linkedMapOf(
            "empty" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE),
                BigFraction.ofLong(2))),
            "lower x" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "lower y" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "useful" to SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower b" to SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateIntegerBoundSnapshotReuseAttempts") stopped = true
            }, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.0, y to 0.0, a to 0.75, b to 0.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
        assertEquals(1L, observations["AggregateIntegerBoundScanComplete"])
        assertNull(observations["AggregateIntegerRowEligible"])
    }

    @Test
    fun `an integral unit coefficient should preserve the checked rounded row for either sign`() {
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(sign),
                    y to BigFraction.ofLong(2)), BigFraction.ofLong(3))),
                "side" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(-sign)), BigFraction.ZERO)),
            ))
            val observations = mutableMapOf<String, Long>()

            val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 1.5)).single()

            assertEquals(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE), cut.inequality)
            assertEquals(setOf(0, 1), cut.owners)
            assertEquals(1L, observations["AggregateUnitScaleNormalization"])
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `integral normalization without a unit coefficient should retain its common factor`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(4)), BigFraction.ofLong(5))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 1.25)).single()

        assertEquals(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE), cut.inequality)
        assertNull(observations["AggregateUnitScaleNormalization"])
        assertEquals(1L, observations["AggregateIntegerRowScaled"])
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `cancelling unit scale normalization should withhold the unfinished row`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                y to BigFraction.ofLong(2)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, _ -> if (name == "AggregateUnitScaleNormalization") stopped = true },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = mapOf(x to 0.0, y to 1.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `integer pair recipes should combine pure rows without endpoint premises`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "first" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ONE), BigFraction.ofLong(2))),
            "second" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE,
                y to BigFraction.ofLong(2)), BigFraction.ofLong(2))),
        ))

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 2.0 / 3.0, y to 2.0 / 3.0)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(0, 1), cut.owners)
        assertEquals(CutFamily.SOURCE_AGGREGATION, cut.family)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `integer row recipes should decline a domain-only endpoint`() {
        val source = SourceProofContext(scope.copy(condition = SourceCondition(lower = mapOf(y to BigFraction.ZERO))),
            setOf(x, y), mapOf("row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3)))))

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.5))

        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `integer row recipes should retain guarded and pinned source owners`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val pin = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope, setOf(x, y, bool, pin), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3)), mapOf(0 to true)),
            "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO),
                guards = emptyMap(), integerPins = mapOf(pin to BigFraction.ONE)),
        ))
        val established = SourceCondition(bools = mapOf(0 to true),
            lower = mapOf(pin to BigFraction.ONE), upper = mapOf(pin to BigFraction.ONE))
        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.5)).single()

        for (condition in listOf(established, SourceCondition(lower = established.lower, upper = established.upper),
            SourceCondition(bools = established.bools, lower = established.lower), established)) {
            val context = source.atScope(scope.copy(condition = condition))
            val check = SourceEvidenceVerifier(context).verify(
                SourceEvidence(context.scope, SourceClaim.Consequence(cut.inequality), cut.derivation))

            if (condition == established) assertEquals(SourceEvidenceCheck.Checked, check)
            else assertIs<SourceEvidenceCheck.Rejected>(check)
        }
    }

    @Test
    fun `integer row recipes should rebuild changed source generations`() {
        LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            for (lower in listOf(0L, 2L, 0L)) {
                val currentScope = scope.copy(revision = SourceRevision("integer-row-$lower", "exact-affine"),
                    objective = SourceObjective("sum", ObjectiveSense.MINIMIZE))
                val source = SourceProofContext(currentScope, setOf(x, y), linkedMapOf(
                    "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                        y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
                    "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE),
                        BigFraction.ofLong(lower))),
                ), mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))

                val result = authority.advanceWithPointCuts(source, Cancellation.Never)

                assertEquals(SourceValue.Rational(BigFraction.ofLong(if (lower == 0L) -1 else -2)),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
            }
        }
    }

    @Test
    fun `integer recipe interruption should withhold its partial proposal`() {
        val source = SourceProofContext(scope, setOf(x, y), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                y to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, _ -> if (name == "AggregateIntegerRowAttempts") stopped = true },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `integer recipes should share work and allocation with continuous aggregation`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val source = SourceProofContext(scope, setOf(x, y, a, b, real), linkedMapOf(
            "row" to SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "left" to SourcePremise(left), "right" to SourcePremise(right),
        ))
        val point = mapOf(x to 0.75, y to 0.75, real to 1.5, a to 0.75, b to 0.5)
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits
        var work = 0L
        var allocation = 0L
        var beforeWork = 0L
        var beforeAllocation = 0L
        val complete = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count ->
                if (name == "AggregateWork") work += count
                if (name == "AggregateAllocation") allocation += count
                if (name == "AggregateIntegerRowAttempts") {
                    beforeWork = work
                    beforeAllocation = allocation
                }
            }, limits = limits, point = point)
        assertEquals(2, complete.size)
        assertTrue(beforeWork > 0L && beforeAllocation > 0L)

        for (limited in listOf(limits.copy(work = beforeWork), limits.copy(allocation = beforeAllocation))) {
            val observations = mutableMapOf<String, Long>()
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = limited, point = point)

            assertTrue(cuts.isEmpty())
            assertTrue((observations["AggregateWork"] ?: 0L) <= limited.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limited.allocation)
        }
    }

    @Test
    fun `integer recipes should retain at most the existing producer cut cap`() {
        val premises = linkedMapOf<String, SourcePremise>()
        val point = linkedMapOf<SourceCoordinate, Double>()
        repeat(17) { index ->
            val a = SourceCoordinate(SourceCoordinate.Kind.Integer, index * 2)
            val b = SourceCoordinate(SourceCoordinate.Kind.Integer, index * 2 + 1)
            premises["row $index"] = SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3)))
            premises["lower $index"] = SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
            point[a] = 0.75
            point[b] = 0.5
        }
        val source = SourceProofContext(scope, point.keys, premises)

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits, point = point)

        assertEquals(16, cuts.size)
        assertTrue(cuts.all { cut -> SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)) ==
                SourceEvidenceCheck.Checked })
    }

    @Test
    fun `integer recipes should withhold coverage beyond the existing input row cap`() {
        val premises = linkedMapOf<String, SourcePremise>()
        val point = linkedMapOf<SourceCoordinate, Double>()
        repeat(49) { index ->
            val a = SourceCoordinate(SourceCoordinate.Kind.Integer, index * 2)
            val b = SourceCoordinate(SourceCoordinate.Kind.Integer, index * 2 + 1)
            premises["row $index"] = SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3)))
            premises["lower $index"] = SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE),
                BigFraction.ZERO))
            point[a] = if (index == 48) 0.75 else 0.0
            point[b] = if (index == 48) 0.5 else 0.0
        }
        val source = SourceProofContext(scope, point.keys, premises)
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count }, point = point)

        assertTrue(cuts.isEmpty())
        assertEquals(1L, observations["AggregateIntegerRowLimit"])
        assertEquals(1L, observations["AggregatePairIncomplete"])
    }

    @Test
    fun `integer row recipes should exclude Boolean activity from integer rounding`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val source = SourceProofContext(scope, setOf(x, bool), mapOf(
            "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                bool to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
        ))

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, bool to 0.5))

        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `integer row recipes should retain later supported rows after a scalar refusal`() {
        val a = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val b = SourceCoordinate(SourceCoordinate.Kind.Integer, 3)
        val large = BigFraction.of(BIG_ONE shl 512, BIG_ONE)
        val source = SourceProofContext(scope, setOf(x, y, a, b), linkedMapOf(
            "wide" to SourcePremise(SourceInequality(mapOf(x to large, y to BigFraction.ONE),
                BigFraction.ofLong(3))),
            "row" to SourcePremise(SourceInequality(mapOf(a to BigFraction.ofLong(2),
                b to BigFraction.ofLong(3)), BigFraction.ofLong(3))),
            "lower" to SourcePremise(SourceInequality(mapOf(b to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.0, y to 0.0, a to 0.75, b to 0.5)).single()

        assertEquals(SourceInequality(mapOf(a to BigFraction.ONE, b to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(1, 2), cut.owners)
        assertEquals(1L, observations["AggregateIntegerRowScalarLimit"])
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `integer row recipes should preserve the explicit aggregate row minimum`() {
        val row = SourceInequality(mapOf(x to BigFraction.ofLong(2), y to BigFraction.ofLong(3)),
            BigFraction.ofLong(3))

        val cut = LinearAuthorityAggregateCuts.derive(listOf(LinearAuthorityAggregateTerm(0, "row", row)),
            Cancellation.Never)

        assertNull(cut)
    }

    @Test
    fun `integer side substitution should strengthen the checked numerical bound`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
            ), mapOf(x to BigFraction.ofLong(-2), y to BigFraction.MINUS_ONE))

        val result = LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, Cancellation.Never)
        }

        assertEquals(SourceValue.Rational(BigFraction.ofLong(-2)),
            assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
        assertTrue(CutFamily.AGGREGATION_MIR in result.cutBoundFamilies)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
    }

    @Test
    fun `integer side substitution should separate either sign of an odd coefficient`() {
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3L * sign),
                    real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(if (sign > 0) 2 else 1))),
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                    BigFraction.ofLong(if (sign > 0) 0 else 1))),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE),
                    BigFraction.ofLong(if (sign > 0) 1 else 0))),
            ))

            val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                point = mapOf(x to 0.75 * sign, y to (if (sign > 0) 0.875 else 0.375), real to -0.25)).single()

            assertEquals(SourceInequality(mapOf(x to BigFraction.ofLong(if (sign > 0) 2 else -1),
                y to BigFraction.ONE), BigFraction.ofLong(if (sign > 0) 2 else 1)), cut.inequality)
            assertEquals(setOf(0, 1, 3), cut.owners)
            assertEquals(CutFamily.AGGREGATION_MIR, cut.family)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `integer side scoring should remove strictness belonging only to the replaced lower bound`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                real to BigFraction.ONE), BigFraction.ofLong(2))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                BigFraction.ZERO, strict = true)),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(4))),
        ))
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            point = mapOf(x to 3.5, y to -3.25, real to -8.5))

        assertTrue(cuts.isEmpty())
        assertEquals(1L, observations["AggregateHalfSideScans"])
        assertNull(observations["AggregateHalfSideAttempts"])
    }

    @Test
    fun `integer side substitution should retain upper premise guards and pins`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val pin = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope, setOf(x, y, real, bool, pin), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                real to BigFraction.ONE), BigFraction.ofLong(2))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
                mapOf(0 to true), mapOf(pin to BigFraction.ONE)),
        ))
        val established = SourceCondition(bools = mapOf(0 to true),
            lower = mapOf(pin to BigFraction.ONE), upper = mapOf(pin to BigFraction.ONE))
        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            point = mapOf(x to 0.75, y to 0.875, real to -0.25)).single()

        for (condition in listOf(established, SourceCondition(lower = established.lower, upper = established.upper),
            SourceCondition(bools = established.bools, lower = established.lower), established)) {
            val context = source.atScope(scope.copy(condition = condition))
            val check = SourceEvidenceVerifier(context).verify(
                SourceEvidence(context.scope, SourceClaim.Consequence(cut.inequality), cut.derivation))

            if (condition == established) assertEquals(SourceEvidenceCheck.Checked, check)
            else assertIs<SourceEvidenceCheck.Rejected>(check)
        }
    }

    @Test
    fun `integer side substitution should release the replaced lower premise`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val source = SourceProofContext(scope.copy(condition = SourceCondition(bools = mapOf(0 to false))),
            setOf(x, y, real, bool), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE),
                    BigFraction.ZERO, strict = true), mapOf(0 to true)),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(3))),
            ))

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            point = mapOf(x to 2.75, y to -2.125, real to -6.25)).single()

        assertEquals(setOf(0, 1, 3), cut.owners)
        assertEquals(SourceInequality(mapOf(x to BigFraction.ofLong(2), y to BigFraction.ONE),
            BigFraction.ofLong(3)), cut.inequality)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(source.scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `integer side substitution should rebuild a changed upper premise`() {
        for (upper in listOf(1L, 2L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(upper))),
            ))

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                point = mapOf(x to 0.75, y to 0.875, real to -0.25))

            if (upper == 1L) {
                val cut = cuts.single()
                assertEquals(BigFraction.ofLong(2), cut.inequality.rhs)
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                    SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            } else assertTrue(cuts.isEmpty())
        }
    }

    @Test
    fun `integer side interruption should withhold its partial proof`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                real to BigFraction.ONE), BigFraction.ofLong(2))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, _ -> if (name == "AggregateHalfSideAttempts") stopped = true },
            point = mapOf(x to 0.75, y to 0.875, real to -0.25))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `integer side substitution should share spent generation work and allocation`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                real to BigFraction.ONE), BigFraction.ofLong(2))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(2),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
        ))
        val point = mapOf(x to 0.75, y to 0.875, real to -0.25)
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits
        var work = 0L
        var allocation = 0L
        var beforeWork = 0L
        var beforeAllocation = 0L
        val complete = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count ->
                if (name == "AggregateWork") work += count
                if (name == "AggregateAllocation") allocation += count
                if (name == "AggregateHalfSideAttempts") {
                    beforeWork = work
                    beforeAllocation = allocation
                }
            }, point = point)
        assertEquals(1, complete.size)
        assertTrue(beforeWork > 0L && beforeAllocation > 0L)

        for (limited in listOf(limits.copy(work = beforeWork), limits.copy(allocation = beforeAllocation))) {
            val observations = mutableMapOf<String, Long>()
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = limited, point = point)

            assertTrue(cuts.isEmpty())
            assertTrue((observations["AggregateWork"] ?: 0L) <= limited.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limited.allocation)
        }
    }

    @Test
    fun `integer side scoring should build one separating substitution among several coordinates`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope, setOf(x, y, z, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                z to BigFraction.ofLong(2), real to BigFraction.ONE), BigFraction.ofLong(2))),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
            "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "y upper" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE)),
        ))
        val observations = mutableMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            point = mapOf(x to 0.8, y to 0.6, z to -0.1, real to -0.2)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ofLong(2), y to BigFraction.ONE,
            z to BigFraction.ONE), BigFraction.ofLong(2)), cut.inequality)
        assertEquals(setOf(0, 1, 3, 4), cut.owners)
        assertEquals(1L, observations["AggregateHalfSideAttempts"])
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `integer point sides should combine upper complements into a checked separating cut`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope,
            setOf(x, y, z, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    z to BigFraction.ofLong(2), real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
                "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "y upper" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE)),
            ))
        val point = mapOf(x to 0.75, y to 0.75, z to 0.25, real to -0.75)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            maximumCandidates = 1, limits = limits, point = point).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ofLong(2), y to BigFraction.ofLong(2),
            z to BigFraction.ONE), BigFraction.ofLong(3)), cut.inequality)
        assertEquals(setOf(0, 1, 3, 5), cut.owners)
        assertEquals(CutFamily.AGGREGATION_MIR, cut.family)
        assertEquals(1L, observations["AggregateHalfPointEligible"])
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `integer point sides should withhold an interrupted multi-coordinate choice`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope,
            setOf(x, y, z, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    z to BigFraction.ofLong(2), real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
                "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "y upper" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE)),
            ))
        val point = mapOf(x to 0.75, y to 0.75, z to 0.25, real to -0.75)
        val observations = mutableMapOf<String, Long>()
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateHalfPointScans") stopped = true
            }, point = point)

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
        assertNull(observations["AggregateHalfPointComplete"])
        assertNull(observations["AggregateHalfPointAttempts"])
    }

    @Test
    fun `integer point sides should share the generation work and allocation`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope,
            setOf(x, y, z, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    z to BigFraction.ofLong(2), real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
                "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "y upper" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ONE), BigFraction.ONE)),
            ))
        val point = mapOf(x to 0.75, y to 0.75, z to 0.25, real to -0.75)
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits
        var work = 0L
        var allocation = 0L
        var beforeWork = 0L
        var beforeAllocation = 0L
        val complete = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count ->
                if (name == "AggregateWork") work += count
                if (name == "AggregateAllocation") allocation += count
                if (name == "AggregateHalfPointAttempts") {
                    beforeWork = work
                    beforeAllocation = allocation
                }
            }, point = point)
        assertEquals(1, complete.size)
        assertTrue(beforeWork > 0L && beforeAllocation > 0L)

        for (limited in listOf(limits.copy(work = beforeWork), limits.copy(allocation = beforeAllocation))) {
            val observations = mutableMapOf<String, Long>()
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = limited, point = point)

            assertTrue(cuts.isEmpty())
            assertTrue((observations["AggregateWork"] ?: 0L) <= limited.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limited.allocation)
        }
    }

    @Test
    fun `integer point sides should require each selected upper endpoint as a source premise`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope.copy(condition = SourceCondition(upper = mapOf(y to BigFraction.ONE))),
            setOf(x, y, z, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(3),
                    z to BigFraction.ofLong(2), real to BigFraction.ONE), BigFraction.ofLong(2))),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(3))),
                "x lower" to SourcePremise(SourceInequality(mapOf(x to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "x upper" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE)),
                "y lower" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ))
        val point = mapOf(x to 0.75, y to 0.75, z to 0.25, real to -0.75)

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never, point = point)

        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `half aggregation should strengthen the checked numerical bound`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.ONE), BigFraction.ONE)),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            ), mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))

        val result = LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = true).use { authority ->
            authority.advanceWithPointCuts(source, Cancellation.Never)
        }

        assertEquals(SourceValue.Rational(BigFraction.MINUS_ONE),
            assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
        assertTrue(CutFamily.AGGREGATION_MIR in result.cutBoundFamilies)
        assertEquals(SourceEvidenceCheck.Checked,
            SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
    }

    @Test
    fun `half aggregation should cite the stated lower or upper integer side`() {
        for (sign in listOf(-1L, 1L)) {
            val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.ONE), BigFraction.ONE)),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3L * sign),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(-2L * sign)),
                    BigFraction.ZERO)),
            ))

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = 2),
                point = mapOf(x to 0.75, y to 0.5 * sign, real to -0.5))

            val cut = cuts.single()
            assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(sign)),
                BigFraction.ONE), cut.inequality)
            assertEquals(setOf(0, 1, 2), cut.owners)
            assertEquals(CutFamily.AGGREGATION_MIR, cut.family)
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            assertTrue(LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never).isEmpty())
        }
    }

    @Test
    fun `half aggregation should decline an odd coefficient without a stated integer bound`() {
        val source = SourceProofContext(scope.copy(condition = SourceCondition(lower = mapOf(y to BigFraction.ZERO))),
            setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.ONE), BigFraction.ONE)),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            ))
        val observations = mutableMapOf<String, Long>()

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            point = mapOf(x to 0.75, y to 0.5, real to -0.5))

        assertTrue(cuts.isEmpty())
        assertEquals(1L, observations["AggregateHalfMissingIntegerBounds"])
    }

    @Test
    fun `interrupting half aggregation should withhold its partial proposal`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.ONE), BigFraction.ONE)),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, _ -> if (name == "AggregateHalfAttempts") stopped = true },
            point = mapOf(x to 0.75, y to 0.5, real to -0.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `half aggregation should share spent generation work and allocation`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.ONE), BigFraction.ONE)),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO)),
        ))
        val point = mapOf(x to 0.75, y to 0.5, real to -0.5)
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits
        var work = 0L
        var allocation = 0L
        var beforeWork = 0L
        var beforeAllocation = 0L
        val complete = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count ->
                if (name == "AggregateWork") work += count
                if (name == "AggregateAllocation") allocation += count
                if (name == "AggregateHalfAttempts") {
                    beforeWork = work
                    beforeAllocation = allocation
                }
            }, limits = limits, point = point)
        assertEquals(1, complete.size)
        assertTrue(beforeWork > 0L && beforeAllocation > 0L)

        for (limited in listOf(limits.copy(work = beforeWork), limits.copy(allocation = beforeAllocation))) {
            val observations = mutableMapOf<String, Long>()
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
                limits = limited, point = point)

            assertTrue(cuts.isEmpty())
            assertTrue((observations["AggregateWork"] ?: 0L) <= limited.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limited.allocation)
        }
    }

    @Test
    fun `half aggregation should rebuild integer bound premises for each generation`() {
        for (rhs in listOf(0L, 2L, 0L)) {
            val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
                "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.ONE), BigFraction.ONE)),
                "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                    real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
                "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE),
                    BigFraction.ofLong(rhs))),
            ))

            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                point = mapOf(x to 0.75, y to 0.5, real to -0.5))

            if (rhs == 0L) {
                val cut = cuts.single()
                assertEquals(BigFraction.ONE, cut.inequality.rhs)
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                    SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            } else assertTrue(cuts.isEmpty())
        }
    }

    @Test
    fun `half aggregation should retain supporting integer bound guards and pins`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val pin = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope, setOf(x, y, real, bool, pin), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.ONE), BigFraction.ONE)),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE), BigFraction.ZERO),
                mapOf(0 to true), mapOf(pin to BigFraction.ONE)),
        ))
        val established = SourceCondition(bools = mapOf(0 to true),
            lower = mapOf(pin to BigFraction.ONE), upper = mapOf(pin to BigFraction.ONE))
        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            point = mapOf(x to 0.75, y to 0.5, real to -0.5)).single()

        for (condition in listOf(established, SourceCondition(lower = established.lower, upper = established.upper),
            SourceCondition(bools = established.bools, lower = established.lower), established)) {
            val scoped = source.atScope(scope.copy(condition = condition))
            val check = SourceEvidenceVerifier(scoped).verify(
                SourceEvidence(scoped.scope, SourceClaim.Consequence(cut.inequality), cut.derivation))

            if (condition == established) assertEquals(SourceEvidenceCheck.Checked, check)
            else assertIs<SourceEvidenceCheck.Rejected>(check)
        }
    }

    @Test
    fun `half aggregation should round strict integral endpoints below their right side`() {
        val source = SourceProofContext(scope, setOf(x, y, real), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                real to BigFraction.ONE), BigFraction.ONE)),
            "right" to SourcePremise(SourceInequality(mapOf(y to BigFraction.ofLong(3),
                real to BigFraction.MINUS_ONE), BigFraction.ofLong(2))),
            "bound" to SourcePremise(SourceInequality(mapOf(y to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE, strict = true)),
        ))

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            point = mapOf(x to -0.75, y to 1.5, real to 2.5)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ZERO),
            cut.inequality)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `point separation should cancel a third source row within its configured row limit`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "other lower" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
        ))
        val point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 1.5)

        val unbounded = SourceProofContext(scope, source.coordinates,
            source.premises.filterKeys { it != "lower" && it != "other lower" })
        for (context in listOf(source, unbounded)) {
            for (rows in listOf(2, 3, 4)) {
                val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(context, Cancellation.Never,
                    maximumCandidates = 1,
                    limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = rows), point = point)

                if (rows == 2) assertTrue(cuts.isEmpty()) else {
                    val cut = cuts.single()
                    assertEquals(SourceInequality(mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE),
                        BigFraction.ofLong(-2)), cut.inequality)
                    assertEquals(setOf(0, 1, 2), cut.owners)
                    assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(
                        SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
                }
            }
        }
    }

    @Test
    fun `a tight continuation should separate before loose rows exhaust the production allowance`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val premises = linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
        )
        repeat(512) { index ->
            premises["loose $index"] = SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ofLong(100)))
        }
        premises["tight"] = SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
            y to BigFraction.ofLong(-2)), BigFraction.ZERO))
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), premises)
        val observations = mutableMapOf<String, Long>()
        val limits = LinearAuthorityAggregateCuts.pointSourcePairLimits

        val cut = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            maximumCandidates = 1, limits = limits,
            point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 1.5)).single()

        assertEquals(SourceInequality(mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE),
            BigFraction.ofLong(-2)), cut.inequality)
        assertEquals(setOf(0, 1, 514), cut.owners)
        assertTrue((observations["AggregateContinuationScored"] ?: 0L) > 0L)
        assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
        assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `cancelling continuation scoring should withhold its unfinished choice`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "loose" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ofLong(100))),
            "tight" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
        ))
        val observations = mutableMapOf<String, Long>()
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, count ->
                observations[name] = (observations[name] ?: 0L) + count
                if (name == "AggregateContinuationScored") stopped = true
            }, limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 1.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
        assertNull(observations["AggregateContinuationComplete"])
        assertNull(observations["AggregateExtendedAttempts"])
    }

    @Test
    fun `point separation should require a fourth original row when three leave a continuous residual`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val lastReal = SourceCoordinate(SourceCoordinate.Kind.Real, 2)
        val source = SourceProofContext(scope, setOf(x, y, z, real, otherReal, lastReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "middle" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(lastReal to BigFraction.ONE,
                z to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE, lastReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-5))),
        ))
        val point = mapOf(x to 5.0 / 6, y to 5.0 / 6, z to 5.0 / 6,
            real to 5.0 / 3, otherReal to 5.0 / 3, lastReal to 5.0 / 3)

        for (rows in listOf(2, 3, 4)) {
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                maximumCandidates = 1,
                limits = LinearAuthorityAggregateCuts.pointSourcePairLimits.copy(rows = rows), point = point)

            if (rows < 4) assertTrue(cuts.isEmpty()) else {
                val cut = cuts.single()
                assertEquals(SourceInequality(mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE,
                    z to BigFraction.MINUS_ONE), BigFraction.ofLong(-3)), cut.inequality)
                assertEquals(setOf(0, 1, 2, 3), cut.owners)
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                    SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            }
        }
    }

    @Test
    fun `interrupting an extended derivation should withhold its partial proposal`() {
        val otherReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope, setOf(x, y, real, otherReal), linkedMapOf(
            "left" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE,
                x to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "right" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.ONE,
                y to BigFraction.ofLong(-2)), BigFraction.ZERO)),
            "demand" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE,
                otherReal to BigFraction.MINUS_ONE), BigFraction.ofLong(-3))),
            "lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "other lower" to SourcePremise(SourceInequality(mapOf(otherReal to BigFraction.MINUS_ONE),
                BigFraction.ZERO)),
        ))
        var stopped = false

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation { stopped },
            observe = { name, _ -> if (name == "AggregateExtendedAttempts") stopped = true },
            limits = LinearAuthorityAggregateCuts.pointSourcePairLimits,
            point = mapOf(x to 0.75, y to 0.75, real to 1.5, otherReal to 1.5))

        assertTrue(stopped)
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `a source row should support multiple differently weighted pairs`() {
        val z = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val weightedLeft = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to BigFraction.ofLong(3)),
            BigFraction.ofLong(3))
        val weightedRight = SourceInequality(mapOf(y to BigFraction.ofLong(4), real to BigFraction.ofLong(-2)),
            BigFraction.ZERO)
        val otherRight = SourceInequality(mapOf(z to BigFraction.ofLong(2), real to BigFraction.MINUS_ONE),
            BigFraction.ZERO)
        val source = SourceProofContext(scope, setOf(x, y, z, real), linkedMapOf(
            "left" to SourcePremise(weightedLeft), "right" to SourcePremise(weightedRight),
            "other" to SourcePremise(otherRight)))

        val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never)

        assertEquals(setOf(
            SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(3)), BigFraction.ONE),
            SourceInequality(mapOf(x to BigFraction.ONE, z to BigFraction.ofLong(3)), BigFraction.ONE),
        ), cuts.map { it.inequality }.toSet())
        for (cut in cuts) {
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        }
    }

    @Test
    fun `an unavailable point coordinate should withhold complete proposal coverage`() {
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right)))
        val observations = mutableMapOf<String, Long>()

        LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
            observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count },
            point = mapOf(x to 0.75, real to 1.5))

        assertEquals(1L, observations["AggregatePairIncomplete"])
    }

    @Test
    fun `source pair preparation should retain its containing work and storage limits`() {
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right)))

        for (limits in listOf(LinearAuthorityAggregateLimits(work = 1),
            LinearAuthorityAggregateLimits(allocation = 1))) {
            val observations = mutableMapOf<String, Long>()
            val cuts = LinearAuthorityAggregateCuts.prepareSourcePairs(source, Cancellation.Never,
                observe = { name, count -> observations[name] = (observations[name] ?: 0L) + count }, limits = limits)

            assertTrue(cuts.isEmpty())
            assertTrue((observations["AggregateWork"] ?: 0L) <= limits.work)
            assertTrue((observations["AggregateAllocation"] ?: 0L) <= limits.allocation)
        }
    }

    @Test
    fun `opposite bound projection should strengthen a checked sparse bound before mixed rounding`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val source = SourceProofContext(scope.copy(objective = SourceObjective("projection", ObjectiveSense.MINIMIZE)),
            setOf(x, real, residual), mapOf(
                "row" to SourcePremise(SourceInequality(mapOf(x to BigFraction.ofLong(2),
                    real to BigFraction.MINUS_ONE, residual to BigFraction.ONE), BigFraction.ofLong(3))),
                "real-lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "real-upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ofLong(2))),
                "residual-lower" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.MINUS_ONE),
                    BigFraction.ZERO)),
                "residual-upper" to SourcePremise(SourceInequality(mapOf(residual to BigFraction.ONE),
                    BigFraction.ofLong(100))),
            ), mapOf(x to BigFraction.MINUS_ONE, real to BigFraction.ONE))
        for ((aggregate, expected) in listOf(false to (BigFraction.ofLong(-3) *
            BigFraction.ofLong(2).reciprocal()), true to BigFraction.MINUS_ONE)) {
            LinearAuthority(SourceLpBudget(), retainLp = true, aggregateCuts = aggregate).use { authority ->
                val result = authority.advance(source, Cancellation.Never, cuts = true)

                assertEquals(SourceValue.Rational(expected),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
            }
        }
    }

    @Test
    fun `appending an aggregate should stop at the shared allocation allowance`() {
        val source = SourceProofContext(scope.copy(objective = SourceObjective("sum", ObjectiveSense.MINIMIZE)),
            setOf(x, y, real), mapOf("left" to SourcePremise(left), "right" to SourcePremise(right)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE))
        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), Cancellation.Never))
        val funding = SourceLpFunding(1_000_000L, 1L).apply { grant(1_000_000L) }
        LinearAuthority(SourceLpBudget(funding = funding), retainLp = true,
            additionalCuts = listOf(cut)).use { authority ->
            val result = authority.advance(source, Cancellation.Never, cuts = true)

            assertTrue(funding.exhausted)
            assertTrue(funding.allocated > 0L)
            assertTrue(result.paused)
            assertNull(result.bound)
        }
    }

    @Test
    fun `appending a false proposal should leave its row without authority`() {
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right)))
        val falseCut = LinearAuthorityCut(0, SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ZERO),
            SourceDerivation.Premise("left"), owners = setOf(0, 1))
        val template = assertNotNull(assertNotNull(LinearAuthorityTemplate.prepare(source, Cancellation.Never,
            cuts = true)).withAdditional(listOf(falseCut), Cancellation.Never))

        val query = assertNotNull(LinearAuthorityQuery.prepare(source, template, Cancellation.Never))

        assertEquals(0, query.activeCuts)
        assertTrue(query.checkedCuts.isEmpty())
    }

    @Test
    fun `an appended aggregate should strengthen a freshly checked sparse bound`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val objectiveScope = scope.copy(objective = SourceObjective("aggregate", ObjectiveSense.MINIMIZE))
        val source = SourceProofContext(objectiveScope, setOf(x, y, real, residual), mapOf(
            "left" to SourcePremise(mixed), "right" to SourcePremise(right), "bound" to SourcePremise(bound)),
            mapOf(x to BigFraction.MINUS_ONE, y to BigFraction.MINUS_ONE, residual to BigFraction.ONE))
        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
        ), Cancellation.Never, allowMixed = true, continuousBounds = mapOf(
            (residual to false) to LinearAuthorityAggregateTerm(2, "bound", bound))))
        assertTrue(assertNotNull(LinearAuthorityCuts.prepare(source, Cancellation.Never)).rows.isEmpty())
        for ((additional, expected) in listOf(emptyList<LinearAuthorityCut>() to
            (BigFraction.ofLong(-3) * BigFraction.ofLong(2).reciprocal()), listOf(cut) to BigFraction.MINUS_ONE)) {
            LinearAuthority(SourceLpBudget(), retainLp = true, additionalCuts = additional).use { authority ->
                val result = authority.advance(source, Cancellation.Never, cuts = true)

                assertEquals(SourceValue.Rational(expected),
                    assertIs<SourceClaim.Bound>(assertNotNull(result.bound).claim).value)
                assertEquals(SourceEvidenceCheck.Checked,
                    SourceEvidenceVerifier(source.withLinearAuthority()).verify(assertNotNull(result.bound)))
            }
        }
    }

    @Test
    fun `cancelling a continuous coordinate should produce a checked integer cut`() {
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right)))

        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ONE),
        ), Cancellation.Never))

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE), BigFraction.ONE),
            cut.inequality)
        assertEquals(setOf(0, 1), cut.owners)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `unequal positive source weights should cancel a continuous coordinate exactly`() {
        val weightedLeft = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to BigFraction.ofLong(3)),
            BigFraction.ofLong(3))
        val weightedRight = SourceInequality(mapOf(y to BigFraction.ofLong(4), real to BigFraction.ofLong(-2)),
            BigFraction.ZERO)
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(weightedLeft), "right" to SourcePremise(weightedRight)))

        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", weightedLeft, BigFraction.ofLong(2)),
            LinearAuthorityAggregateTerm(1, "right", weightedRight, BigFraction.ofLong(3)),
        ), Cancellation.Never))

        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(3)), BigFraction.ONE),
            cut.inequality)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `aggregate candidates should retain activation premises from every source row`() {
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val pin = SourceCoordinate(SourceCoordinate.Kind.Integer, 2)
        val source = SourceProofContext(scope, setOf(x, y, real, bool, pin), mapOf(
            "left" to SourcePremise(left, mapOf(0 to true)),
            "right" to SourcePremise(right, emptyMap(), mapOf(pin to BigFraction.ONE))))
        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ONE),
        ), Cancellation.Never))
        val established = SourceCondition(bools = mapOf(0 to true),
            lower = mapOf(pin to BigFraction.ONE), upper = mapOf(pin to BigFraction.ONE))
        for (condition in listOf(established, SourceCondition(lower = established.lower, upper = established.upper),
            SourceCondition(bools = established.bools, lower = established.lower), established)) {
            val scoped = source.atScope(scope.copy(condition = condition))

            val check = SourceEvidenceVerifier(scoped).verify(
                SourceEvidence(scoped.scope, SourceClaim.Consequence(cut.inequality), cut.derivation))

            if (condition == established) assertEquals(SourceEvidenceCheck.Checked, check)
            else assertIs<SourceEvidenceCheck.Rejected>(check)
        }
    }

    @Test
    fun `strict aggregate endpoints should round below an integral right side`() {
        for ((leftStrict, rightStrict) in listOf(true to false, false to true)) {
            val strictLeft = SourceInequality(left.coefficients, BigFraction.ofLong(4), leftStrict)
            val strictRight = SourceInequality(right.coefficients, right.rhs, rightStrict)

            val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
                LinearAuthorityAggregateTerm(0, "left", strictLeft, BigFraction.ONE),
                LinearAuthorityAggregateTerm(1, "right", strictRight, BigFraction.ONE),
            ), Cancellation.Never))

            assertEquals(BigFraction.ONE, cut.inequality.rhs)
        }
    }

    @Test
    fun `nonpositive weights should supply no aggregate candidate`() {
        for (weight in listOf(BigFraction.MINUS_ONE, BigFraction.ZERO)) {
            val cut = LinearAuthorityAggregateCuts.derive(listOf(
                LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
                LinearAuthorityAggregateTerm(1, "right", right, weight),
            ), Cancellation.Never)

            assertNull(cut)
        }
    }

    @Test
    fun `uncancelled continuous activity should supply no integer rounding candidate`() {
        val cut = LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ofLong(2)),
        ), Cancellation.Never)

        assertNull(cut)
    }

    @Test
    fun `aggregate admission should bound source count support proof work and allocation`() {
        val terms = listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ONE),
            LinearAuthorityAggregateTerm(2, "third",
                SourceInequality(mapOf(x to BigFraction.ofLong(2)), BigFraction.ZERO), BigFraction.ONE),
        )
        assertNotNull(LinearAuthorityAggregateCuts.derive(terms, Cancellation.Never))
        for (limits in listOf(LinearAuthorityAggregateLimits(rows = 2),
            LinearAuthorityAggregateLimits(support = 1), LinearAuthorityAggregateLimits(support = 2),
            LinearAuthorityAggregateLimits(proofBytes = 1),
            LinearAuthorityAggregateLimits(work = 1),
            LinearAuthorityAggregateLimits(allocation = 1))) {
            val cut = LinearAuthorityAggregateCuts.derive(terms, Cancellation.Never, limits = limits)

            assertNull(cut)
        }
    }

    @Test
    fun `aggregate arithmetic should decline intermediate scalar growth`() {
        val large = BigFraction.of(BIG_ONE shl 127, BIG_ONE)
        val row = SourceInequality(mapOf(x to large), BigFraction.ONE)
        val observations = linkedMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", row, large),
            LinearAuthorityAggregateTerm(1, "right", row, BigFraction.ONE),
        ), Cancellation.Never, { key, value -> observations[key] = (observations[key] ?: 0L) + value },
            LinearAuthorityAggregateLimits(scalarBits = 128))

        assertNull(cut)
        assertEquals(1L, observations["AggregateScalarLimit"])
        assertTrue(assertNotNull(observations["AggregateWork"]) > 0L)
        assertTrue(assertNotNull(observations["AggregateAllocation"]) > 0L)
    }

    @Test
    fun `large cancelling coefficients should retain their exact source proof`() {
        val large = BigFraction.of(BIG_ONE shl 127, BIG_ONE)
        val largeLeft = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to large), BigFraction.ofLong(3))
        val largeRight = SourceInequality(mapOf(y to BigFraction.ofLong(2), real to large.negated()), BigFraction.ZERO)
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(largeLeft), "right" to SourcePremise(largeRight)))

        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", largeLeft, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", largeRight, BigFraction.ONE),
        ), Cancellation.Never))

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `repeated declined aggregates should retain every failed attempt cost`() {
        val observations = linkedMapOf<String, Long>()
        val terms = listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ofLong(2)),
        )
        LinearAuthorityAggregateCuts.derive(terms, Cancellation.Never,
            { key, value -> observations[key] = (observations[key] ?: 0L) + value })
        val first = observations.toMap()

        val cut = LinearAuthorityAggregateCuts.derive(terms, Cancellation.Never,
            { key, value -> observations[key] = (observations[key] ?: 0L) + value })

        assertNull(cut)
        assertTrue(assertNotNull(first["AggregateWork"]) > 0L)
        assertEquals(first.mapValues { (_, value) -> value * 2L }, observations)
    }

    @Test
    fun `cancelled aggregation should retain no candidate and report completed work`() {
        var polls = 0
        val observations = linkedMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ONE),
        ), Cancellation { ++polls > 8 },
            { key, value -> observations[key] = (observations[key] ?: 0L) + value })

        assertNull(cut)
        assertEquals(1L, observations["AggregateStopped"])
        assertTrue(assertNotNull(observations["AggregateWork"]) > 0L)
    }

    @Test
    fun `aggregation should stop under cumulative source funding without renewing it`() {
        val funding = SourceLpFunding(20L, 1_048_576L).apply { grant(20L) }
        val budget = SourceLpBudget(funding = funding)
        val terms = listOf(
            LinearAuthorityAggregateTerm(0, "left", left, BigFraction.ONE),
            LinearAuthorityAggregateTerm(1, "right", right, BigFraction.ONE),
        )
        val stop = Cancellation { funding.paused }
        val observe: (String, Long) -> Unit = { stage, count ->
            if (stage == "AggregateWork") budget.observe(preparation = count)
            if (stage == "AggregateAllocation") funding.charge(0L, count)
        }
        val first = LinearAuthorityAggregateCuts.derive(terms, stop, observe)
        val spent = funding.spent

        val repeated = LinearAuthorityAggregateCuts.derive(terms, stop, observe)

        assertNull(first)
        assertNull(repeated)
        assertTrue(spent >= 20L)
        assertEquals(spent, funding.spent)
    }

    @Test
    fun `pair inspection should expose a checked missing inference at a row feasible point`() {
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6))
        val source = SourceProofContext(scope, setOf(x, y, real), mapOf(
            "left" to SourcePremise(left), "right" to SourcePremise(right)))

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), point, Cancellation.Never)

        assertTrue(result.complete)
        assertEquals(true, result.activeSourceRowsSatisfied)
        assertEquals(BigFraction.ZERO, result.maximumSourceResidual)
        assertEquals(listOf(BigFraction.ofLong(2).reciprocal()), result.violations)
        val cut = result.candidates.single()
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `pair inspection should include a continuous only cancellation row`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to BigFraction.ONE,
            residual to BigFraction.ONE), BigFraction.ofLong(3))
        val continuous = SourceInequality(mapOf(real to BigFraction.MINUS_ONE, residual to BigFraction.MINUS_ONE),
            BigFraction.ZERO)
        val point = mapOf(x to BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal(),
            real to BigFraction.ZERO, residual to BigFraction.ZERO)

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "mixed", mixed),
            LinearAuthorityAggregateTerm(1, "continuous", continuous),
        ), emptySet(), point, Cancellation.Never)

        assertTrue(result.complete)
        assertEquals(true, result.activeSourceRowsSatisfied)
        assertEquals(SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ONE),
            result.candidates.single().inequality)
    }

    @Test
    fun `pair inspection should retain bounded inferences after a wide source row`() {
        val wide = SourceInequality((2..6).associate {
            SourceCoordinate(SourceCoordinate.Kind.Real, it) to BigFraction.ONE
        }, BigFraction.ZERO)
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6))
        val source = SourceProofContext(scope, point.keys + wide.coefficients.keys, mapOf(
            "wide" to SourcePremise(wide), "left" to SourcePremise(left), "right" to SourcePremise(right)))

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "wide", wide), LinearAuthorityAggregateTerm(1, "left", left),
            LinearAuthorityAggregateTerm(2, "right", right),
        ), emptySet(), point, Cancellation.Never, limits = LinearAuthorityAggregateLimits(support = 4))

        val cut = result.candidates.single()
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
        assertFalse(result.complete)
        assertNull(result.activeSourceRowsSatisfied)
    }

    @Test
    fun `pair inspection should distinguish source residuals from aggregate violations`() {
        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), mapOf(x to BigFraction.ONE, y to BigFraction.ONE, real to BigFraction.ZERO), Cancellation.Never)

        assertTrue(result.complete)
        assertEquals(false, result.activeSourceRowsSatisfied)
        assertEquals(BigFraction.ofLong(2), result.maximumSourceResidual)
        assertEquals(listOf(BigFraction.ONE), result.violations)
    }

    @Test
    fun `pair inspection should not combine inactive source rows`() {
        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), setOf(1), mapOf(x to BigFraction.ONE, y to BigFraction.ONE, real to BigFraction.ZERO), Cancellation.Never)

        assertTrue(result.complete)
        assertTrue(result.candidates.isEmpty())
        assertEquals(true, result.activeSourceRowsSatisfied)
    }

    @Test
    fun `limited pair inspection should withhold complete scan claims`() {
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6))
        val rows = listOf(LinearAuthorityAggregateTerm(0, "left", left),
            LinearAuthorityAggregateTerm(1, "right", right))
        for (limits in listOf(LinearAuthorityAggregateLimits(work = 1),
            LinearAuthorityAggregateLimits(allocation = 1))) {
            val result = LinearAuthorityAggregateCuts.inspectPairs(rows, emptySet(), point, Cancellation.Never,
                limits = limits)

            assertFalse(result.complete)
            assertTrue(result.candidates.isEmpty())
        }
    }

    @Test
    fun `a pair retention limit should not establish the absence of violated cuts`() {
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6))

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), point, Cancellation.Never, maximumCandidates = 0)

        assertFalse(result.complete)
        assertTrue(result.candidates.isEmpty())
        assertEquals(true, result.activeSourceRowsSatisfied)
    }

    @Test
    fun `pair inspection should complete when the aggregate is satisfied at the point`() {
        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), mapOf(x to BigFraction.ONE, y to BigFraction.ZERO, real to BigFraction.ONE), Cancellation.Never)

        assertTrue(result.complete)
        assertTrue(result.candidates.isEmpty())
        assertEquals(true, result.activeSourceRowsSatisfied)
    }

    @Test
    fun `pair inspection should retain unknown efficacy when a point coordinate is missing`() {
        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), mapOf(x to BigFraction.ONE, real to BigFraction.ZERO), Cancellation.Never)

        assertFalse(result.complete)
        assertTrue(result.candidates.isEmpty())
        assertNull(result.activeSourceRowsSatisfied)
    }

    @Test
    fun `pair inspection should charge nested aggregation once to the containing meter`() {
        var charged = 0L
        var observed = 0L
        val meter = WorkMeter { charged += it }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = meter
        }
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6))

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", left), LinearAuthorityAggregateTerm(1, "right", right),
        ), emptySet(), point, token, { stage, count -> if (stage == "AggregateWork") observed += count })

        assertTrue(result.complete)
        assertTrue(charged > 0L)
        assertEquals(observed, charged)
    }

    @Test
    fun `mixed aggregates should retain checked continuous bound complements and strict source rows`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        for (sign in listOf(BigFraction.MINUS_ONE, BigFraction.ONE)) {
            for (strict in listOf(false, true)) {
                val mixed = SourceInequality(left.coefficients + (residual to sign), left.rhs, strict)
                val bound = SourceInequality(mapOf(residual to sign), BigFraction.ZERO)
                val source = SourceProofContext(scope, setOf(x, y, real, residual), mapOf(
                    "left" to SourcePremise(mixed), "right" to SourcePremise(right),
                    "bound" to SourcePremise(bound)))

                val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
                    LinearAuthorityAggregateTerm(0, "left", mixed),
                    LinearAuthorityAggregateTerm(1, "right", right),
                ), Cancellation.Never, allowMixed = true, continuousBounds = mapOf(
                    (residual to (sign > BigFraction.ZERO)) to LinearAuthorityAggregateTerm(2, "bound", bound))))

                assertEquals(SourceInequality(mapOf(x to BigFraction.ONE, y to BigFraction.ONE, residual to sign),
                    BigFraction.ONE), cut.inequality)
                assertTrue(cut.mixed)
                assertEquals(setOf(0, 1, 2), cut.owners)
                assertTrue(assertNotNull(LinearAuthorityCuts.prepare(source, Cancellation.Never)).rows.isEmpty())
                assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
                    SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
            }
        }
    }

    @Test
    fun `mixed aggregates should decline a missing residual bound`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val observations = linkedMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
        ), Cancellation.Never, { key, value -> observations[key] = (observations[key] ?: 0L) + value },
            allowMixed = true)

        assertNull(cut)
        assertEquals(1L, observations["AggregateMissingBounds"])
    }

    @Test
    fun `mixed aggregates should retain the activation premise of a residual bound`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val bool = SourceCoordinate(SourceCoordinate.Kind.Boolean, 0)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val source = SourceProofContext(scope, setOf(x, y, real, residual, bool), mapOf(
            "left" to SourcePremise(mixed), "right" to SourcePremise(right),
            "bound" to SourcePremise(bound, mapOf(0 to true))))
        val cut = assertNotNull(LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
        ), Cancellation.Never, allowMixed = true, continuousBounds = mapOf(
            (residual to false) to LinearAuthorityAggregateTerm(2, "bound", bound))))
        val template = assertNotNull(assertNotNull(LinearAuthorityTemplate.prepare(source, Cancellation.Never,
            cuts = true)).withAdditional(listOf(cut), Cancellation.Never))
        for (enabled in listOf(true, false, true)) {
            val context = source.atScope(scope.copy(condition = SourceCondition(bools = mapOf(0 to enabled))))

            val check = SourceEvidenceVerifier(context).verify(
                SourceEvidence(context.scope, SourceClaim.Consequence(cut.inequality), cut.derivation))
            val query = assertNotNull(LinearAuthorityQuery.prepare(context, template, Cancellation.Never))

            assertEquals(if (enabled) 1 else 0, query.activeCuts)
            if (enabled) assertEquals(SourceEvidenceCheck.Checked, check)
            else assertIs<SourceEvidenceCheck.Rejected>(check)
        }
    }

    @Test
    fun `mixed aggregate arithmetic should decline a wide bound endpoint before retaining a cut`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val large = BigFraction.of(BIG_ONE shl 127, BIG_ONE)
        val bound = SourceInequality(mapOf(residual to large.reciprocal().negated()), large)
        val observations = linkedMapOf<String, Long>()

        val cut = LinearAuthorityAggregateCuts.derive(listOf(
            LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
        ), Cancellation.Never, { key, value -> observations[key] = (observations[key] ?: 0L) + value },
            LinearAuthorityAggregateLimits(scalarBits = 128), allowMixed = true, continuousBounds = mapOf(
                (residual to false) to LinearAuthorityAggregateTerm(2, "bound", bound)))

        assertNull(cut)
        assertEquals(1L, observations["AggregateScalarLimit"])
        assertTrue(assertNotNull(observations["AggregateWork"]) > 0L)
    }

    @Test
    fun `mixed pair inspection should expose a checked missing inference at a row feasible point`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val source = SourceProofContext(scope, setOf(x, y, real, residual), mapOf(
            "left" to SourcePremise(mixed), "right" to SourcePremise(right), "bound" to SourcePremise(bound)))
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6), residual to BigFraction.ZERO)

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
            LinearAuthorityAggregateTerm(2, "bound", bound),
        ), emptySet(), point, Cancellation.Never, allowMixed = true)

        assertTrue(result.complete)
        assertEquals(true, result.activeSourceRowsSatisfied)
        assertEquals(listOf(BigFraction.ofLong(2).reciprocal()), result.violations)
        val cut = result.candidates.single()
        assertTrue(cut.mixed)
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source).verify(
            SourceEvidence(scope, SourceClaim.Consequence(cut.inequality), cut.derivation)))
    }

    @Test
    fun `mixed pair inspection should require an active continuous bound owner`() {
        val residual = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val mixed = SourceInequality(left.coefficients + (residual to BigFraction.MINUS_ONE), left.rhs)
        val bound = SourceInequality(mapOf(residual to BigFraction.MINUS_ONE), BigFraction.ZERO)
        val quarter = BigFraction.ofLong(4).reciprocal()
        val point = mapOf(x to quarter * BigFraction.ofLong(3), y to quarter * BigFraction.ofLong(3),
            real to quarter * BigFraction.ofLong(6), residual to BigFraction.ZERO)
        for (inactive in listOf(emptySet(), setOf(2), emptySet())) {
            val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
                LinearAuthorityAggregateTerm(0, "left", mixed), LinearAuthorityAggregateTerm(1, "right", right),
                LinearAuthorityAggregateTerm(2, "bound", bound),
            ), inactive, point, Cancellation.Never, allowMixed = true)

            assertTrue(result.complete)
            assertEquals(if (inactive.isEmpty()) 1 else 0, result.candidates.size)
        }
    }

    @Test
    fun `a scalar limited candidate should withhold complete pair inspection`() {
        val large = BigFraction.of(BIG_ONE shl 127, BIG_ONE)
        val largeLeft = SourceInequality(mapOf(x to BigFraction.ofLong(2), real to large), BigFraction.ofLong(3))
        val largeRight = SourceInequality(mapOf(y to BigFraction.ofLong(2), real to large.negated()), BigFraction.ZERO)
        val point = mapOf(x to BigFraction.ZERO, y to BigFraction.ZERO, real to BigFraction.ZERO)

        val result = LinearAuthorityAggregateCuts.inspectPairs(listOf(
            LinearAuthorityAggregateTerm(0, "left", largeLeft), LinearAuthorityAggregateTerm(1, "right", largeRight),
        ), emptySet(), point, Cancellation.Never,
            limits = LinearAuthorityAggregateLimits(scalarBits = 128))

        assertFalse(result.complete)
        assertTrue(result.candidates.isEmpty())
        assertEquals(true, result.activeSourceRowsSatisfied)
    }
}
