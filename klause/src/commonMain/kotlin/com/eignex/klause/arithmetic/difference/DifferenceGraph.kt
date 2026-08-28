package com.eignex.klause.arithmetic.difference

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/** How often a relaxation sweep looks at the budget: often enough to land inside one pass over a large
 *  edge set, rarely enough that the check does not show up against the relaxation itself. */
private const val POLL_INTERVAL = 8192

/** Outcome of computing potentials over a difference graph. */
internal sealed interface Potentials {
    /** One potential per vertex, so the system is feasible. */
    class Found(val values: LongArray) : Potentials

    /** A negative cycle: the system has no solution. */
    data object Infeasible : Potentials

    /** The budget was spent before the sweeps settled, so nothing is claimed either way. */
    data object Abandoned : Potentials
}

/** Shortest paths from one real graph node. */
internal sealed interface ShortestPaths {
    /** Finite distances for the reachable nodes. */
    class Found(val values: LongArray, val reachable: BooleanArray) : ShortestPaths

    /** A negative cycle is reachable from the source. */
    data object Infeasible : ShortestPaths

    /** The budget was spent before the paths settled. */
    data object Abandoned : ShortestPaths
}

internal class DifferenceGraph(val numVars: Int) {
    private val from = IntArrayList()
    private val to = IntArrayList()
    private val weight = LongArrayList()

    val size: Int get() = from.size

    fun addEdge(source: Int, target: Int, bound: Long): Int {
        from.add(source)
        to.add(target)
        weight.add(bound)
        return from.size - 1
    }

    /**
     * An edge cycle of negative total weight, or null when none was found.
     *
     * A spent [cancelled] budget also reports null. That is the same weakening as a propagation that did
     * not finish — it claims no conflict, never that there is none — so a caller may treat null as
     * "nothing deduced" but never as proof of consistency.
     */
    fun negativeCycle(active: BooleanArray? = null, cancelled: () -> Boolean = { false }): IntArray? {
        val n = numVars
        if (n == 0 || size == 0) return null
        // The virtual source makes every distance start finite and equal, which is exactly what a
        // zero-weight edge to each vertex would do, without materialising the edges.
        val dist = LongArray(n)
        val predEdge = IntArray(n) { -1 }
        var changed = -1
        var untilPoll = POLL_INTERVAL
        repeat(n + 1) {
            if (cancelled()) return null
            changed = -1
            for (e in 0 until size) {
                if (--untilPoll <= 0) {
                    untilPoll = POLL_INTERVAL
                    if (cancelled()) return null
                }
                if (active != null && !active[e]) continue
                val u = from[e]
                val v = to[e]
                val relaxed = dist[u] + weight[e]
                // A relaxation that overflows is not a real improvement; the guard keeps a wrapped
                // sum from inventing a cycle that the constraints do not contain.
                if (addOverflows(dist[u], weight[e])) continue
                if (relaxed < dist[v]) {
                    dist[v] = relaxed
                    predEdge[v] = e
                    changed = v
                }
            }
            if (changed == -1) return null // a full pass with no improvement ⇒ no negative cycle
        }
        return extractCycle(changed, predEdge)
    }

    /** Vertex potentials witnessing feasibility, or why none were produced. */
    fun potentials(active: BooleanArray? = null, cancelled: () -> Boolean = { false }): Potentials {
        val dist = LongArray(numVars)
        var untilPoll = POLL_INTERVAL
        repeat(numVars) {
            if (cancelled()) return Potentials.Abandoned
            var changed = false
            for (e in 0 until size) {
                if (--untilPoll <= 0) {
                    untilPoll = POLL_INTERVAL
                    if (cancelled()) return Potentials.Abandoned
                }
                if (active != null && !active[e]) continue
                val u = from[e]
                val v = to[e]
                if (addOverflows(dist[u], weight[e])) continue
                val relaxed = dist[u] + weight[e]
                if (relaxed < dist[v]) {
                    dist[v] = relaxed
                    changed = true
                }
            }
            if (!changed) return Potentials.Found(dist)
        }
        return Potentials.Infeasible
    }

    /**
     * Shortest paths from [source], optionally following every edge backwards.
     *
     * Unreachable nodes have no distance; keeping that bit separately lets `Long.MAX_VALUE` remain a
     * representable path weight. Arithmetic overflow only drops a relaxation, which weakens a bound
     * computation without inventing one.
     */
    fun shortestPaths(
        source: Int,
        active: BooleanArray? = null,
        reversed: Boolean = false,
        cancelled: () -> Boolean = { false },
    ): ShortestPaths {
        require(source in 0 until numVars) { "source node $source is outside a $numVars-node graph" }
        val dist = LongArray(numVars)
        val reachable = BooleanArray(numVars)
        reachable[source] = true
        var untilPoll = POLL_INTERVAL
        repeat(numVars) {
            if (cancelled()) return ShortestPaths.Abandoned
            var changed = false
            for (e in 0 until size) {
                if (--untilPoll <= 0) {
                    untilPoll = POLL_INTERVAL
                    if (cancelled()) return ShortestPaths.Abandoned
                }
                if (active != null && !active[e]) continue
                val u = if (reversed) to[e] else from[e]
                val v = if (reversed) from[e] else to[e]
                if (!reachable[u] || addOverflows(dist[u], weight[e])) continue
                val candidate = dist[u] + weight[e]
                if (!reachable[v] || candidate < dist[v]) {
                    dist[v] = candidate
                    reachable[v] = true
                    changed = true
                }
            }
            if (!changed) return ShortestPaths.Found(dist, reachable)
        }
        return ShortestPaths.Infeasible
    }

    private fun extractCycle(seed: Int, predEdge: IntArray): IntArray? {
        var v = seed
        repeat(numVars) {
            val e = predEdge[v]
            if (e < 0) return null
            v = from[e]
        }
        val start = v
        val edges = IntArrayList()
        var cur = start
        do {
            val e = predEdge[cur]
            if (e < 0) return null
            edges.add(e)
            cur = from[e]
        } while (cur != start && edges.size <= size)
        return if (cur == start) edges.toIntArray() else null
    }

    private fun addOverflows(a: Long, b: Long): Boolean {
        val r = a + b
        return ((a xor r) and (b xor r)) < 0L
    }
}
