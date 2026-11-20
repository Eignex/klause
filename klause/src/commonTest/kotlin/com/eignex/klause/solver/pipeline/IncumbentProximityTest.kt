package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncumbentProximityTest : RuntimeInitializedTest() {
    @Test
    fun `LP agreements should restrict pure binary queries while mixed exchanges remain free`() {
        for (mixed in listOf(false, true)) {
            val columns = (0..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
            val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
            val condition = SourceCondition(lower = mapOf(columns[2] to BigFraction.ONE))
            val source = SourceProofContext(SourceScope(SourceRevision("agreement", "exact"), condition,
                SourceObjective("cost", ObjectiveSense.MINIMIZE)),
                columns.toSet() + if (mixed) setOf(real) else emptySet(), emptyMap(),
                columns.zip(listOf(1L, -2L, 1L, 1L).map(BigFraction::ofLong)).toMap())
            val budget = SourceLpBudget(funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) })
            val guidance = LinearRepairGuidance()
            val values = columns.zip(listOf(1.0, 0.5, 0.0, 0.0)).toMap() +
                if (mixed) mapOf(real to 0.0) else emptyMap()
            assertTrue(guidance.remember(source, values, budget, ExecutionControl.Never))
            val center = Sample(booleanArrayOf(), longArrayOf(1, 0, 1, 0),
                reals = if (mixed) doubleArrayOf(0.0) else doubleArrayOf())

            val query = assertNotNull(IncumbentProximity.prepare(source, center, BigFraction.ofLong(2),
                budget, ExecutionControl.Never, guidance.find(source)))

            if (mixed) assertEquals(condition, query.scope.condition) else {
                assertEquals(mapOf(columns[0] to BigFraction.ONE, columns[2] to BigFraction.ONE,
                    columns[3] to BigFraction.ZERO), query.scope.condition.lower)
                assertEquals(mapOf(columns[0] to BigFraction.ONE, columns[3] to BigFraction.ZERO),
                    query.scope.condition.upper)
            }
        }
    }

    @Test
    fun `private exchanges should release incumbent coordinates and preserve receiving pins`() {
        val columns = (0..3).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val condition = SourceCondition(lower = mapOf(columns[2] to BigFraction.ONE))
        val source = SourceProofContext(SourceScope(SourceRevision("agreement", "exact"), condition,
            SourceObjective("cost", ObjectiveSense.MINIMIZE)), columns.toSet(), emptyMap(),
            columns.zip(listOf(1L, -2L, 1L, 1L).map(BigFraction::ofLong)).toMap())
        val budget = SourceLpBudget(funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) })

        val query = assertNotNull(IncumbentProximity.prepare(source,
            Sample(booleanArrayOf(), longArrayOf(1, 0, 1, 0)), BigFraction.ofLong(2), budget,
            ExecutionControl.Never))

        assertEquals(condition, query.scope.condition)
        val inside = Sample(booleanArrayOf(), longArrayOf(0, 1, 1, 0))
        val outside = Sample(booleanArrayOf(), longArrayOf(0, 1, 0, 0))
        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(query.withLinearAuthority()).verify(
            SourceEvidence(query.scope, SourceClaim.Witness(inside), SourceDerivation.SourceWitness)))
        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(query.withLinearAuthority()).verify(
            SourceEvidence(query.scope, SourceClaim.Witness(outside), SourceDerivation.SourceWitness)))
    }

    @Test
    fun `private neighborhoods should reject improving points outside their change radius`() {
        for ((size, radius) in listOf(5 to 4, 50 to 5, 400 to 32)) {
            val columns = (0 until size).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }.toSet()
            val source = SourceProofContext(SourceScope(SourceRevision("radius", "exact"),
                objective = SourceObjective("cost", ObjectiveSense.MINIMIZE)), columns, emptyMap(),
                columns.associateWith { BigFraction.ONE })
            val center = Sample(booleanArrayOf(), LongArray(size) { 1L })
            val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
            val budget = SourceLpBudget(funding = funding)
            val query = assertNotNull(IncumbentProximity.prepare(source, center, BigFraction.ofLong(size.toLong()),
                budget, ExecutionControl.Never))
            val inside = center.copy(ints = LongArray(size) { if (it < radius) 0L else 1L })
            val outside = center.copy(ints = LongArray(size) { if (it <= radius) 0L else 1L })

            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(query.withLinearAuthority()).verify(
                SourceEvidence(query.scope, SourceClaim.Witness(inside), SourceDerivation.SourceWitness)))
            assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(query.withLinearAuthority()).verify(
                SourceEvidence(query.scope, SourceClaim.Witness(outside), SourceDerivation.SourceWitness)))
            assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(source.withLinearAuthority()).verify(
                SourceEvidence(source.scope, SourceClaim.Witness(outside), SourceDerivation.SourceWitness)))
        }
    }

    @Test
    fun `private candidate provenance should remain attached to its publication snapshot`() {
        val stats = SolveStats.EMPTY

        val private = IncumbentProximity.candidateStats(stats, true)
        val ordinary = IncumbentProximity.candidateStats(stats, false)

        assertEquals(1L, private.smt.completeSearchStages?.get("incumbentReusePrivateCandidate"))
        assertNull(ordinary.smt.completeSearchStages?.get("incumbentReusePrivateCandidate"))
        assertNull(stats.smt.completeSearchStages?.get("incumbentReusePrivateCandidate"))
    }

    @Test
    fun `small declared gaps should retain the coefficient improvement floor`() {
        val column = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val semantics = SourceSemantics(SourceFormat.MPS, optimalityTolerance = OptimalityTolerance(
            SemanticRational.of(bigIntOf(1), bigIntOf(10_000_000)))).resolve()
        val source = SourceProofContext(SourceScope(SourceRevision("proximity-gap", "exact"),
            objective = SourceObjective("cost", ObjectiveSense.MINIMIZE)), setOf(column), emptyMap(),
            mapOf(column to BigFraction.MINUS_ONE), semantics = semantics)
        val center = Sample(booleanArrayOf(), longArrayOf(0))
        val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }

        val query = assertNotNull(IncumbentProximity.prepare(source, center, BigFraction.ZERO,
            SourceLpBudget(funding = funding), ExecutionControl.Never))

        assertEquals(BigFraction.of(bigIntOf(-1), bigIntOf(1000)),
            query.premises.getValue("incumbent-proximity-target").inequality.rhs)
    }

    @Test
    fun `relative improvement should be independent of objective translation`() {
        val column = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        for (constant in listOf(0L, 1_000_000L)) {
            val source = SourceProofContext(SourceScope(SourceRevision("proximity-translation", "exact"),
                objective = SourceObjective("cost", ObjectiveSense.MINIMIZE)), setOf(column), emptyMap(),
                mapOf(column to BigFraction.ofLong(200)), BigFraction.ofLong(constant))
            val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }

            val query = assertNotNull(IncumbentProximity.prepare(source, Sample(booleanArrayOf(), longArrayOf(1)),
                BigFraction.ofLong(200 + constant), SourceLpBudget(funding = funding), ExecutionControl.Never))

            assertEquals(BigFraction.ofLong(198), query.premises.getValue("incumbent-proximity-target").inequality.rhs)
        }
    }

    @Test
    fun `private distance should count binary changes while preserving receiving pins`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val condition = SourceCondition(lower = mapOf(columns[0] to BigFraction.ONE))
        val source = SourceProofContext(SourceScope(SourceRevision("proximity", "exact"), condition,
            SourceObjective("cost", ObjectiveSense.MINIMIZE)), columns.toSet(), emptyMap(),
            columns.associateWith { BigFraction.ONE })
        val center = Sample(booleanArrayOf(), longArrayOf(1, 0, 1))
        val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }

        val query = assertNotNull(IncumbentProximity.prepare(source, center, BigFraction.ofLong(2),
            SourceLpBudget(funding = funding), ExecutionControl.Never))

        assertEquals(listOf(-1, 1, -1).map { BigFraction.ofLong(it.toLong()) },
            columns.map { query.premises.getValue("incumbent-proximity-radius").inequality.coefficients[it] })
        assertEquals(source.objective, query.objective)
        assertEquals(source.objectiveConstant, query.objectiveConstant)
        assertEquals(BigFraction.ONE, query.premises.getValue("incumbent-proximity-radius").inequality.rhs)
        assertEquals(condition, query.scope.condition)
        assertNotEquals(source.scope, query.scope)
        assertTrue(funding.spent > 0 && funding.allocated > 0)
    }

    @Test
    fun `maximization target should retain the original objective constant`() {
        val column = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("proximity-max", "exact"),
            objective = SourceObjective("cost", ObjectiveSense.MAXIMIZE)), setOf(column), emptyMap(),
            mapOf(column to BigFraction.ofLong(2)), BigFraction.ofLong(7))
        val center = Sample(booleanArrayOf(), longArrayOf(0))
        val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }

        val query = assertNotNull(IncumbentProximity.prepare(source, center, BigFraction.ofLong(-7),
            SourceLpBudget(funding = funding), ExecutionControl.Never))

        val target = query.premises.getValue("incumbent-proximity-target").inequality
        assertEquals(BigFraction.ofLong(-2), target.coefficients[column])
        assertEquals(BigFraction.of(bigIntOf(-1), bigIntOf(500)), target.rhs)
        assertEquals(source.objective, query.objective)
        assertEquals(source.objectiveConstant, query.objectiveConstant)
        assertEquals(ObjectiveSense.MAXIMIZE, query.scope.objective?.sense)
    }

    @Test
    fun `unavailable preparation should not create a private query`() {
        val column = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("proximity-limit", "exact"),
            objective = SourceObjective("cost", ObjectiveSense.MINIMIZE)), setOf(column), emptyMap(),
            mapOf(column to BigFraction.ONE))
        for (variant in listOf("work", "allocation", "cancelled", "nonbinary")) {
            val funding = SourceLpFunding(if (variant == "work") 0 else 100_000,
                if (variant == "allocation") 0 else 100_000).also { it.grant(100_000) }
            val center = Sample(booleanArrayOf(), longArrayOf(if (variant == "nonbinary") 2 else 1))
            val stop = if (variant == "cancelled") ExecutionControl { true } else ExecutionControl.Never

            assertNull(IncumbentProximity.prepare(source, center, BigFraction.ONE,
                SourceLpBudget(funding = funding), stop), variant)
        }
    }
}
