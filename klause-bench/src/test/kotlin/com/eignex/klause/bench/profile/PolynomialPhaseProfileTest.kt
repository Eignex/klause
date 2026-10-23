package com.eignex.klause.bench.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialPhaseProfileTest {
    @Test
    fun `query checkpoints retain committed reductions without changing charge accounting`() {
        val source = "(declare-const x Real) (declare-const y Real) " +
            "(assert (and (= x (+ (* y y) 1)) (= y 2)))"

        val trace = PolynomialPhaseProfile.PhaseAccess().measure(source)

        assertEquals("Satisfiable", trace.result)
        assertEquals(trace.nativeWork, trace.chargeSum)
        assertTrue(trace.checkpoints.any { it.progress?.contains("snapshots=1,") == true })
        assertTrue(trace.checkpoints.last().progress?.contains("rootFinishedWork=") == true)
    }

    @Test
    fun `pipeline dimensions distinguish declarations from product lowering coordinates`() {
        val source = "(declare-const x Real) (assert (= (* x x) 2))"

        val trace = PolynomialPhaseProfile.pipeline(source, timeoutMs = 10_000)

        val shape = requireNotNull(trace.sourceShape)
        assertEquals("POLYNOMIAL", shape.preparedKind)
        assertEquals(1, shape.declaredReals)
        assertEquals(0, shape.declaredBooleans)
        val modelReals = requireNotNull(shape.modelReals)
        assertTrue(modelReals > shape.declaredReals)
        assertEquals(0, shape.modelBooleans)
        assertTrue(requireNotNull(shape.theoryReals) >= modelReals)
        assertEquals(shape.modelBooleans, shape.theoryBooleans)
        assertEquals("Sat", trace.result)
    }

    @Test
    fun `pipeline tracing preserves a linear control without polynomial attribution`() {
        val source = "(declare-const x Real) (assert (> x 0))"

        val trace = PolynomialPhaseProfile.pipeline(source, timeoutMs = 10_000)

        val shape = requireNotNull(trace.sourceShape)
        assertEquals(1, shape.declaredReals)
        assertEquals(1, shape.modelReals)
        assertNull(shape.theoryReals)
        assertNull(shape.theoryBooleans)
        assertEquals("not a polynomial execution", trace.nativeStats)
        assertEquals("Sat", trace.result)
    }

    @Test
    fun `linear refutations retain unused declared coordinates`() {
        val source = "(declare-const x Real) (assert false)"

        val trace = PolynomialPhaseProfile.pipeline(source, timeoutMs = 10_000)

        val shape = requireNotNull(trace.sourceShape)
        assertEquals("Unsat", trace.result)
        assertEquals(1, shape.declaredReals)
        assertNull(shape.theoryReals)
        assertEquals("not a polynomial execution", trace.nativeStats)
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
    fun `a completed refutation retains normalization and terminal checkpoints`() {
        val trace = PolynomialPhaseProfile.PhaseAccess().measure("(assert false)")

        assertEquals("Unsatisfiable", trace.result)
        assertEquals(trace.nativeWork, trace.chargeSum)
        assertEquals("Pending", trace.checkpoints.first().result)
        assertEquals("Unsatisfiable", trace.checkpoints.last().result)
        assertTrue(trace.checkpoints.dropLast(1).all { it.result == "Pending" })
        assertNull(trace.containingDeadlineStack)
    }
}
