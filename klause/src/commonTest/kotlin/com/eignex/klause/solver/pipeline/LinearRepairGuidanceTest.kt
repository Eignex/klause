package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearRepairGuidanceTest {
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val scope = SourceScope(SourceRevision("repair-guidance", "exact"))
    private val source = SourceProofContext(scope, setOf(integer), emptyMap())

    @Test
    fun `a paid root snapshot should retain only original integer guidance`() {
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val mixed = SourceProofContext(scope, setOf(integer, real), emptyMap())
        val funding = SourceLpFunding(10_000, 10_000).also { it.grant(10_000) }
        val guidance = LinearRepairGuidance()

        val retained = guidance.rememberIntegerPoint(mixed, listOf(integer, real),
            listOf(BigFraction.ONE, BigFraction.ZERO), SourceLpBudget(funding = funding), ExecutionControl.Never)

        assertTrue(retained)
        assertEquals(mapOf(integer to 1.0), assertNotNull(guidance.find(mixed)).point)
    }

    @Test
    fun `retained guidance should own a snapshot of the producer point`() {
        val funding = SourceLpFunding(1000, 1000).also { it.grant(1000) }
        val point = mutableMapOf(integer to 0.5)
        val guidance = LinearRepairGuidance()

        assertTrue(guidance.remember(source, point, SourceLpBudget(funding = funding), ExecutionControl.Never))
        point[integer] = 1.0

        assertEquals(0.5, assertNotNull(guidance.find(source)).integer(0))
        assertTrue(funding.spent > 0)
        assertTrue(funding.allocated > 0)
    }

    @Test
    fun `an equal foreign context should not receive another capture guidance`() {
        val funding = SourceLpFunding(1000, 1000).also { it.grant(1000) }
        val guidance = LinearRepairGuidance()
        assertTrue(guidance.remember(source, mapOf(integer to 0.5),
            SourceLpBudget(funding = funding), ExecutionControl.Never))

        assertNull(guidance.find(SourceProofContext(scope, setOf(integer), emptyMap())))
    }

    @Test
    fun `a finite cut receipt should admit only its retained source context`() {
        val funding = SourceLpFunding(1000, 1000).also { it.grant(1000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        val foreign = SourceProofContext(scope, setOf(integer), emptyMap())
        assertTrue(guidance.remember(source, mapOf(integer to 0.5), budget, ExecutionControl.Never))

        guidance.settleFiniteCut(foreign, published = true, budget, ExecutionControl.Never)

        assertNull(guidance.finiteCutReady(source))
    }

    @Test
    fun `later producer points should leave the retained snapshot unchanged`() {
        val funding = SourceLpFunding(10_000, 10_000).also { it.grant(10_000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        assertTrue(guidance.remember(source, mapOf(integer to 0.5), budget, ExecutionControl.Never))

        assertFalse(guidance.remember(source, mapOf(integer to 1.0), budget, ExecutionControl.Never))

        assertEquals(0.5, assertNotNull(guidance.find(source)).integer(0))
    }

    @Test
    fun `invalid or cancelled points should leave no shared guidance`() {
        for (variant in listOf("foreign", "nonfinite", "cancelled", "oversized")) {
            val funding = SourceLpFunding(1_000_000, 1_000_000).also { it.grant(1_000_000) }
            val point = when (variant) {
                "foreign" -> mapOf(SourceCoordinate(SourceCoordinate.Kind.Integer, 1) to 0.5)
                "nonfinite" -> mapOf(integer to Double.NaN)
                "oversized" -> (0..4096).associate { SourceCoordinate(SourceCoordinate.Kind.Integer, it) to 0.5 }
                else -> mapOf(integer to 0.5)
            }
            val guidance = LinearRepairGuidance()

            assertFalse(guidance.remember(source, point, SourceLpBudget(funding = funding),
                ExecutionControl { variant == "cancelled" }))

            assertNull(guidance.find(source))
        }
    }

    @Test
    fun `guidance retention should require both remaining work and allocation`() {
        for ((work, allocation) in listOf(0L to 1000L, 1000L to 0L)) {
            val funding = SourceLpFunding(work, allocation).also { it.grant(work) }
            val guidance = LinearRepairGuidance()

            assertFalse(guidance.remember(source, mapOf(integer to 0.5),
                SourceLpBudget(funding = funding), ExecutionControl.Never))

            assertNull(guidance.find(source))
        }
    }

    @Test
    fun `retirement during capture should refuse late guidance publication`() {
        val funding = SourceLpFunding(10_000, 10_000).also { it.grant(10_000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        val token = ExecutionControl {
            guidance.close()
            false
        }

        val retained = guidance.remember(source, mapOf(integer to 0.5), budget, token)

        assertFalse(retained)
        assertNull(guidance.find(source))
    }

    @Test
    fun `closing guidance should release the point and refuse later publication`() {
        val funding = SourceLpFunding(1000, 1000).also { it.grant(1000) }
        val budget = SourceLpBudget(funding = funding)
        val guidance = LinearRepairGuidance()
        assertTrue(guidance.remember(source, mapOf(integer to 0.5), budget, ExecutionControl.Never))

        guidance.close()

        assertNull(guidance.find(source))
        assertFalse(guidance.remember(source, mapOf(integer to 0.5), budget, ExecutionControl.Never))
    }
}
