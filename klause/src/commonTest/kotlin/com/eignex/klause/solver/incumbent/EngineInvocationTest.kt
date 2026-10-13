package com.eignex.klause.solver.incumbent

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineInvocationTest {
    private val source = Problem(1, 1, arrayOf(IntDomain(0, 9)), emptyArray())
    private val objective = LinearObjective(intCoefficients = longArrayOf(1))
    private val plan = source.prepareExecutionPlan(objective)
    private val model = ModelIdentity.of(Any(), objective)

    @Test
    fun `runtime binding retains caller pins independently of mutable assumption arrays`() {
        val assumptions = Assumptions(bools = mapOf(0 to true), ints = mapOf(0 to 3L))
        val binding = EngineInvocation(plan, model, objective, assumptions)
        assumptions.boolValues[0] = false
        assumptions.intValues[0] = 4
        val exported = binding.assumptions
        exported.intValues[0] = 5
        val point = Sample(booleanArrayOf(true), longArrayOf(3))

        val checked = binding.checkWitness(point, Cancellation.Never)

        assertIs<SourceEvidenceCheck.Checked>(checked)
    }

    @Test
    fun `runtime binding cannot import another plan model objective or condition`() {
        val binding = EngineInvocation(plan, model, objective)
        val otherObjective = LinearObjective(intCoefficients = longArrayOf(1))
        val variants = listOf(
            EngineInvocation(source.prepareExecutionPlan(objective), model, objective),
            EngineInvocation(plan, ModelIdentity.of(Any(), objective), objective),
            EngineInvocation(plan, model.forObjective(otherObjective), otherObjective),
            EngineInvocation(plan, model, objective, Assumptions(bools = mapOf(0 to true))),
            EngineInvocation(plan, model, objective, executionAssumptions = Assumptions().withIntHole(0, 3)),
        )

        for (other in variants) assertFalse(binding.matches(other))
        assertTrue(binding.matches(EngineInvocation(plan, model, objective)))
    }

    @Test
    fun `execution deductions remain part of runtime witness admission`() {
        val point = Sample(booleanArrayOf(true), longArrayOf(3))
        val variants = listOf(
            Assumptions.None.withTightenedMin(0, 4),
            Assumptions.None.withTightenedMax(0, 2),
            Assumptions.None.withIntHole(0, 3),
        )

        for (assumptions in variants) {
            val binding = EngineInvocation(plan, model, objective, executionAssumptions = assumptions)

            assertIs<SourceEvidenceCheck.Rejected>(binding.checkWitness(point, Cancellation.Never))
        }
    }

    @Test
    fun `weaker execution assumptions cannot admit a point outside the source query`() {
        val point = Sample(booleanArrayOf(false), longArrayOf(3))
        val variants = listOf(
            Assumptions(bools = mapOf(0 to true)),
            Assumptions.None.withTightenedMin(0, 4),
            Assumptions.None.withIntHole(0, 3),
        )

        for (assumptions in variants) {
            val binding = EngineInvocation(plan, model, objective, assumptions, Assumptions.None)

            assertIs<SourceEvidenceCheck.Rejected>(binding.checkWitness(point, Cancellation.Never))
        }
    }
}
