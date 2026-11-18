package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IndependentOpenComponentsTest {
    @Test
    fun `an independent arithmetic contradiction should refute an open CP source`() {
        val source = Problem(1, 3, Array(3) { IntDomain(0, 1) }, listOf(
            Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.LE, 0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.GE, 1),
        ), openIntLo = booleanArrayOf(false, false, true), openIntHi = booleanArrayOf(true, false, true))
        assertEquals(ProblemPipeline.UNSUPPORTED_OPEN, source.componentPlan().theoryPipeline)

        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))
        assertIs<SourceProblemRoute.UnsupportedOpen>(invocation.engineRoute)

        val result = assertIs<SourceExecutionResult.Linear>(SourceSetup.execute(SourceExecutionRequest(invocation)))

        assertIs<OpenTheoryResult.Unsat>(assertIs<OpenTheoryExecution.Satisfy>(result.result).result)
    }

    @Test
    fun `a satisfied independent fragment should leave an unowned component undecided`() {
        val source = Problem(1, 3, Array(3) { IntDomain(0, 1) }, listOf(
            Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.LE, 0),
        ), openIntHi = booleanArrayOf(true, false, true))

        val result = OpenTheoryPipeline.searchWithoutTheory(source, TheoryParams(), objective = null,
            engine = FiniteEngine.BACKTRACK)

        val unknown = assertIs<OpenTheoryResult.Unknown>(assertIs<OpenTheoryExecution.Satisfy>(result).result)
        assertEquals(TerminationReason.Unsupported, unknown.reason)
    }

    @Test
    fun `satisfaction should join a complete open coordinate with a heuristic component witness`() {
        val source = Problem(0, 3, Array(3) { IntDomain(0, 1) }, listOf(
            Diffn(intArrayOf(0), intArrayOf(0), longArrayOf(1), longArrayOf(1)),
            Linear(longArrayOf(1), intArrayOf(2), LinearOp.EQ, 1_000_000_000_000),
        ), openIntHi = booleanArrayOf(true, false, true))

        val result = OpenTheoryPipeline.searchWithoutTheory(source, TheoryParams(maxFlips = 100),
            objective = null, engine = FiniteEngine.MIXED)

        val verdict = assertIs<OpenTheoryExecution.Satisfy>(result).result
        val point = assertIs<OpenTheoryResult.Sat>(verdict, (verdict as? OpenTheoryResult.Unknown)?.reason.toString())
            .assignment
        assertEquals("1000000000000", point.intValue(2))
        assertTrue(point.intValue(0).toLong() >= 0L)
        assertEquals("0", point.intValue(1))
    }

    @Test
    fun `component projection should preserve declared integer holes`() {
        val source = Problem(0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2).excludeValue(1)), listOf(
            Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            Linear(intArrayOf(1), intArrayOf(2), LinearOp.EQ, 1),
        ), openIntHi = booleanArrayOf(true, false, false))

        val result = OpenTheoryPipeline.searchWithoutTheory(source, TheoryParams(), objective = null,
            engine = FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryResult.Unsat>(assertIs<OpenTheoryExecution.Satisfy>(result).result)
    }

    @Test
    fun `projection should retain condition coordinates for unused encoding targets`() {
        val source = Problem(1, 3, Array(3) { IntDomain(0, 1) }, listOf(
            Cumulative(intArrayOf(0), longArrayOf(1), longArrayOf(1), 1),
            Linear(intArrayOf(1), intArrayOf(2), LinearOp.EQ, 10),
        ), openIntHi = booleanArrayOf(true, false, true))
        val target = EncodingTarget("conditioned-test")
        val selected = FactorProjectionProviders(encodingTargets = setOf(target),
            encoder = EncoderProvider { factor, language ->
                if (language == target && factor is Linear) {
                    Encoder(EncoderSupport.CONDITIONED, listOf(SourceIntId(1)), mapOf(SourceBoolId(0) to true))
                } else BuiltInEncoderProvider.select(factor, language)
            }).prepare(source)

        val component = assertNotNull(selected.independentOpenComponents(ExecutionControl.Never)).single()
        val result = OpenTheoryPipeline.engineFor(component.request).solve(TheoryParams())

        assertIs<OpenTheoryResult.Sat>(result)
        val encoder = component.projection.selection.encoder(0, target)
        assertEquals(listOf(SourceIntId(1)), encoder.requiredIntegers)
        assertEquals(mapOf(SourceBoolId(0) to true), encoder.requiredBooleans)
    }

    @Test
    fun `a shared Boolean should keep arithmetic rows in their unsupported component`() {
        val source = Problem(1, 3, Array(3) { IntDomain(0, 1) }, listOf(
            Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.LE, 0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.GE, 1),
        ), openIntLo = booleanArrayOf(false, false, true), openIntHi = booleanArrayOf(true, false, true))

        val components = assertNotNull(FactorProjectionProviders.Default.prepare(source)
            .independentOpenComponents(ExecutionControl.Never))

        assertEquals(emptyList(), components)
    }

    @Test
    fun `an independent contradiction should prove infeasibility without an objective cutoff`() {
        val source = Problem(1, 3, Array(3) { IntDomain(0, 1) }, listOf(
            Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.LE, 0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.GE, 1),
        ), openIntLo = booleanArrayOf(false, false, true), openIntHi = booleanArrayOf(true, false, true))

        val result = OpenTheoryPipeline.searchWithoutTheory(source, TheoryParams(),
            objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 1)), engine = FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryOptimum.Infeasible>(assertIs<OpenTheoryExecution.Optimize>(result).result)
    }

    @Test
    fun `a stopped independent check should withhold refutation`() {
        val source = Problem(1, 3, Array(3) { IntDomain(0, 1) }, listOf(
            Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.LE, 0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.GE, 1),
        ), openIntLo = booleanArrayOf(false, false, true), openIntHi = booleanArrayOf(true, false, true))

        val result = OpenTheoryPipeline.searchWithoutTheory(source,
            TheoryParams(cancellation = ExecutionControl { true }), objective = null, engine = FiniteEngine.BACKTRACK)

        val unknown = assertIs<OpenTheoryResult.Unknown>(assertIs<OpenTheoryExecution.Satisfy>(result).result)
        assertEquals(TerminationReason.Cancelled, unknown.reason)
    }
}
