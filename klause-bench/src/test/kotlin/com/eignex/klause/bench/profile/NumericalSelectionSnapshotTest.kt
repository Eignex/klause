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
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NumericalSelectionSnapshotTest {
    private val x = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val generation = SourceScope(SourceRevision("selection-import", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE),
        condition = SourceCondition(upper = mapOf(x to BigFraction.ONE)))
    private val row = SourceInequality(mapOf(x to BigFraction.ONE), BigFraction.ofLong(2), strict = true)
    private val premise = SourceDerivation.Premise("row")

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
