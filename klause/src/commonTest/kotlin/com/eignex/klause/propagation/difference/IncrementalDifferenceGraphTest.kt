package com.eignex.klause.propagation.difference

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The difference system under assertion and retraction. The invariant under test is that the structure
 * answers the same questions a from-scratch cycle search would, in whatever order the search asserts and
 * retracts — a stale potential or a leftover edge would show up as a cycle the system does not contain.
 */
class IncrementalDifferenceGraphTest {

    /** `target − source ≤ bound` per triple, in the given order. */
    private fun graph(numNodes: Int, vararg edges: Triple<Int, Int, Long>) = IncrementalDifferenceGraph(
        numNodes,
        IntArray(edges.size) { edges[it].first },
        IntArray(edges.size) { edges[it].second },
        LongArray(edges.size) { edges[it].third },
    )

    @Test
    fun `a chain of assertions stays consistent`() {
        val g = graph(3, Triple(0, 1, 3L), Triple(1, 2, 4L))
        assertNull(g.assertEdge(0))
        assertNull(g.assertEdge(1))
    }

    @Test
    fun `a zero-weight cycle is consistent`() {
        val g = graph(2, Triple(0, 1, 2L), Triple(1, 0, -2L))
        assertNull(g.assertEdge(0))
        assertNull(g.assertEdge(1))
    }

    @Test
    fun `the assertion closing a negative cycle reports it`() {
        val g = graph(2, Triple(0, 1, -1L), Triple(1, 0, -1L))
        assertNull(g.assertEdge(0))
        assertEquals(setOf(0, 1), assertNotNull(g.assertEdge(1)).toSet())
    }

