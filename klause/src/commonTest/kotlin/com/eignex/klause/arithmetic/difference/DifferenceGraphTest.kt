package com.eignex.klause.arithmetic.difference

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Conflict explanations require a closed cycle with negative total weight.
class DifferenceGraphTest {

    @Test
    fun `exact path queries retain original edges across feasible reweighting`() {
        val source = intArrayOf(0, 1)
        val edges = DifferencePathEdges(source, intArrayOf(1, 2), longArrayOf(-2, -3),
            intArrayOf(0, 1, 2, 2), intArrayOf(0, 1))
        val scratch = DifferencePathScratch(3)
        for (potential in listOf(longArrayOf(0, -2, -5), longArrayOf(0, -4, -9))) {
            val result = differenceShortestPaths(edges, booleanArrayOf(true, true), potential,
                0, intArrayOf(2), scratch)

            assertEquals(DifferencePathCompletion.Completed, result)
            assertEquals(listOf(1, 0), scratch.pathTo(2, source)?.toList())
        }
    }

    @Test
    fun `a cancelled path query withholds a previously completed path and retains its work`() {
        val source = intArrayOf(0, 1)
        val edges = DifferencePathEdges(source, intArrayOf(1, 2), longArrayOf(-2, -3),
            intArrayOf(0, 1, 2, 2), intArrayOf(0, 1))
        val scratch = DifferencePathScratch(3)
        val point = longArrayOf(0, -2, -5)
        assertEquals(DifferencePathCompletion.Completed,
            differenceShortestPaths(edges, booleanArrayOf(true, true), point, 0, intArrayOf(2), scratch))
        var work = 0L
        val meter = WorkMeter { work += it }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 3L
            override fun workMeter(): WorkMeter = meter
        }

        val result = differenceShortestPaths(edges, booleanArrayOf(true, true), point,
            0, intArrayOf(2), scratch, stop)

