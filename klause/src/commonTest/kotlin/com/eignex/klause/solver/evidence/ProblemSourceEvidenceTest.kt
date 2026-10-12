package com.eignex.klause.solver.evidence

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProblemSourceEvidenceTest {
    private val scope = SourceScope(SourceRevision("original-ir", "klause-ir-exact-v1"))
    private val model = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf(
        Linear(longArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
    ))

    @Test
    fun `source witness checking reads the original factor constraints`() {
        val context = assertNotNull(model.sourceProofContext(scope))
        val evidence = SourceEvidence(scope, SourceClaim.Witness(Sample(booleanArrayOf(), longArrayOf(1))),
            SourceDerivation.SourceWitness)

        assertIs<SourceEvidenceCheck.Rejected>(SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `a complete original source witness has checkable captured coordinates`() {
        val context = assertNotNull(model.sourceProofContext(scope))
        val evidence = SourceEvidence(scope, SourceClaim.Witness(Sample(booleanArrayOf(), longArrayOf(2))),
            SourceDerivation.SourceWitness)

        assertEquals(SourceEvidenceCheck.Checked, SourceEvidenceVerifier(context).verify(evidence))
    }

    @Test
    fun `an invented finite box cannot become an original source premise`() {
        val model = Problem(0, IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(100),
            Bits(1).also { it.set(0) }, Bits(1).also { it.set(0) }), emptyArray())
        val context = assertNotNull(model.sourceProofContext(scope))

        assertFalse("int/0/lower" in context.premises)
        assertFalse("int/0/upper" in context.premises)
    }

    @Test
    fun `the original IR checker cannot relabel different declared source semantics`() {
        val decimal = scope.copy(revision = scope.revision.copy(semantics = "MPS-exact-decimal"))

        assertNull(model.sourceProofContext(decimal))
    }

    @Test
    fun `invocation capture owns the explicit source pins`() {
        val assumptions = Assumptions.None.withInt(0, 2)
        val condition = assumptions.sourceCondition()
        assumptions.intValues[0] = 3

        assertEquals(BigFraction.ofLong(2), condition.lower[SourceCoordinate(SourceCoordinate.Kind.Integer, 0)])
        assertEquals(BigFraction.ofLong(2), condition.upper[SourceCoordinate(SourceCoordinate.Kind.Integer, 0)])
    }
}