    @Test
    fun `a negative cycle is reported across a longer loop`() {
        val g = graph(3, Triple(0, 1, 1L), Triple(1, 2, 1L), Triple(2, 0, -3L))
        assertNull(g.assertEdge(0))
        assertNull(g.assertEdge(1))
        assertEquals(setOf(0, 1, 2), assertNotNull(g.assertEdge(2)).toSet())
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
    fun `re-asserting after a retraction finds the same cycle`() {
        val g = graph(3, Triple(0, 1, 1L), Triple(1, 2, 1L), Triple(2, 0, -3L))
        g.assertEdge(0)
        g.assertEdge(1)
        assertNotNull(g.assertEdge(2))
        g.retract(1)
        assertNull(g.assertEdge(2))
        assertNotNull(g.assertEdge(1), "the cycle is back once the retracted edge returns")
    }

    @Test
    fun `a shortest path over negative weights is measured exactly`() {
        val g = graph(3, Triple(0, 1, -5L), Triple(1, 2, -4L), Triple(0, 2, -2L))
        for (e in 0..2) assertNull(g.assertEdge(e))
        g.shortestPathsFrom(0, intArrayOf(2))
        assertEquals(-9L, g.distanceTo(2), "the two-hop route beats the direct edge")
    }

    @Test
    fun `the reported path is the one that carries the distance`() {
        val g = graph(3, Triple(0, 1, -5L), Triple(1, 2, -4L), Triple(0, 2, -2L))
        for (e in 0..2) assertNull(g.assertEdge(e))
        g.shortestPathsFrom(0, intArrayOf(2))
        assertEquals(setOf(0, 1), g.pathTo(2).toSet())
    }

    @Test
    fun `an unreached vertex has no distance`() {
        val g = graph(3, Triple(0, 1, 1L))
        assertNull(g.assertEdge(0))
        g.shortestPathsFrom(0, intArrayOf(2))
        assertEquals(IncrementalDifferenceGraph.UNREACHABLE, g.distanceTo(2))
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
    fun `a system whose weights cannot be summed inside Long deduces nothing`() {
        val g = graph(2, Triple(0, 1, Long.MAX_VALUE / 2), Triple(1, 0, Long.MAX_VALUE / 2))
        assertFalse(g.usable, "a potential over these weights would wrap")
    }

    @Test
    fun `an ordinary system is usable`() {
        assertTrue(graph(2, Triple(0, 1, 5L)).usable)
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
    fun `the repair follows the tight route to the cycle and not the slack one`() {
        val (g, edges) = divergentRoutes()
        for (e in 0..6) assertNull(g.assertEdge(e), "setup edge $e must be consistent")
        val cycle = assertNotNull(g.assertEdge(7), "the tight route closes a negative cycle")
        assertEquals(setOf(7, 2, 5, 6), cycle.toSet(), "the slack route through vertex 3 is not negative")
        assertEquals(-3L, weightOf(edges, cycle))
    }

    @Test
    fun `the repair settles each vertex at most once`() {
        // The closing vertex is never settled, so one settle per vertex is strictly under the count.
        // Ordering the scan by `π + γ` on this system costs 5 settles over 5 vertices instead of 3.
        val (g, _) = divergentRoutes()
        for (e in 0..6) g.assertEdge(e)
        g.assertEdge(7)
        assertTrue(g.settlements < g.numNodes, "settled ${g.settlements} times over ${g.numNodes} vertices")
    }

    @Test
    fun `every assertion leaves a potential that solves the asserted system`() {
        val rng = Random(20260815)
        repeat(TRIALS) {
            val edges = Array(EDGES) {
                Triple(rng.nextInt(NODES), rng.nextInt(NODES), rng.nextLong(-4L, 5L))
            }
            val g = graph(NODES, *edges)
            repeat(3 * EDGES) {
                val e = rng.nextInt(EDGES)
                if (g.isActive(e)) g.retract(e) else g.assertEdge(e)
                for (k in 0 until EDGES) {
                    if (!g.isActive(k)) continue
                    val (s, t, w) = edges[k]
                    assertTrue(
                        g.potentialOf(s) + w >= g.potentialOf(t),
                        "edge $k is violated by the potential the repair left",
                    )
                }
            }
        }
    }

    @Test
    fun `an arbitrary assert and retract sequence agrees with a from-scratch cycle search`() {
        val rng = Random(20260813)
        repeat(TRIALS) {
            val edges = Array(EDGES) {
                Triple(rng.nextInt(NODES), rng.nextInt(NODES), rng.nextLong(-4L, 5L))
            }
            val g = graph(NODES, *edges)
            val active = BooleanArray(EDGES)
            repeat(3 * EDGES) {
                val e = rng.nextInt(EDGES)
                if (active[e]) {
                    g.retract(e)
                    active[e] = false
                } else {
                    active[e] = true
                    val closes = oracle(edges).negativeCycle(active.copyOf()) != null
                    val reported = g.assertEdge(e)
                    assertEquals(closes, reported != null, "verdict on asserting edge $e")
                    if (reported != null) {
                        active[e] = false
                        assertTrue(weightOf(edges, reported) < 0L, "the reported cycle must be negative")
                    }
                }
            }
        }
    }

    @Test
    fun `a reported distance matches an exhaustive relaxation`() {
        val rng = Random(20260814)
        repeat(TRIALS) {
            val edges = Array(EDGES) {
                Triple(rng.nextInt(NODES), rng.nextInt(NODES), rng.nextLong(-4L, 5L))
            }
            val g = graph(NODES, *edges)
            val active = BooleanArray(EDGES)
            for (e in 0 until EDGES) {
                if (g.assertEdge(e) == null) active[e] = true
            }
            val origin = rng.nextInt(NODES)
            val expected = relax(edges, active, origin)
            g.shortestPathsFrom(origin, IntArray(NODES) { it })
            for (n in 0 until NODES) assertEquals(expected[n], g.distanceTo(n), "distance $origin to $n")
        }
    }

    /** A fresh Bellman-Ford graph over the same edges, as the from-scratch verdict to compare against. */
    private fun oracle(edges: Array<Triple<Int, Int, Long>>): DifferenceGraph {
        val g = DifferenceGraph(NODES)
        for ((s, t, w) in edges) g.addEdge(s, t, w)
        return g
    }

    private fun weightOf(edges: Array<Triple<Int, Int, Long>>, cycle: IntArray): Long = cycle.sumOf { edges[it].third }

    /** Shortest-path weights from [origin] over the active edges by repeated relaxation. */
    private fun relax(edges: Array<Triple<Int, Int, Long>>, active: BooleanArray, origin: Int): LongArray {
        val d = LongArray(NODES) { IncrementalDifferenceGraph.UNREACHABLE }
        d[origin] = 0L
        repeat(NODES) {
            for (e in 0 until EDGES) {
                if (!active[e]) continue
                val (s, t, w) = edges[e]
                if (d[s] == IncrementalDifferenceGraph.UNREACHABLE) continue
                if (d[s] + w < d[t]) d[t] = d[s] + w
            }
        }
        return d
    }

    private companion object {
        const val NODES = 6
        const val EDGES = 10
        const val TRIALS = 40
    }
}
