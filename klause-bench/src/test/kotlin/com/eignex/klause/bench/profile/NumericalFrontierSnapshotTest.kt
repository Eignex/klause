package com.eignex.klause.bench.profile

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.optimization.LinearPinnedObjective
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
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NumericalFrontierSnapshotTest {
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
    private val half = BigFraction.ofLong(2).reciprocal()
    private val context = SourceProofContext(
        SourceScope(SourceRevision("frontier", "exact"),
            objective = SourceObjective("x", ObjectiveSense.MINIMIZE)),
        setOf(integer),
        mapOf("half" to SourcePremise(SourceInequality(
            mapOf(integer to BigFraction.ofLong(-2)), BigFraction.MINUS_ONE))),
        mapOf(integer to BigFraction.ONE),
    )
    private val evidence = SourceEvidence(
        context.scope.copy(condition = SourceCondition(lower = mapOf(integer to BigFraction.ZERO))),
        SourceClaim.Bound(SourceValue.Rational(half)),
        SourceDerivation.Combination(listOf(SourceDerivation.Combination.Term(half, SourceDerivation.Premise("half")))),
    )

    @Test
    fun `an opened profile frontier should publish a checked source bound`() {
        val invocation = NumericalFrontierSnapshot.open(context, TheoryParams(sourceLpOperations = 2),
            frontier = true) as AutoCloseable

        val bound = invocation.use {
            val result = NumericalFrontierSnapshot.run(it, onIncumbent = { _, _ -> })
            NumericalFrontierSnapshot.field(result, "bound") as SourceEvidence
        }

        val inspected = NumericalFrontierSnapshot(context, Cancellation.Never).bound(bound)
        assertEquals("1", inspected.getValue("nativeValue").jsonPrimitive.content)
        assertEquals("true", inspected.getValue("checked").jsonPrimitive.content)
    }

    @Test
    fun `selection capture should expose empty journal limits`() {
        val snapshot = NumericalSelectionSnapshot(Cancellation.Never)
        val journal = snapshot.journal()

        val capture = snapshot.capture(journal)

        assertEquals("false", capture.getValue("limited").jsonPrimitive.content)
        assertEquals("0", capture.getValue("attempted").jsonPrimitive.content)
        assertEquals(0, capture.getValue("records").jsonArray.size)
    }

    @Test
    fun `selection inspection should check sibling evidence in its original scope`() {
        val current = SourceProofContext(context.scope.copy(condition = SourceCondition(
            lower = mapOf(integer to BigFraction.ONE))), context.coordinates, context.premises, context.objective)
        val sibling = evidence.copy(derivation = LinearPinnedObjective(evidence.derivation))

        val inspected = NumericalSelectionSnapshot(Cancellation.Never).evidence(current, sibling)

        assertEquals("Checked", inspected.getValue("freshNativeCheck").jsonPrimitive.content)
        assertEquals("1/2", inspected.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun `selection inspection should distinguish its stop from journal retention limits`() {
        val snapshot = NumericalSelectionSnapshot(Cancellation { true })

        val capture = snapshot.capture(snapshot.journal())

        assertEquals("true", capture.getValue("inspectionStopped").jsonPrimitive.content)
        assertEquals("false", capture.getValue("limited").jsonPrimitive.content)
    }

    @Test
    fun `a captured bound should retain its checked scope and original objective units`() {
        val snapshot = NumericalFrontierSnapshot(context, Cancellation.Never, BigFraction.ofLong(2))

        val result = snapshot.bound(evidence)

        assertEquals("1/2", result.getValue("nativeValue").jsonPrimitive.content)
        assertEquals("1/4", result.getValue("sourceUnitValue").jsonPrimitive.content)
        val lower = result.getValue("scope").jsonObject.getValue("condition").jsonObject
            .getValue("lower").jsonArray.single().jsonObject
        assertEquals("0", lower.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun `a stronger captured claim should fail its certificate check`() {
        val snapshot = NumericalFrontierSnapshot(context, Cancellation.Never)
        val forged = SourceEvidence(evidence.scope, SourceClaim.Bound(SourceValue.Rational(BigFraction.ONE)),
            evidence.derivation)

        assertFailsWith<IllegalStateException> { snapshot.bound(forged) }
    }

    @Test
    fun `a foreign captured revision should fail before certificate reuse`() {
        val snapshot = NumericalFrontierSnapshot(context, Cancellation.Never)
        val foreign = SourceEvidence(evidence.scope.copy(revision = SourceRevision("foreign", "exact")),
            evidence.claim, evidence.derivation)

        assertFailsWith<IllegalStateException> { snapshot.bound(foreign) }
    }
}
