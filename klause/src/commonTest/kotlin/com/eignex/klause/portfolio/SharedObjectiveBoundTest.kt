package com.eignex.klause.portfolio

import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared objective lower-bound manager keeps the cross-arm maximum of the bounds arms
 * prove, ignoring non-informative values. Verified in isolation (no executor), since the logic is a
 * deterministic monotone fold.
 */
class SharedObjectiveBoundTest {

    @Test
    fun `current is negative infinity until a bound is published`() {
        assertEquals(Double.NEGATIVE_INFINITY, SharedObjectiveBound().current())
    }

    @Test
    fun `publish keeps the maximum`() {
        val bounds = SharedObjectiveBound()
        bounds.publish(3.0)
        bounds.publish(7.0)
        bounds.publish(5.0) // a weaker bound does not lower the shared maximum
        assertEquals(7.0, bounds.current())
    }

    @Test
    fun `non-finite publications carry no information`() {
        val bounds = SharedObjectiveBound()
        bounds.publish(4.0)
        bounds.publish(Double.NaN)
        bounds.publish(Double.NEGATIVE_INFINITY)
        bounds.publish(Double.POSITIVE_INFINITY)
        assertEquals(4.0, bounds.current(), "only finite bounds update the maximum")
    }

    @Test
    fun `publishing returns how far it raised a finite bound`() {
        val bounds = SharedObjectiveBound()

        val first = bounds.publish(3.0)
        val raise = bounds.publish(7.5)
        val weaker = bounds.publish(5.0)

        assertEquals(listOf(0.0, 4.5, 0.0), listOf(first, raise, weaker))
    }
    @Test
    fun `only a matching certified objective bound changes the shared floor`() {
        val source = Any()
        val model = ModelIdentity.of(source, Any())
        val other = ModelIdentity.of(source, Any())
        val bounds = SharedObjectiveBound(identity = model)

        bounds.publish(100.0, other, EvidenceCertificate.verified(other, EvidenceKind.Bound))
        bounds.publish(100.0, model)
        bounds.publish(100.0, model, EvidenceCertificate.verified(model, EvidenceKind.Infeasible))
        assertEquals(Double.NEGATIVE_INFINITY, bounds.current())
        bounds.publish(7.0, model, EvidenceCertificate.verified(model, EvidenceKind.Bound))

        assertEquals(7.0, bounds.current())
    }
}
