package com.eignex.klause.portfolio

import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedSolutionPoolTest {

    // A solution tagged by an int value, so we can identify which assignment the pool returns.
    private fun tagged(tag: Int) = Sample(booleanArrayOf(), longArrayOf(tag.toLong()))

    @Test
    fun `best is the lowest-objective solution`() {
        val pool = SharedSolutionPool()
        pool.publish(tagged(1), 5.0)
        pool.publish(tagged(2), 3.0)
        pool.publish(tagged(3), 8.0)
        assertEquals(3.0, pool.bestObjective())
        assertEquals(2L, pool.best()!!.ints[0])
    }

    @Test
    fun `capacity keeps the lowest-objective solutions and evicts the worst`() {
        val pool = SharedSolutionPool(capacity = 2)
        pool.publish(tagged(1), 5.0)
        pool.publish(tagged(2), 3.0)
        pool.publish(tagged(3), 8.0)
        assertEquals(listOf(2L, 1L), pool.all().map { it.ints[0] }, "keeps 3.0 and 5.0 best-first, evicts 8.0")
    }

    @Test
    fun `a duplicate objective is dropped`() {
        val pool = SharedSolutionPool()
        pool.publish(tagged(1), 4.0)
        pool.publish(tagged(2), 4.0)
        assertEquals(1, pool.all().size)
        assertEquals(1L, pool.best()!!.ints[0], "the first solution at that objective is retained")
    }

    @Test
    fun `a non-finite objective is ignored`() {
        val pool = SharedSolutionPool()
        pool.publish(tagged(1), Double.POSITIVE_INFINITY)
        pool.publish(tagged(2), Double.NaN)
        assertNull(pool.best())
        assertEquals(Double.POSITIVE_INFINITY, pool.bestObjective())
    }

    @Test
    fun `an empty pool has no best`() {
        assertNull(SharedSolutionPool().best())
    }
}
