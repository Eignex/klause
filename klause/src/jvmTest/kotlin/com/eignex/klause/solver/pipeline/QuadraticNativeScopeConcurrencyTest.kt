package com.eignex.klause.solver.pipeline

import com.eignex.klause.theory.qfnra.QfNraStats
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuadraticNativeScopeConcurrencyTest {
    @Test
    fun `parallel native charges should retain every work unit in the containing allowance`() {
        val scope = QuadraticNativeScope(PolynomialTheoryParams(maximumWork = 800))
        val workers = List(4) { scope.worker(it) }

        val threads = workers.map { worker -> thread {
            repeat(100) {
                worker.polynomial.charge(1)
                worker.numerical.charge(1)
            }
        } }
        threads.forEach { it.join() }

        assertEquals(800L, scope.work)
        assertEquals(400L, scope.numericalWork)
        assertEquals(400L, scope.polynomialStats.workSpent)
        assertTrue(workers.all { it.work == 200L && it.stopped })
    }

    @Test
    fun `parallel native observations should retain optional totals and saturation`() {
        val scope = QuadraticNativeScope(PolynomialTheoryParams())
        val workers = List(4) { scope.worker(it) }

        val threads = workers.map { worker -> thread {
            repeat(100) { worker.observe(QfNraStats(1, 2, 0, Long.MAX_VALUE, 1, 0, 1)) }
        } }
        threads.forEach { it.join() }

        val stats = scope.polynomialStats
        assertEquals(400L, stats.projectedPolynomials)
        assertEquals(800L, stats.liftedCells)
        assertEquals(Long.MAX_VALUE, stats.dyadicAttempts)
        assertEquals(400L, stats.dyadicBases)
        assertEquals(0L, stats.coupledWorkDeclines)
        assertEquals(400L, stats.dyadicPreExtractionDeclines)
    }
}
