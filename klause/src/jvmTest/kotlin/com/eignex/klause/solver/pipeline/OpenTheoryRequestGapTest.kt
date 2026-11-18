package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.presolve.PresolveConfig
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

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
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            semantics = semantics.resolve())
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val context = SourceProofContext(SourceScope(SourceRevision("gap-policy", "exact-affine"),
            objective = SourceObjective("cost", ObjectiveSense.MINIMIZE)), setOf(real),
            mapOf("lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE))), mapOf(real to BigFraction.ONE),
            semantics = semantics.copy(optimalityTolerance = policy).resolve())
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)))
            .withLinearAuthority(context).withPresolve(PresolveConfig.NONE)

        val authority = RationalTheoryDescent(model, assertNotNull(request.minimizedObjective), TheoryParams(),
            context, gapTolerance = request.gapTolerance).use { descent ->
            assertIs<MinimizeResult.Optimal>(descent.runSlice(ExecutionControl.Never, Long.MAX_VALUE, -1) {})
            assertNotNull(descent.linearResult)
        }

        assertEquals(policy?.isExact == false, authority.gapClosed)
        if (!authority.gapClosed) assertIs<SourceClaim.Optimal>(assertNotNull(authority.terminal).claim)
    }
}
