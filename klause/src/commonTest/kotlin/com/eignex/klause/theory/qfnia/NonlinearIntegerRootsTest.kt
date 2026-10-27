package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NonlinearIntegerRootsTest {
    @Test
    fun `admission should keep exactly the integer roots of a unary equality`() {
        val cases = listOf(
            "(= (* n n) 2)" to emptyList<Int>(),
            "(= (* (- (* n n) 2) (- n 2)) 0)" to listOf(2),
            "(= (* (- (* 2 n) 1) (- n 3)) 0)" to listOf(3),
            "(= (* 4 n n) 1)" to emptyList<Int>(),
            "(= (* (- n 1) (- n 1) (+ n 2)) 0)" to listOf(-2, 1),
            "(= (* n n n) (- 8))" to listOf(-2),
            "(= 0 (* n (- n 5)))" to listOf(0, 5),
        )
        for ((atom, expected) in cases) {
            val store = NonlinearIntegerRootStore(problem("(declare-const n Int) (declare-const q Int) (assert $atom)"))

            val admission = store.admit(Cancellation.Never, PolynomialLimits()) {}

            assertEquals(mapOf(0 to expected.map(::bigIntOf)), admission.roots, atom)
            assertEquals(1L, admission.admitted, atom)
            assertEquals(0L, admission.declined, atom)
        }
    }

    @Test
    fun `equalities on one coordinate should intersect their integer roots`() {
        val store = NonlinearIntegerRootStore(problem(
            "(declare-const n Int) (assert (= (* n n) 4)) (assert (= (* n n n) (- 8)))",
        ))

        val admission = store.admit(Cancellation.Never, PolynomialLimits()) {}

        assertEquals(mapOf(0 to listOf(bigIntOf(-2))), admission.roots)
        assertEquals(2L, admission.admitted)
    }

    @Test
    fun `constant differences and representation limits should decline without roots`() {
        val cases = listOf(
            "(= (- (* n n) (* n n)) 0)" to PolynomialLimits(),
            "(= (- (* n n) (* n n)) 1)" to PolynomialLimits(),
            "(= (^ n 4096) 1)" to PolynomialLimits(),
            "(= (* n n n n n) 1)" to PolynomialLimits(maxDenseCoefficients = 4),
        )
        for ((atom, limits) in cases) {
            val store = NonlinearIntegerRootStore(problem("(declare-const n Int) (assert $atom)"))

            val admission = store.admit(Cancellation.Never, limits) {}

            assertEquals(emptyMap(), admission.roots, atom)
            assertEquals(0L, admission.admitted, atom)
            assertEquals(1L, admission.declined, atom)
        }
    }

    @Test
    fun `guarded negated multivariate and real equalities should not be admitted`() {
        val sources = listOf(
            "(declare-const n Int) (declare-const b Bool) " +
                "(assert (or (and b (= (* n n) 2)) (and (not b) (= (* n n) 4))))",
            "(declare-const n Int) (assert (not (= (* n n) 2)))",
            "(declare-const n Int) (declare-const q Int) (assert (= (* n q) 2))",
            "(declare-const r Real) (declare-const n Int) (assert (= (* r r) 2))",
        )
        for (source in sources) {
            val store = NonlinearIntegerRootStore(problem(source))

            val admission = store.admit(Cancellation.Never, PolynomialLimits()) {}

            assertEquals(emptyMap(), admission.roots, source)
            assertEquals(0L, admission.admitted + admission.declined, source)
        }
    }

    @Test
    fun `a completed admission should be reused without isolating roots again`() {
        val store = NonlinearIntegerRootStore(problem(
            "(declare-const n Int) (assert (= (* (- (* n n) 2) (- n 2)) 0))",
        ))
        var charged = 0L
        val parent = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val first = store.admit(parent, PolynomialLimits()) {}
        val admission = charged
        var steps = 0L

        val reused = store.admit(parent, PolynomialLimits()) { steps++ }

        assertTrue(first.fresh)
        assertFalse(reused.fresh)
        assertSame(first.roots, reused.roots)
        assertTrue(admission > 0)
        assertEquals(admission, charged)
        assertEquals(1L, steps)
    }

    @Test
    fun `a stopped parent should interrupt admission and leave it incomplete`() {
        val store = NonlinearIntegerRootStore(problem("(declare-const n Int) (assert (= (* n n) 2))"))

        assertFailsWith<PolynomialInterrupted> { store.admit(Cancellation { true }, PolynomialLimits()) {} }
        val retried = store.admit(Cancellation.Never, PolynomialLimits()) {}

        assertTrue(retried.fresh)
        assertEquals(mapOf(0 to emptyList<BigInt>()), retried.roots)
    }

    private fun problem(text: String): NonlinearIntegerProblem {
        val parsed = SmtLibPolynomial.parse(text, allowIntegers = true)
        return NonlinearIntegerProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.intVarNames,
            parsed.realVarNames,
            parsed.boolVarNames,
        )
    }
}
