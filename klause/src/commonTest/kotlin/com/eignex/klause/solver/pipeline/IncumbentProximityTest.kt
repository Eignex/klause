package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncumbentProximityTest : RuntimeInitializedTest() {
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
    fun `paid bound aspiration should request half the remaining original gap`() {
        val column = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        for (sense in ObjectiveSense.entries) {
            val maximize = sense == ObjectiveSense.MAXIMIZE
            val source = SourceProofContext(SourceScope(SourceRevision("aspiration", "exact"),
                objective = SourceObjective("cost", sense)), setOf(column), emptyMap(),
                mapOf(column to BigFraction.ofLong(if (maximize) -200 else 200)), BigFraction.ofLong(7))
            val funding = SourceLpFunding(100_000, 100_000).also { it.grant(100_000) }
            val constant = if (maximize) -7L else 7L

            val query = assertNotNull(IncumbentProximity.prepare(source, Sample(booleanArrayOf(), longArrayOf(1)),
                BigFraction.ofLong(200 + constant), SourceLpBudget(funding = funding), ExecutionControl.Never,
                aspiration = BigFraction.ofLong(100 + constant)))

            assertEquals(BigFraction.ofLong(150), query.premises.values.single().inequality.rhs)
        }
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

        assertEquals(BigFraction.of(bigIntOf(-1), bigIntOf(1000)), query.premises.values.single().inequality.rhs)
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

            assertEquals(BigFraction.ofLong(198), query.premises.values.single().inequality.rhs)
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
            columns.map { query.objective[it] })
        assertEquals(BigFraction.ofLong(2), query.objectiveConstant)
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

        val target = query.premises.values.single().inequality
        assertEquals(BigFraction.ofLong(-2), target.coefficients[column])
        assertEquals(BigFraction.of(bigIntOf(-1), bigIntOf(500)), target.rhs)
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
