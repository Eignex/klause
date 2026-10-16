package com.eignex.klause.bench.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialPhaseProfileTest {
    @Test
    fun `a negated conjunction declines conjunctive guidance after normalization`() {
        val source = """
            (declare-const x Real)
            (assert (not (and (> x 0) (< (* x x) 2))))
        """.trimIndent()

        val gate = PolynomialPhaseProfile.PhaseAccess().gate(source)

        assertEquals(1, gate.realCount)
        assertEquals(2, gate.atoms)
        assertNull(gate.conjunctiveAtoms)
        assertEquals(1, gate.normalizedNodeKinds["Not"])
    }

    @Test
    fun `native work exhaustion retains its reason and exact caller charges`() {
        val source = "(declare-const x Real) (assert (> (* x x) 2))"

        val trace = PolynomialPhaseProfile.PhaseAccess().measure(source, maximumWork = 1, timeoutMs = 10_000)

        assertEquals("Unknown", trace.result)
        assertEquals("WorkLimit", trace.reason)
        assertEquals(trace.nativeWork, trace.chargeSum)
        assertNull(trace.containingDeadlineStack)
        assertTrue(trace.checkpoints.isNotEmpty())
    }

    @Test
    fun `a completed refutation retains normalization probe and completion checkpoints`() {
        val trace = PolynomialPhaseProfile.PhaseAccess().measure("(assert false)")

        assertEquals("Unsatisfiable", trace.result)
        assertEquals(trace.nativeWork, trace.chargeSum)
        assertEquals("Pending", trace.checkpoints.first().result)
        assertEquals("Pending", trace.checkpoints[1].result)
        assertNull(trace.containingDeadlineStack)
    }
}
