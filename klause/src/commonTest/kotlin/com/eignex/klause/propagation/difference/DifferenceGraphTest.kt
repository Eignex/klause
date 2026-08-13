package com.eignex.klause.propagation.difference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Negative-cycle detection over difference constraints. Beyond "did it fire", the tests check that a
 * reported cycle really is one — its edges form a closed walk of negative total weight — because the
 * cycle is handed to conflict analysis as the explanation, and a wrong explanation is a wrong nogood.
 */
class DifferenceGraphTest {

    /** `x − y ≤ c` for each triple, in insertion order. */
    private fun graph(numVars: Int, vararg edges: Triple<Int, Int, Long>): DifferenceGraph {
        val g = DifferenceGraph(numVars)
        for ((y, x, c) in edges) g.addEdge(y, x, c)
        return g
    }

    @Test
    fun `a satisfiable system reports no cycle`() {
        // x - y <= 3, y - z <= 4: a chain, no cycle at all.
        assertNull(graph(3, Triple(1, 0, 3L), Triple(2, 1, 4L)).negativeCycle())
    }

    @Test
    fun `a zero-weight cycle is satisfiable`() {
        // x - y <= 2 and y - x <= -2 force x - y = 2 exactly; consistent, so no negative cycle.
        assertNull(graph(2, Triple(1, 0, 2L), Triple(0, 1, -2L)).negativeCycle())
    }

    @Test
    fun `a strictly negative two-cycle is detected`() {
        // x - y <= -1 and y - x <= -1 sum to 0 <= -2.
        val cycle = graph(2, Triple(1, 0, -1L), Triple(0, 1, -1L)).negativeCycle()
        assertNotNull(cycle)
        assertEquals(2, cycle.size)
    }

    @Test
    fun `a negative cycle is found across a longer loop`() {
        // Three constraints round a triangle summing to -1.
        val cycle = graph(3, Triple(0, 1, 1L), Triple(1, 2, 1L), Triple(2, 0, -3L)).negativeCycle()
        assertNotNull(cycle)
        assertEquals(3, cycle.size)
    }

    @Test
    fun `the reported edges form a closed negative walk`() {
        val g = DifferenceGraph(3)
        val e0 = g.addEdge(0, 1, 1L)
        val e1 = g.addEdge(1, 2, 1L)
        val e2 = g.addEdge(2, 0, -5L)
        val cycle = g.negativeCycle()
        assertNotNull(cycle)
        assertEquals(setOf(e0, e1, e2), cycle.toSet(), "the explanation must name exactly the cycle")
    }

    @Test
    fun `a disconnected component's cycle is still found`() {
        // Vars 0,1 are an unrelated satisfiable chain; the cycle lives on 2,3, which no path reaches
        // from them — so the search must start from every vertex, not just one.
        val cycle = graph(
            4,
            Triple(0, 1, 5L),
            Triple(2, 3, -1L),
            Triple(3, 2, -1L),
        ).negativeCycle()
        assertNotNull(cycle)
        assertEquals(2, cycle.size)
    }

    @Test
    fun `masking an edge out removes the conflict`() {
        val g = DifferenceGraph(2)
        val e0 = g.addEdge(1, 0, -1L)
        val e1 = g.addEdge(0, 1, -1L)
        assertNotNull(g.negativeCycle(), "both asserted ⇒ conflict")
        val active = booleanArrayOf(true, true)
        active[e1] = false
        assertNull(g.negativeCycle(active), "with one retracted the system is satisfiable")
        assertTrue(e0 == 0)
    }

    @Test
    fun `an empty system is satisfiable`() {
        assertNull(DifferenceGraph(3).negativeCycle())
        assertNull(DifferenceGraph(0).negativeCycle())
    }

    @Test
    fun `an extreme bound does not wrap into a false conflict`() {
        // Weights near the Long extremes must not sum into a phantom negative cycle; a relaxation that
        // would overflow is skipped rather than believed.
        val g = graph(2, Triple(0, 1, Long.MAX_VALUE), Triple(1, 0, Long.MAX_VALUE))
        assertNull(g.negativeCycle(), "two huge positive bounds are satisfiable")
    }
}
