package com.eignex.klause.bench.profile

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NumericalSelectionSnapshotTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val generation = SourceScope(SourceRevision("selection-import", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE),
        condition = SourceCondition(upper = mapOf(x to BigFraction.ONE)))
    private val row = SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(2), strict = true)
    private val premise = SourceDerivation.Premise("row")

    @Test
    fun `rejection cost should preserve cumulative attempt and phase values`() {
        val phaseType = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityAggregatePhase")
        val phase = phaseType.enumConstants.single { (it as Enum<*>).name == "BoundRounding" }
        val phaseCostType = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityAggregatePhaseCost")
        val phaseCost = phaseCostType.declaredConstructors.single().newInstance(70L, 1024L, 2L)
        val prefixType = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityAggregateCostPrefix")
        val prefix = prefixType.declaredConstructors.single().newInstance(3L, mapOf(phase to phaseCost))

        val captured = checkNotNull(NumericalSelectionSnapshot(Cancellation.Never).rejectionCost(prefix))

        assertEquals("cumulative-source-pair-prefix", captured.getValue("kind").jsonPrimitive.content)
        assertEquals("3", captured.getValue("attempt").jsonPrimitive.content)
        val rounding = captured.getValue("phases").jsonObject.getValue("BoundRounding").jsonObject
        assertEquals("70", rounding.getValue("work").jsonPrimitive.content)
        assertEquals("1024", rounding.getValue("allocation").jsonPrimitive.content)
        assertEquals("2", rounding.getValue("spans").jsonPrimitive.content)
        assertFalse("checked" in captured)
    }

    @Test
    fun `unobserved rejection cost should remain absent`() {
        assertNull(NumericalSelectionSnapshot(Cancellation.Never).rejectionCost(null))
    }

    @Test
    fun `query cost should retain its scalar interval scope and installed mask`() {
        val snapshot = NumericalSelectionSnapshot(Cancellation.Never)
        val journal = snapshot.journal()
        val type = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityFractionalQueryCost")
        val cost = type.declaredConstructors.single { it.parameterCount == 9 }.newInstance(
            generation, setOf(3, 1), mapOf("operations" to 1L), mapOf("operations" to 2L),
            "completed", 1L, 0L, 129L, 8192L)
        journal.javaClass.getDeclaredField("fractionalQueryCost").apply { isAccessible = true }.set(journal, cost)

        val captured = snapshot.fractionalQueryCost(journal)

        assertEquals("true", captured.getValue("complete").jsonPrimitive.content)
        assertEquals(NumericalFrontierSnapshot.scope(generation), captured.getValue("scope"))
        assertEquals(listOf("1", "3"), captured.getValue("installed").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("1", captured.getValue("before").jsonObject.getValue("operations").jsonPrimitive.content)
        assertEquals("2", captured.getValue("after").jsonObject.getValue("operations").jsonPrimitive.content)
        assertEquals("129", captured.getValue("observationWork").jsonPrimitive.content)
        assertEquals("8192", captured.getValue("observationAllocation").jsonPrimitive.content)
    }

    @Test
    fun `stopped query cost should not claim a complete query interval`() {
        val snapshot = NumericalSelectionSnapshot(Cancellation.Never)
        val journal = snapshot.journal()
        val type = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityFractionalQueryCost")
        val cost = type.declaredConstructors.single { it.parameterCount == 9 }.newInstance(
            generation, emptySet<Int>(), mapOf("operations" to 1L), mapOf("operations" to 1L),
            "stopped", 1L, 0L, 129L, 8192L)
        journal.javaClass.getDeclaredField("fractionalQueryCost").apply { isAccessible = true }.set(journal, cost)

        val captured = snapshot.fractionalQueryCost(journal)

        assertEquals("true", captured.getValue("available").jsonPrimitive.content)
        assertEquals("false", captured.getValue("complete").jsonPrimitive.content)
        assertEquals("stopped", captured.getValue("outcome").jsonPrimitive.content)
    }

    @Test
    fun `unretained query cost should remain unavailable`() {
        val snapshot = NumericalSelectionSnapshot(Cancellation.Never)

        val captured = snapshot.fractionalQueryCost(snapshot.journal())

        assertEquals("false", captured.getValue("available").jsonPrimitive.content)
        assertEquals("false", captured.getValue("complete").jsonPrimitive.content)
        assertFalse("after" in captured)
    }

    @Test
    fun `generation owners should preserve source iteration rather than sorted premise order`() {
        val context = SourceProofContext(generation, setOf(x), linkedMapOf(
            "z" to SourcePremise(row), "a" to SourcePremise(row)))

        val captured = NumericalSelectionSnapshot(Cancellation.Never).generationOwnerMap(context)

        assertEquals("true", captured.getValue("complete").jsonPrimitive.content)
        val owners = captured.getValue("owners").jsonArray
        assertEquals(listOf("z", "a"), owners.map { it.jsonObject.getValue("premise").jsonPrimitive.content })
        assertEquals(listOf("0", "1"), owners.map { it.jsonObject.getValue("owner").jsonPrimitive.content })
    }

    @Test
    fun `imported consequence capture should preserve its scope row and derivation`() {
        val imported = SourceDerivation.Import(SourceEvidence(generation, SourceClaim.Consequence(row), premise))

        val captured = NumericalSelectionSnapshot(Cancellation.Never).proof(imported)

        assertEquals("Import", captured.getValue("kind").jsonPrimitive.content)
        assertEquals(NumericalFrontierSnapshot.scope(generation), captured.getValue("scope"))
        assertEquals("Consequence", captured.getValue("claim").jsonPrimitive.content)
        assertEquals("2", captured.getValue("rhs").jsonPrimitive.content)
        assertEquals("true", captured.getValue("strict").jsonPrimitive.content)
        assertEquals("1", captured.getValue("coefficients").jsonArray.single().jsonObject
            .getValue("value").jsonPrimitive.content)
        assertEquals("row", captured.getValue("derivation").jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `imported bound capture should preserve its value and strictness`() {
        val imported = SourceDerivation.Import(SourceEvidence(generation, SourceClaim.Bound(BigFraction.ONE,
            strict = true), premise))

        val captured = NumericalSelectionSnapshot(Cancellation.Never).proof(imported)

        assertEquals("Bound", captured.getValue("claim").jsonPrimitive.content)
        assertEquals("1", captured.getValue("value").jsonPrimitive.content)
        assertEquals("true", captured.getValue("strict").jsonPrimitive.content)
        assertEquals(NumericalFrontierSnapshot.scope(generation), captured.getValue("scope"))
    }

    @Test
    fun `imported refutation capture should preserve its claim and derivation`() {
        val imported = SourceDerivation.Import(SourceEvidence(generation, SourceClaim.Refutation, premise))

        val captured = NumericalSelectionSnapshot(Cancellation.Never).proof(imported)

        assertEquals("Refutation", captured.getValue("claim").jsonPrimitive.content)
        assertEquals("row", captured.getValue("derivation").jsonObject.getValue("id").jsonPrimitive.content)
        assertFalse("value" in captured)
        assertFalse("coefficients" in captured)
    }

    @Test
    fun `imported proof capture should withhold a nested derivation at its depth limit`() {
        val imported = SourceDerivation.Import(SourceEvidence(generation, SourceClaim.Consequence(row), premise))

        val captured = NumericalSelectionSnapshot(Cancellation.Never).proof(imported, depth = 63)

        assertEquals("Import", captured.getValue("kind").jsonPrimitive.content)
        assertEquals("Unavailable", captured.getValue("derivation").jsonObject.getValue("kind").jsonPrimitive.content)
    }

    @Test
    fun `cancelled imported proof capture should publish no imported claim`() {
        val imported = SourceDerivation.Import(SourceEvidence(generation, SourceClaim.Consequence(row), premise))

        val captured = NumericalSelectionSnapshot(Cancellation { true }).proof(imported)

        assertEquals("Unavailable", captured.getValue("kind").jsonPrimitive.content)
        assertFalse("claim" in captured)
        assertFalse("scope" in captured)
    }
}
