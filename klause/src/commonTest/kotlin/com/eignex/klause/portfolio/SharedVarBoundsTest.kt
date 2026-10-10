package com.eignex.klause.portfolio

import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared globally-valid variable-bound manager keeps, per variable, the tightest lower and upper
 * bound any arm has published — a monotone intersection. Verified in isolation (a deterministic fold).
 */
class SharedVarBoundsTest {

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
