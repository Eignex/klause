package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.theory.qfnra.QfNraStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuadraticNativeScopeTest {
    @Test
    fun `native observation aggregation should saturate without polling or charging`() {
        var polls = 0
        var charges = 0L
        val scope = QuadraticNativeScope(
            PolynomialTheoryParams(
                cancellation = object : Cancellation {
                    override fun isCancelled(): Boolean {
                        polls++
                        return false
                    }
                    override fun workMeter(): WorkMeter = WorkMeter { charges += it }
                },
            ),
        )
        val first = scope.worker(0)
        val second = scope.worker(1)
        val empty = scope.polynomialStats

        first.observe(QfNraStats(0, 0, 0, Long.MAX_VALUE, 0, 1, 1))
        val captured = scope.polynomialStats
        second.observe(QfNraStats(0, 0, 0, 1, 1, 0, 0))
        val combined = scope.polynomialStats

        assertEquals(0, polls)
        assertEquals(0L, charges)
        assertNull(empty.dyadicAttempts)
        assertEquals(Long.MAX_VALUE, captured.dyadicAttempts)
        assertEquals(0L, captured.dyadicBases)
        assertEquals(Long.MAX_VALUE, combined.dyadicAttempts)
        assertEquals(1L, combined.dyadicBases)
        assertEquals(1L, combined.coupledWorkDeclines)
        assertEquals(1L, combined.dyadicPreExtractionDeclines)
        assertEquals(1L, second.polynomialStats.dyadicAttempts)
    }

    @Test
    fun `worker native charges should reach the containing meter once`() {
        var forwarded = 0L
        val lifetime = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { forwarded += it }
        }
        val scope = QuadraticNativeScope(PolynomialTheoryParams(cancellation = lifetime))
        val complete = scope.worker(0)
        val heuristic = scope.worker(1)

        complete.polynomial.charge(3)
        complete.numerical.charge(2)
        heuristic.polynomial.charge(7)
        complete.observe(QfNraStats(1, 2, 3))
        heuristic.observe(QfNraStats(4, 5, 7))

        assertEquals(12L, forwarded)
        assertEquals(12L, scope.work)
        assertEquals(5L, complete.work)
        assertEquals(7L, heuristic.work)
        assertEquals(PolynomialTheoryStats(5, 7, 10), scope.polynomialStats)
        assertEquals(PolynomialTheoryStats(1, 2, 3), complete.polynomialStats)
    }

    @Test
    fun `containing exhaustion should stop siblings without renewing arithmetic allowance`() {
        val scope = QuadraticNativeScope(PolynomialTheoryParams(maximumWork = 4))
        val complete = scope.worker(0)
        val heuristic = scope.worker(1)

        complete.polynomial.charge(2)
        assertFalse(heuristic.stopped)
        heuristic.numerical.charge(3)

        assertTrue(complete.stopped)
        assertTrue(heuristic.stopped)
        assertTrue(scope.worker(2).stopped)
        assertEquals(5L, scope.work)
    }

    @Test
    fun `retained worker quota should not take unused sibling allowance`() {
        val scope = QuadraticNativeScope(PolynomialTheoryParams(maximumWork = 100))
        val complete = scope.worker(0, maximumWork = 2)
        val heuristic = scope.worker(1, maximumWork = 3)

        complete.polynomial.charge(2)

        assertTrue(complete.stopped)
        assertTrue(scope.worker(0, maximumWork = 2).stopped)
        assertFailsWith<IllegalArgumentException> { scope.worker(0, maximumWork = 3) }
        assertFalse(heuristic.stopped)
        assertFalse(scope.stopped)
        assertEquals(2L, complete.work)
    }

    @Test
    fun `worker representation decline should preserve another worker native lifetime`() {
        val scope = QuadraticNativeScope(PolynomialTheoryParams())
        val heuristic = scope.worker(1)
        val complete = scope.worker(0)

        heuristic.decline(PolynomialResource.MATRIX_CELLS)

        assertTrue(heuristic.stopped)
        assertFalse(complete.stopped)
        assertFalse(scope.stopped)
        assertEquals(PolynomialTheoryResource.MatrixCells, heuristic.resource)
    }
}
