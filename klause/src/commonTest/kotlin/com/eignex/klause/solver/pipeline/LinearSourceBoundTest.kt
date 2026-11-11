package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.optimization.LinearNumericalClaim
import com.eignex.klause.presolve.ObjectiveAdjustment
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.result.LinearNumericalResult
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinearSourceBoundTest {
    @Test
    fun `numerical source bounds should round outward in original objective units`() {
        for (sense in ObjectiveSense.entries) {
            val source = LinearSourceProblem(
                listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE)),
                emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0,
                    SemanticRational.of(BIG_ONE, bigIntOf(3)))),
                    SemanticRational.of(BIG_ONE, bigIntOf(10)), sense))
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED, maximize = sense == ObjectiveSense.MAXIMIZE))
            val prepared = assertNotNull(invocation.linearSource)
            val scope = SourceScope(SourceRevision("numeric-original-units", "linear"),
                objective = SourceObjective("x", sense))
            val context = SourceProofContext(scope, emptySet(), emptyMap(), emptyMap())
            val budget = SourceLpBudget(solveContext = { LpSolveContext.Numerical })
            val native = if (sense == ObjectiveSense.MINIMIZE) 3.0 else 13.0
            val claim = assertNotNull(LinearNumericalClaim.create(context, native, false, budget, Cancellation.Never))

            val bound = assertNotNull(prepared.qualifyNumericalBound(LinearNumericalResult(claim, null, false), true))

            val expected = BigFraction.of(bigIntOf(if (sense == ObjectiveSense.MINIMIZE) 1 else 13),
                bigIntOf(if (sense == ObjectiveSense.MINIMIZE) 10 else 30))
            val actual = assertNotNull(BigFraction.ofDouble(bound))
            assertTrue(if (sense == ObjectiveSense.MINIMIZE) actual <= expected else actual >= expected)
            assertEquals(expected.toDouble(), bound, 0.000000000000001)
            assertNull(prepared.qualifyNumericalBound(LinearNumericalResult(claim, null, false), false))
        }
    }

    @Test
    fun `finite source bounds should preserve original units and objective direction`() {
        for (sense in ObjectiveSense.entries) {
            val third = SemanticRational.of(BIG_ONE, bigIntOf(3))
            val source = LinearSourceProblem(
                listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE),
                    LinearSourceVariable("r", false, SemanticRational.ZERO, SemanticRational.ONE)),
                listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ONE),
                    LinearSourceTerm(1, SemanticRational.ONE)),
                    lower = SemanticRational.of(BIG_ONE, bigIntOf(2)))),
                LinearSourceObjective(listOf(LinearSourceTerm(0, third), LinearSourceTerm(1, third)),
                    SemanticRational.of(BIG_ONE, bigIntOf(10)), sense),
            )
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE))

            val result = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(SourceExecutionRequest(
                invocation, ExecutionOptions(), presolveConfig = PresolveConfig.NONE)))

            val bound = assertNotNull(result.linearSource?.nativeObjectiveBound)
            val expected = if (sense == ObjectiveSense.MINIMIZE) BigFraction.of(bigIntOf(4), bigIntOf(15))
                else BigFraction.of(bigIntOf(23), bigIntOf(30))
            assertEquals(expected, bound.value)
            assertEquals(sense, bound.sense)
            assertFalse(bound.strict)
        }
    }

    @Test
    fun `finite bound translation should retain strictness through objective adjustment`() {
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val scope = SourceScope(SourceRevision("working", "exact"),
            objective = SourceObjective("cost", ObjectiveSense.MINIMIZE))
        val evidence = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ofLong(2), strict = true),
            SourceDerivation.Unavailable("native claim"))
        val mapping = SourceMapping(model, model, TransformationGuarantees.IDENTITY,
            objectiveAdjustment = ObjectiveAdjustment(BigFraction.ofLong(3), BigFraction.ofLong(5)))

        val bound = assertNotNull(finiteObjectiveBound(evidence, mapping))

        assertEquals(BigFraction.ofLong(11), bound.value)
        assertSame(evidence, bound.evidence)
        assertEquals(true, assertIs<SourceClaim.Bound>(bound.evidence.claim).strict)
    }

    @Test
    fun `a mapping without objective preservation should withhold its bound`() {
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val scope = SourceScope(SourceRevision("working", "exact"),
            objective = SourceObjective("cost", ObjectiveSense.MINIMIZE))
        val evidence = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ONE),
            SourceDerivation.Unavailable("native claim"))
        val mapping = SourceMapping(model, model, TransformationGuarantees.IDENTITY.copy(objective = false))

        val bound = finiteObjectiveBound(evidence, mapping)

        assertNull(bound)
    }

    @Test
    fun `restricted source claims should withhold a nominal global bound`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("r", false, SemanticRational.ZERO, SemanticRational.ONE)),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED))
        val prepared = assertNotNull(invocation.linearSource)
        val scope = SourceScope(SourceRevision("working", "exact"),
            objective = SourceObjective("cost", ObjectiveSense.MINIMIZE))
        val evidence = SourceEvidence(scope, SourceClaim.Bound(BigFraction.ONE),
            SourceDerivation.Unavailable("native claim"))
        val conditioned = SourceEvidence(scope.copy(condition = SourceCondition(lower = mapOf(
            SourceCoordinate(SourceCoordinate.Kind.Real, 0) to BigFraction.ONE))), evidence.claim,
            evidence.derivation)

        assertNull(prepared.qualifyBound(evidence, unrestricted = false))
        assertNull(prepared.qualifyBound(conditioned, unrestricted = true))
    }
}
