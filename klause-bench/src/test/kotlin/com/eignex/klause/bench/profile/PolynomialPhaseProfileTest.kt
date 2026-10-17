package com.eignex.klause.bench.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialPhaseProfileTest {
    @Test
    fun `phase observations retain subresultant operand shapes and matrix index`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = "$declarations (assert (= (+ (* x0 x0) (* x1 x1)) 1))"

        val trace = PolynomialPhaseProfile.PhaseAccess().measure(source, timeoutMs = 10_000)

        val observed = assertNotNull(trace.lastSubresultants)
        assertEquals("Satisfiable", trace.result)
        assertEquals(trace.nativeWork, trace.chargeSum)
        assertTrue(observed.variable in 0..1)
        assertEquals(0, observed.index)
        assertEquals(2, observed.leftDegree)
        assertEquals(1, observed.rightDegree)
        assertEquals(3, observed.leftTerms)
        assertEquals(1, observed.rightTerms)
        assertEquals(3L, observed.matrixSize)
    }

    @Test
    fun `phase observations remain absent without arithmetic requests`() {
        val trace = PolynomialPhaseProfile.PhaseAccess().measure("(assert false)")

        assertNull(trace.lastSubresultants)
        assertNull(trace.lastDivision)
        assertNull(trace.lastProjection)
    }

    @Test
    fun `phase observations distinguish family pairs and common monomial factors`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = "$declarations (assert (>= (* x0 x1) 0)) (assert (>= (+ (* x0 x1) x1) 0))"

        val trace = PolynomialPhaseProfile.PhaseAccess().measure(source, timeoutMs = 10_000)

        val observed = assertNotNull(trace.lastProjection)
        assertEquals("Satisfiable", trace.result)
        assertEquals(trace.nativeWork, trace.chargeSum)
        assertTrue(observed.variable in 0..1)
        assertEquals(2, observed.polynomials)
        assertEquals(3L, observed.terms)
        assertEquals(mapOf(1 to 2), observed.degrees)
        assertEquals(0, observed.constantLeadingCoefficients)
        assertEquals(2, observed.commonMonomialFactors)
        assertEquals(1L, observed.allInputPairs)
        assertEquals(false, observed.requiredEquation)
    }

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
