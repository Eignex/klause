package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@RunWith(Parameterized::class)
class OpenTheoryRequestGapTest(private val policy: OptimalityTolerance?) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "policy={0}")
        fun policies(): List<OptimalityTolerance?> = listOf(null, OptimalityTolerance(),
            OptimalityTolerance(SemanticRational.ONE))
    }

    @Test
    fun `bound source authority should select its declared gap instead of the model policy`() {
        val semantics = SourceSemantics(SourceFormat.IR,
            feasibilityTolerance = SourceTolerance(SemanticRational.ONE),
            optimalityTolerance = OptimalityTolerance(SemanticRational.ONE))
        val model = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray(), semantics = semantics.resolve())
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val context = SourceProofContext(SourceScope(SourceRevision("gap-policy", "exact-affine"),
            objective = SourceObjective("cost", ObjectiveSense.MINIMIZE)), setOf(integer),
            mapOf("lower" to SourcePremise(SourceInequality(mapOf(integer to BigFraction.ofLong(-2)),
                BigFraction.MINUS_ONE))), mapOf(integer to BigFraction.ONE),
            semantics = semantics.copy(optimalityTolerance = policy).resolve())
        val request = OpenTheoryRequest(model, LinearObjective(intCoefficients = longArrayOf(1)))
            .withLinearAuthority(context).withPresolve(PresolveConfig.NONE)
        val point = Sample(booleanArrayOf(), longArrayOf(1))

        val authority = RationalTheoryDescent(model, assertNotNull(request.minimizedObjective),
            TheoryParams(sourceCuts = false), context, gapTolerance = request.gapTolerance,
            external = { point to BigFraction.ONE }).use { descent ->
            assertIs<MinimizeResult.Optimal>(descent.runSlice(ExecutionControl.Never, Long.MAX_VALUE, -1) {})
            assertNotNull(descent.linearResult)
        }

        assertEquals(policy?.isExact == false, authority.gapClosed)
        if (!authority.gapClosed) assertIs<SourceClaim.Optimal>(assertNotNull(authority.terminal).claim)
    }
}
