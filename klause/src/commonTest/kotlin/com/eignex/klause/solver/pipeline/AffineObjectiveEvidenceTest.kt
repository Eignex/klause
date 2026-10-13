package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.sourceCondition
import com.eignex.klause.solver.evidence.sourceResultCapture
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class AffineObjectiveEvidenceTest {
    private val revision = SourceRevision("affine-polynomial", "klause-ir-exact-v1")

    @Test
    fun `affine operation capture preserves original namespaces exact weights and pins`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val objective = LinearObjective(longArrayOf(-3), longArrayOf(7), Long.MIN_VALUE,
            doubleArrayOf(0.1, -0.0))
        val pins = Assumptions.None.withBool(0, true).withInt(0, 1)

        val operation = source.pipelineRequest().affineOperation(objective, ObjectiveSense.MAXIMIZE)
        val capture = assertNotNull(operation.preparedPlan.sourceResultCapture(revision, pins, "cost"))

        assertSame(source, operation.preparedPlan.originalSource)
        assertSame(objective, operation.preparedPlan.objective)
        assertEquals(mapOf(
            SourceCoordinate(SourceCoordinate.Kind.Boolean, 0) to BigFraction.ofLong(-3),
            SourceCoordinate(SourceCoordinate.Kind.Integer, 0) to BigFraction.ofLong(7),
            SourceCoordinate(SourceCoordinate.Kind.Real, 0) to assertNotNull(BigFraction.ofDouble(0.1)),
        ), capture.context.objective)
        assertEquals(BigFraction.ofLong(Long.MIN_VALUE), capture.context.objectiveConstant)
        assertEquals(pins.sourceCondition(), capture.context.scope.condition)
        assertEquals(ObjectiveSense.MAXIMIZE, assertNotNull(capture.context.scope.objective).sense)
    }

    @Test
    fun `changed affine constant cannot reuse an earlier source upper bound`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val original = source.pipelineRequest().affineOperation(objective, ObjectiveSense.MAXIMIZE)
        val first = assertNotNull(original.preparedPlan.sourceResultCapture(revision, objectiveIdentity = "cost"))
        val proof = SourceEvidence(first.context.scope, SourceClaim.Bound(BigFraction.ofLong(8)),
            SourceDerivation.Premise("real/1/upper"))
        val changed = original.affineOperation(objective.copy(constant = 13), ObjectiveSense.MAXIMIZE)
        val next = assertNotNull(changed.preparedPlan.sourceResultCapture(revision, objectiveIdentity = "cost"))

        val firstCheck = SourceEvidenceVerifier(first.context).verify(proof)
        val nextCheck = SourceEvidenceVerifier(next.context).verify(proof)

        assertEquals(SourceEvidenceCheck.Checked, firstCheck)
        assertIs<SourceEvidenceCheck.Rejected>(nextCheck)
    }
}
