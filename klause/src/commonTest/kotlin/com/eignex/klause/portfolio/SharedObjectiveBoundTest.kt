package com.eignex.klause.portfolio

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedObjectiveBoundTest {

    @Test
    fun `runtime bound sharing should preserve the actual native value`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 9)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val bounds = SharedObjectiveBound(identity = model)
        bounds.bindInvocation(invocation)
        val publication = CompletedEnginePublication.ObjectiveBound(invocation, 7.0)

        bounds.publish(publication)
        bounds.publish(100.0, model, EvidenceCertificate.published(publication))

        assertEquals(7.0, bounds.current())
    }

    @Test
    fun `runtime bound sharing should refuse a stronger source invocation`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 9)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val plan = source.prepareExecutionPlan(objective)
        val invocation = EngineInvocation(plan, model, objective)
        val stronger = EngineInvocation(plan, model, objective, sourceAssumptions = Assumptions(ints = mapOf(0 to 9L)))
        val bounds = SharedObjectiveBound(identity = model)
        bounds.bindInvocation(invocation)

        bounds.publish(CompletedEnginePublication.ObjectiveBound(stronger, 9.0))

        assertEquals(Double.NEGATIVE_INFINITY, bounds.current())
    }

    @Test
    fun `publish should keep the maximum`() {
        val bounds = SharedObjectiveBound()
        bounds.publish(3.0)
        bounds.publish(7.0)
        bounds.publish(5.0)
        assertEquals(7.0, bounds.current())
    }

    @Test
    fun `non-finite publications should not change the bound`() {
        val bounds = SharedObjectiveBound()
        bounds.publish(4.0)
        bounds.publish(Double.NaN)
        bounds.publish(Double.NEGATIVE_INFINITY)
        bounds.publish(Double.POSITIVE_INFINITY)
        assertEquals(4.0, bounds.current())
    }

    @Test
    fun `publishing should return how far it raised a finite bound`() {
        val bounds = SharedObjectiveBound()

        val first = bounds.publish(3.0)
        val raise = bounds.publish(7.5)
        val weaker = bounds.publish(5.0)

        assertEquals(listOf(0.0, 4.5, 0.0), listOf(first, raise, weaker))
    }

    @Test
    fun `only a matching certified objective bound should change the shared floor`() {
        val source = Problem(0, 1, arrayOf(IntDomain(7, 9)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val otherObjective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val other = ModelIdentity.of(source, otherObjective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val foreign = EngineInvocation(source.prepareExecutionPlan(otherObjective), other, otherObjective)
        val bounds = SharedObjectiveBound(identity = model)
        bounds.bindInvocation(invocation)
        val wrongObjective = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(foreign, 7.0))
        val point = Sample(booleanArrayOf(), longArrayOf(8))
        val wrongKind = EvidenceCertificate.published(CompletedEnginePublication.Witness(invocation, point, 8.0))

        bounds.publish(7.0, other, wrongObjective)
        bounds.publish(100.0, model)
        bounds.publish(100.0, model, wrongKind)
        assertEquals(Double.NEGATIVE_INFINITY, bounds.current())
        bounds.publish(CompletedEnginePublication.ObjectiveBound(invocation, 7.0))

        assertEquals(7.0, bounds.current())
    }
}
