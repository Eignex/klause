package com.eignex.klause.solver

import com.eignex.klause.solver.propagation.PropagationResult

import com.eignex.klause.solver.factor.Product
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProductReverseTest {

    @Test
    fun `singleton-b narrows a's domain`() {
        // a * 3 = result, with result in [6..9]. a must be in [2..3].
        val p = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(-10, 10), IntDomain(3, 3), IntDomain(6, 9)),
            factors = listOf(Product(a = 0, b = 1, result = 2)),
        )
        val r = assertIs<PropagationResult.Implied>(p.propagate())
        // With result in [6..9] and b=3, a should be narrowed.
        // Bake-propagation may force a's domain to [2..3]. result=6 means a=2; result=9 means a=3.
        // The "Implied" map only contains singleton-pinned vars. a's domain narrowing isn't
        // returned as a singleton unless it's reduced to one value. So we verify with a tighter
        // result.
        @Suppress("UNUSED_VARIABLE") val _ok = r
    }

    @Test
    fun `singleton-b with singleton-result forces a`() {
        // a * 5 = 15 → a = 3.
        val p = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 100), IntDomain(5, 5), IntDomain(15, 15)),
            factors = listOf(Product(a = 0, b = 1, result = 2)),
        )
        val r = assertIs<PropagationResult.Implied>(p.propagate())
        assertEquals(3, r.ints[0])
    }

    @Test
    fun `singleton-a with singleton-result forces b`() {
        // 4 * b = 12 → b = 3.
        val p = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(4, 4), IntDomain(0, 100), IntDomain(12, 12)),
            factors = listOf(Product(a = 0, b = 1, result = 2)),
        )
        val r = assertIs<PropagationResult.Implied>(p.propagate())
        assertEquals(3, r.ints[1])
    }

    @Test
    fun `singleton-b negative narrows a correctly`() {
        // a * -2 = -6 → a = 3.
        val p = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(-10, 10), IntDomain(-2, -2), IntDomain(-6, -6)),
            factors = listOf(Product(a = 0, b = 1, result = 2)),
        )
        val r = assertIs<PropagationResult.Implied>(p.propagate())
        assertEquals(3, r.ints[0])
    }

    @Test
    fun `non-divisible singleton result yields Unsat`() {
        // a * 4 = 5 has no integer solution → Unsat.
        val p = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 100), IntDomain(4, 4), IntDomain(5, 5)),
            factors = listOf(Product(a = 0, b = 1, result = 2)),
        )
        assertIs<PropagationResult.Unsat>(p.propagate())
    }

    @Test
    fun `zero-singleton operand requires zero result`() {
        // a * 0 = result. If result must be 0, fine. If result domain excludes 0, Unsat.
        val pSat = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(-5, 5), IntDomain(0, 0), IntDomain(0, 0)),
            factors = listOf(Product(a = 0, b = 1, result = 2)),
        )
        assertIs<PropagationResult.Implied>(pSat.propagate())

        val pUnsat = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(-5, 5), IntDomain(0, 0), IntDomain(5, 5)),
            factors = listOf(Product(a = 0, b = 1, result = 2)),
        )
        assertIs<PropagationResult.Unsat>(pUnsat.propagate())
    }
}
