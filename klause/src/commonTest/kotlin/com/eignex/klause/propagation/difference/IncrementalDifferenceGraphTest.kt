package com.eignex.klause.propagation.difference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncrementalDifferenceGraphTest {

    /** `target − source ≤ bound` per triple, in the given order. */
    private fun graph(numNodes: Int, vararg edges: Triple<Int, Int, Long>) = IncrementalDifferenceGraph(
        numNodes,
        IntArray(edges.size) { edges[it].first },
        IntArray(edges.size) { edges[it].second },
        LongArray(edges.size) { edges[it].third },
    )

    /** The same, with [hubEdges] marked as declared ranges rather than rows. */
    private fun graphWithHub(numNodes: Int, hubEdges: Set<Int>, vararg edges: Triple<Int, Int, Long>) =
        IncrementalDifferenceGraph(
            numNodes,
            IntArray(edges.size) { edges[it].first },
            IntArray(edges.size) { edges[it].second },
            LongArray(edges.size) { edges[it].third },
            BooleanArray(edges.size) { it in hubEdges },
        )

    @Test
    fun `a per-head search does not travel through a declared range`() {
        // Vertex 2 is the constant node. 0 reaches 1 only by way of it, which is exactly the route that
        // makes that node a hub: a search out of 0 must not find 1, or every search spans the graph.
        val g = graphWithHub(3, setOf(0, 1), Triple(0, 2, 1L), Triple(2, 1, 1L))
        assertNull(g.assertEdge(0))
        assertNull(g.assertEdge(1))
        g.shortestPathsFrom(0, intArrayOf(1))
        assertEquals(IncrementalDifferenceGraph.UNREACHABLE, g.distanceTo(1))
    }

    @Test
    fun `the distances through the constant node still measure that route`() {
        val g = graphWithHub(3, setOf(0, 1), Triple(0, 2, 1L), Triple(2, 1, 1L))
        assertNull(g.assertEdge(0))
        assertNull(g.assertEdge(1))
        g.refreshZeroDistances(2)
        assertEquals(1L, g.distanceToZeroFrom(0))
        assertEquals(1L, g.distanceFromZeroTo(1))
        assertEquals(listOf(0), g.pathToZeroFrom(0).toList())
        assertEquals(listOf(1), g.pathFromZeroTo(1).toList())
    }

    @Test
    fun `the assertion closing a negative cycle reports the full cycle at any length`() {
        val cases = listOf(
            2 to arrayOf(Triple(0, 1, -1L), Triple(1, 0, -1L)),
            3 to arrayOf(Triple(0, 1, 1L), Triple(1, 2, 1L), Triple(2, 0, -3L)),
        )
        for ((numNodes, edges) in cases) {
            val g = graph(numNodes, *edges)
            for (e in 0 until edges.size - 1) assertNull(g.assertEdge(e))
            val cycle = assertNotNull(g.assertEdge(edges.size - 1))
            assertEquals((0 until numNodes).toSet(), cycle.toSet())
        }
    }

    @Test
    fun `a refused edge is left out of the system`() {
        val g = graph(2, Triple(0, 1, -1L), Triple(1, 0, -1L))
        g.assertEdge(0)
        g.assertEdge(1)
        assertFalse(g.isActive(1), "the edge that would close the cycle must not stay asserted")
    }

    @Test
    fun `retracting an edge makes the cycle assertable again`() {
        val g = graph(2, Triple(0, 1, -1L), Triple(1, 0, -1L))
        g.assertEdge(0)
        assertNotNull(g.assertEdge(1))
        g.retract(0)
        assertNull(g.assertEdge(1), "with the other side retracted the system is satisfiable")
    }

    @Test
    fun `a shortest path over negative weights is measured exactly and its path reported`() {
        val g = graph(3, Triple(0, 1, -5L), Triple(1, 2, -4L), Triple(0, 2, -2L))
        for (e in 0..2) assertNull(g.assertEdge(e))
        g.shortestPathsFrom(0, intArrayOf(2))
        assertEquals(-9L, g.distanceTo(2), "the two-hop route beats the direct edge")
        assertEquals(setOf(0, 1), g.pathTo(2).toSet())
    }

    @Test
    fun `a retracted edge carries no distance`() {
        val g = graph(2, Triple(0, 1, 1L))
        g.assertEdge(0)
        g.retract(0)
        g.shortestPathsFrom(0, intArrayOf(1))
        assertEquals(IncrementalDifferenceGraph.UNREACHABLE, g.distanceTo(1))
    }

    @Test
    fun `usable reflects whether a potential could be computed without overflow`() {
        assertFalse(
            graph(2, Triple(0, 1, Long.MAX_VALUE / 2), Triple(1, 0, Long.MAX_VALUE / 2)).usable,
            "a potential over these weights would wrap",
        )
        assertTrue(graph(2, Triple(0, 1, 5L)).usable, "an ordinary system is usable")
    }

    /**
     * A system built so that the repair reaches the closing vertex over two routes of different slack,
     * with the vertices on them at different potentials.
     *
     * The scan is ordered by the pending decrease γ, and this is where that choice is observable.
     * Ordering by the resulting potential `π + γ` instead takes the slack route first, because vertex 3
     * sits 20 below vertex 2; vertex 4 then settles one short of what the tight route gives it and has
     * to settle a second time. Both orders reach the same cycle — a vertex whose potential must fall
     * further is simply re-queued — so the difference is the settle count, not the answer.
     */
    private fun divergentRoutes(): Pair<IncrementalDifferenceGraph, Array<Triple<Int, Int, Long>>> {
        val edges = arrayOf(
            Triple(0, 3, -20L), // sinks vertex 3 far below the others
            Triple(0, 4, -10L),
            Triple(1, 2, 0L), //  the tight route: 1 → 2 → 4
            Triple(1, 3, -16L), // the slack route: 1 → 3 → 4
            Triple(3, 4, 10L),
            Triple(2, 4, -10L),
            Triple(4, 0, 12L),
            Triple(0, 1, -5L), //  asserted last, closing 0 → 1 → 2 → 4 → 0 at weight −3
        )
        return graph(5, *edges) to edges
    }

    @Test
    fun `the repair follows the tight route to the cycle settling each vertex at most once`() {
        // The closing vertex is never settled, so one settle per vertex is strictly under the count.
        // Ordering the scan by `π + γ` on this system costs 5 settles over 5 vertices instead of 3.
        val (g, edges) = divergentRoutes()
        for (e in 0..6) assertNull(g.assertEdge(e), "setup edge $e must be consistent")
        val cycle = assertNotNull(g.assertEdge(7), "the tight route closes a negative cycle")
        assertEquals(setOf(7, 2, 5, 6), cycle.toSet(), "the slack route through vertex 3 is not negative")
        assertEquals(-3L, weightOf(edges, cycle))
        assertTrue(g.settlements < g.numNodes, "settled ${g.settlements} times over ${g.numNodes} vertices")
    }

    private fun weightOf(edges: Array<Triple<Int, Int, Long>>, cycle: IntArray): Long = cycle.sumOf { edges[it].third }

    private companion object {
        const val NODES = 6
        const val EDGES = 10
        const val TRIALS = 40
    }
}
