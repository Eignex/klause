package com.eignex.klause.portfolio

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedVarBoundsTest {

    @Test
    fun `runtime integer sharing binds its coordinate and endpoints`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 9) }, emptyArray())
        val model = ModelIdentity.of(source)
        val invocation = EngineInvocation(source.prepareExecutionPlan(), model)
        val bounds = SharedVarBounds(2, identity = model)
        bounds.bindInvocation(invocation)
        val publication = CompletedEnginePublication.IntegerWindow(invocation, 0, 2, 7)

        bounds.publish(publication, origin = 3)
        bounds.publish(1, 2, 7, model = model, certificate = EvidenceCertificate.published(publication))
        bounds.publish(0, 4, 5, model = model, certificate = EvidenceCertificate.published(publication))

        assertEquals(2L, bounds.lowerOf(0))
        assertEquals(7L, bounds.upperOf(0))
        assertEquals(Long.MIN_VALUE, bounds.lowerOf(1))
        assertEquals(Long.MAX_VALUE, bounds.upperOf(1))
        assertEquals(3, bounds.lowerOriginOf(0))
    }

    @Test
    fun `runtime integer sharing refuses a stronger source invocation`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 9)), emptyArray())
        val model = ModelIdentity.of(source)
        val plan = source.prepareExecutionPlan()
        val invocation = EngineInvocation(plan, model)
        val stronger = EngineInvocation(plan, model, sourceAssumptions = Assumptions(ints = mapOf(0 to 9L)))
        val bounds = SharedVarBounds(1, identity = model)
        bounds.bindInvocation(invocation)

        bounds.publish(CompletedEnginePublication.IntegerWindow(stronger, 0, 9, 9))

        assertEquals(Long.MIN_VALUE, bounds.lowerOf(0))
        assertEquals(Long.MAX_VALUE, bounds.upperOf(0))
    }

    @Test
    fun `unset bounds are the open interval`() {
        val vb = SharedVarBounds(numIntVars = 2)
        assertEquals(Long.MIN_VALUE, vb.lowerOf(0))
        assertEquals(Long.MAX_VALUE, vb.upperOf(0))
    }

    @Test
    fun `publish keeps the tightest bound each side`() {
        val vb = SharedVarBounds(numIntVars = 1)
        vb.publish(0, lower = 2, upper = 9)
        vb.publish(0, lower = 4, upper = 7) // tighter both sides
        vb.publish(0, lower = 1, upper = 8) // looser both sides — ignored
        assertEquals(4, vb.lowerOf(0))
        assertEquals(7, vb.upperOf(0))
    }

    @Test
    fun `out-of-range variables are ignored`() {
        val vb = SharedVarBounds(numIntVars = 1)
        vb.publish(5, lower = 0, upper = 0) // no such variable
        assertEquals(Long.MIN_VALUE, vb.lowerOf(5))
        assertEquals(Long.MAX_VALUE, vb.upperOf(5))
    }

    @Test
    fun `each side of a shared bound names the arm that tightened it`() {
        val bounds = SharedVarBounds(1)
        bounds.publish(0, lower = 2, upper = 9, origin = 0)
        bounds.publish(0, lower = 4, upper = 9, origin = 1)

        assertEquals(1, bounds.lowerOriginOf(0))
        assertEquals(0, bounds.upperOriginOf(0))
    }
    @Test
    fun `mismatched and withheld certificates cannot prune shared domains`() {
        val model = ModelIdentity.of(Any())
        val other = ModelIdentity.of(Any())
        val bounds = SharedVarBounds(1, identity = model)

        bounds.publish(0, 8, 8, model = other, certificate = EvidenceCertificate.verified(other, EvidenceKind.Bound))
        bounds.publish(0, 8, 8, model = model)
        assertEquals(Long.MIN_VALUE, bounds.lowerOf(0))
        assertEquals(Long.MAX_VALUE, bounds.upperOf(0))
        bounds.publish(0, 2, 9, model = model, certificate = EvidenceCertificate.verified(model, EvidenceKind.Bound))

        assertEquals(2L, bounds.lowerOf(0))
        assertEquals(9L, bounds.upperOf(0))
    }
}
