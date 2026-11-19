package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.solver.Sample
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
