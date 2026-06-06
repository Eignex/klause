package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProductReverseTest {

    @Test
    fun `singleton-b narrows a's domain`() {
        // a * 3 = result, with result in [6..9]. a must be in [2..3].
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-10, 10), IntDomain(3, 3), IntDomain(6, 9)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        assertIs<PropagationResult.Implied>(p.propagate())
        val daAfter = PropagationSession(p).intDomain(0)
        assertEquals(2, daAfter.min, "a.min should be ceil(6/3) = 2; got $daAfter")
        assertEquals(3, daAfter.max, "a.max should be floor(9/3) = 3; got $daAfter")
    }

    @Test
    fun `singleton-b with singleton-result forces a`() {
        // a * 5 = 15 → a = 3.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 100), IntDomain(5, 5), IntDomain(15, 15)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val r = assertIs<PropagationResult.Implied>(p.propagate())
        assertEquals(3, r.ints[0])
    }

    @Test
    fun `singleton-a with singleton-result forces b`() {
        // 4 * b = 12 → b = 3.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(4, 4), IntDomain(0, 100), IntDomain(12, 12)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val r = assertIs<PropagationResult.Implied>(p.propagate())
        assertEquals(3, r.ints[1])
    }

    @Test
    fun `singleton-b negative narrows a correctly`() {
        // a * -2 = -6 → a = 3.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-10, 10), IntDomain(-2, -2), IntDomain(-6, -6)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val r = assertIs<PropagationResult.Implied>(p.propagate())
        assertEquals(3, r.ints[0])
    }

    @Test
    fun `non-divisible singleton result yields Unsat`() {
        // a * 4 = 5 has no integer solution → Unsat.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 100), IntDomain(4, 4), IntDomain(5, 5)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        assertIs<PropagationResult.Unsat>(p.propagate())
    }

    @Test
    fun `non-singleton positive divisor narrows target via corner division`() {
        // a * b = result over a ∈ [-100, 100], b ∈ [2, 4], result ∈ [10, 20].
        // Corner division gives a ∈ [ceil(10/4), floor(20/2)] = [3, 10].
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-100, 100), IntDomain(2, 4), IntDomain(10, 20)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val session = PropagationSession(p)
        val daAfter = session.intDomain(0)
        assertEquals(3, daAfter.min, "a.min should be ceil(10/4) = 3; got $daAfter")
        assertEquals(10, daAfter.max, "a.max should be floor(20/2) = 10; got $daAfter")
    }

    @Test
    fun `non-singleton negative divisor flips bounds correctly`() {
        // a * b = result, b ∈ [-4, -2], result ∈ [10, 20]; corner division yields
        // a.min = min ceil(r/b) = -10 and a.max = max floor(r/b) = -3.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-100, 100), IntDomain(-4, -2), IntDomain(10, 20)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val session = PropagationSession(p)
        val daAfter = session.intDomain(0)
        assertEquals(-10, daAfter.min, "got $daAfter")
        assertEquals(-3, daAfter.max, "got $daAfter")
    }

    @Test
    fun `divisor straddling zero leaves target unbounded by reverse`() {
        // b ∈ [-2, 3] contains 0 — reverse propagation must skip on this divisor side
        // (a/0 is undefined). a's domain endpoints are not on 0 either (-100 / 100), so
        // the zero-exclusion endpoint check doesn't fire. Expect a's domain unchanged.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-100, 100), IntDomain(-2, 3), IntDomain(10, 20)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val session = PropagationSession(p)
        val daAfter = session.intDomain(0)
        assertEquals(-100, daAfter.min, "a.min should not be touched")
        assertEquals(100, daAfter.max, "a.max should not be touched")
    }

    @Test
    fun `zero-result domain excludes zero from non-singleton operands`() {
        // Contiguous-interval domains can only kick 0 out at an endpoint, so a's
        // domain is started at 0 to exercise the endpoint-exclusion path.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(1, 5), IntDomain(10, 20)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        val session = PropagationSession(p)
        val daAfter = session.intDomain(0)
        assertTrue(
            daAfter.min >= 1,
            "a.min=0 should have been pushed up since 0 * b = 0 ∉ result; got $daAfter",
        )
    }

    @Test
    fun `zero-singleton operand requires zero result`() {
        // a * 0 = result. If result must be 0, fine. If result domain excludes 0, Unsat.
        val pSat = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-5, 5), IntDomain(0, 0), IntDomain(0, 0)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        assertIs<PropagationResult.Implied>(pSat.propagate())

        val pUnsat = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(-5, 5), IntDomain(0, 0), IntDomain(5, 5)),
            factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
        )
        assertIs<PropagationResult.Unsat>(pUnsat.propagate())
    }
}
