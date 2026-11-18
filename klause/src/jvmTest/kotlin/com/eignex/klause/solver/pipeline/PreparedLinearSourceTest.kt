package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PreparedLinearSourceTest(private val sense: ObjectiveSense) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun directions(): List<ObjectiveSense> = ObjectiveSense.entries
    }

    @Test
    fun `a lowered fractional objective should close its source gap before integer branching`() {
        val sign = if (sense == ObjectiveSense.MINIMIZE) 1L else -1L
        val policy = OptimalityTolerance(SemanticRational.of(BIG_ONE, bigIntOf(20)))
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                lower = SemanticRational.of(BIG_ONE, bigIntOf(2)))),
            LinearSourceObjective(listOf(LinearSourceTerm(0,
                SemanticRational.of(bigIntOf(sign), bigIntOf(10)))),
                constant = SemanticRational.of(bigIntOf(sign), bigIntOf(20)), sense = sense),
            SourceSemantics(SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(SemanticRational.of(BIG_ONE, bigIntOf(10_000_000))),
                optimalityTolerance = policy).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED, maximize = sense == ObjectiveSense.MAXIMIZE))
        val route = assertIs<SourceProblemRoute.OpenTheory>(invocation.engineRoute)
        val request = route.request.withPreparedPlan(route.preparedPlan.linearWorkingPlan(),
            assertNotNull(invocation.linearSource).workingGapTolerance()).withPresolve(PresolveConfig.NONE)
        val context = assertNotNull(request.linearAuthorityContext(ExecutionControl.Never))
        val point = Sample(booleanArrayOf(), longArrayOf(1))
        val objective = assertNotNull(request.minimizedObjective)

        val authority = RationalTheoryDescent(request.model, objective, TheoryParams(sourceCuts = false), context,
            gapTolerance = request.withLinearAuthority(context).gapTolerance,
            external = { point to objective.evaluateExact(point) }).use { descent ->
            assertIs<MinimizeResult.Optimal>(descent.runSlice(ExecutionControl.Never, Long.MAX_VALUE, -1) {})
            assertNotNull(descent.linearResult)
        }

        assertTrue(authority.gapClosed)
        assertEquals(0L, authority.stats.openTheory.openTheoryDecisions)
        val bound = assertIs<SourceClaim.Bound>(assertNotNull(authority.bound).claim)
        assertEquals(BigFraction.ofLong(2 * sign), assertIs<SourceValue.Rational>(bound.value).value)
        assertSame(source.semantics, authority.semantics)
        assertIs<Verification.Accepted<*, *>>(authority.verifyTerminal(context, ExecutionControl.Never,
            gapTolerance = request.gapTolerance))
    }
}