        assertEquals(DifferencePathCompletion.Abandoned, result)
        assertNull(scratch.pathTo(2, source))
        assertTrue(work >= 3L)
    }

    @Test
    fun `invalid reduced weights and overflowing transforms supply no path`() {
        val source = intArrayOf(0)
        for ((weight, point) in listOf(
            -1L to longArrayOf(0, 0),
            Long.MIN_VALUE to longArrayOf(0, Long.MIN_VALUE),
            Long.MAX_VALUE to longArrayOf(1, 0),
            0L to longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE),
        )) {
            val edges = DifferencePathEdges(source, intArrayOf(1), longArrayOf(weight),
                intArrayOf(0, 1, 1), intArrayOf(0))
            val scratch = DifferencePathScratch(2)

            val result = differenceShortestPaths(edges, booleanArrayOf(true), point, 0, intArrayOf(1), scratch)

            assertEquals(DifferencePathCompletion.Declined, result)
            assertNull(scratch.pathTo(1, source))
        }
    }

    @Test
    fun `a completed cycle sweep retains potentials for its active edges`() {
        val graph = graph(3, Triple(0, 1, -2L), Triple(1, 2, -3L), Triple(2, 0, -1L))
        var values: LongArray? = null

        val cycle = graph.negativeCycleWithPotential(booleanArrayOf(true, true, false), { false }) { values = it }

        assertNull(cycle)
        val point = assertNotNull(values)
        assertTrue(point[1] <= point[0] - 2L)
        assertTrue(point[2] <= point[1] - 3L)
    }

    @Test
    fun `a negative cycle supplies no feasible potential`() {
        var values: LongArray? = null

        val cycle = graph(2, Triple(0, 1, -1L), Triple(1, 0, -1L))
            .negativeCycleWithPotential(null, { false }) { values = it }

        assertNotNull(cycle)
        assertNull(values)
    }

    @Test
    fun `stopped and overflowing sweeps withhold potential guidance`() {
        for (cancelled in listOf(false, true)) {
            var values: LongArray? = null

            val cycle = graph(3, Triple(0, 1, Long.MIN_VALUE), Triple(1, 2, -1L))
                .negativeCycleWithPotential(null, { cancelled }) { values = it }

            assertNull(cycle)
            assertNull(values)
        }
    }

    /** `x − y ≤ c` for each triple, in insertion order. */
    private fun graph(numVars: Int, vararg edges: Triple<Int, Int, Long>): DifferenceGraph {
        val g = DifferenceGraph(numVars)
        for ((y, x, c) in edges) g.addEdge(y, x, c)
        return g
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
    fun `potentials satisfy nonnegative cycles with incoming paths`() {
        for (returnWeight in listOf(2L, 3L)) {
            val g = graph(4, Triple(3, 2, -4L), Triple(2, 0, -3L),
                Triple(0, 1, -2L), Triple(1, 0, returnWeight))

            val values = assertIs<Potentials.Found>(g.potentials(initial = longArrayOf(100, 0, 500, -100))).values

            assertTrue(values[2] <= values[3] - 4L)
            assertTrue(values[0] <= values[2] - 3L)
            assertTrue(values[1] <= values[0] - 2L)
            assertTrue(values[0] <= values[1] + returnWeight)
        }
    }

    @Test
    fun `potentials detect a negative cycle beside an acyclic component`() {
        val g = graph(4, Triple(3, 2, -4L), Triple(0, 1, -2L), Triple(1, 0, 1L))

        var cycle: IntArray? = null

        val result = g.potentials(initial = longArrayOf(25, 0, 300, -70), onNegativeCycle = { cycle = it })

        assertEquals(Potentials.Infeasible, result)
        assertEquals(setOf(1, 2), assertNotNull(cycle).toSet())
    }

    @Test
    fun `potential cycle capture excludes inactive predecessor edges`() {
        val g = graph(3, Triple(0, 1, -2L), Triple(1, 0, 1L), Triple(1, 2, -5L))
        var cycle: IntArray? = null

        val result = g.potentials(booleanArrayOf(true, false, true), onNegativeCycle = { cycle = it })

        assertIs<Potentials.Found>(result)
        assertNull(cycle)
    }

    @Test
    fun `an overflowing initial potential supplies no witness`() {
        val g = graph(2, Triple(0, 1, -1L))

        val result = g.potentials(initial = longArrayOf(Long.MIN_VALUE, 0L))

        assertEquals(Potentials.Abandoned, result)
    }

    @Test
    fun `an interrupted predecessor walk exports no cycle`() {
        for (transient in listOf(false, true)) {
            val graph = DifferenceGraph(9_000)
            repeat(9_000) { graph.addEdge(it, (it + 1) % 9_000, -1L) }
            var polls = 0
            var cycle: IntArray? = null

            val result = graph.potentials(cancelled = {
                polls++
                if (transient) polls == 4 else polls >= 4
            }, onNegativeCycle = { cycle = it })

            assertEquals(Potentials.Abandoned, result)
            assertNull(cycle)
        }
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
    fun `an extreme bound does not wrap into a false conflict`() {
        // Weights near the Long extremes must not sum into a phantom negative cycle; a relaxation that
        // would overflow is skipped rather than believed.
        val g = graph(2, Triple(0, 1, Long.MAX_VALUE), Triple(1, 0, Long.MAX_VALUE))
        assertNull(g.negativeCycle(), "two huge positive bounds are satisfiable")
    }

    @Test
    fun `a spent budget abandons a sweep instead of answering`() {
        // Both answers are claims: "no cycle" licenses consistency and "infeasible" licenses a refutation.
        // Neither may be produced by a sweep that stopped early.
        val cyclic = graph(2, Triple(1, 0, -1L), Triple(0, 1, -1L))

        assertNull(cyclic.negativeCycle(cancelled = { true }), "an abandoned sweep reports no cycle found")
        assertEquals(Potentials.Abandoned, cyclic.potentials(cancelled = { true }))
        assertNotNull(cyclic.negativeCycle(), "the same graph does hold a cycle when the sweep finishes")
    }

    @Test
    fun `shortest paths preserve reachability and direction`() {
        val g = graph(
            4,
            Triple(0, 1, 7L),
            Triple(1, 2, -2L),
            Triple(3, 0, -4L),
        )

        val forward = g.shortestPaths(0)
        val reverse = g.shortestPaths(0, reversed = true)

        assertTrue(forward is ShortestPaths.Found)
        assertTrue(reverse is ShortestPaths.Found)
        assertEquals(5L, forward.values[2])
        assertTrue(forward.reachable[2])
        assertEquals(-4L, reverse.values[3])
        assertTrue(reverse.reachable[3])
        assertTrue(!forward.reachable[3])
    }

    @Test
    fun `overflow abandons one shortest path without inventing a bound`() {
        val g = graph(3, Triple(0, 1, Long.MAX_VALUE), Triple(1, 2, 1L))

        val paths = g.shortestPaths(0)

        assertTrue(paths is ShortestPaths.Found)
        assertTrue(paths.reachable[1])
        assertEquals(Long.MAX_VALUE, paths.values[1])
        assertTrue(!paths.reachable[2])
    }

    @Test
    fun `shortest paths report only a reachable negative cycle`() {
        val reachable = graph(3, Triple(0, 1, 0L), Triple(1, 2, -1L), Triple(2, 1, 0L))
        val disconnected = graph(4, Triple(0, 1, 0L), Triple(2, 3, -1L), Triple(3, 2, 0L))

        assertEquals(ShortestPaths.Infeasible, reachable.shortestPaths(0))
        assertTrue(disconnected.shortestPaths(0) is ShortestPaths.Found)
    }

    @Test
    fun `a budget spent partway through one pass is noticed inside it`() {
        // Two vertices give three relaxation passes, so a poll that only ran per pass could not land
        // inside a pass over an edge set this size, and the sweep would run to completion first.
        val wide = DifferenceGraph(2)
        repeat(50_000) { wide.addEdge(0, 1, 1L) }
        var polls = 0

        val cycle = wide.negativeCycle(cancelled = { polls++ >= 2 })

        assertNull(cycle)
        assertTrue(polls < 50_000, "the sweep stopped on the budget rather than walking every edge")
        assertTrue(polls > 2, "the budget is looked at more than once per pass")
    }
}
