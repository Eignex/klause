package com.eignex.klause.propagation.difference

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * A system of difference constraints `x − y ≤ c` as a weighted digraph: the constraint is the edge
 * `y → x` of weight `c`, and a path's weight bounds the difference between its endpoints.
 *
 * The system is satisfiable over the integers exactly when the graph holds no negative cycle. A cycle of
 * total weight `w` sums its constraints to `0 ≤ w`, so `w < 0` is a contradiction and `w ≥ 0` leaves the
 * shortest-path potentials as a solution. Nothing here needs a variable to be bounded: the decision is
 * structural, over the whole of ℤ, which is what makes the fragment decidable without the finite search
 * box a general integer model has to invent.
 *
 * Edges are appended and never removed; a search that retracts an assertion rebuilds or masks rather than
 * deleting, so an edge's index stays a stable name for the constraint that produced it. That index is
 * what [negativeCycle] reports, so a conflict names exactly the asserted constraints that caused it.
 */
internal class DifferenceGraph(val numVars: Int) {
    private val from = IntArrayList()
    private val to = IntArrayList()
    private val weight = LongArrayList()

    /** Number of edges added so far; also the index the next [addEdge] returns. */
    val size: Int get() = from.size

    /**
     * Record `target − source ≤ bound`, returning the edge index that names it in a
     * [negativeCycle] explanation.
     */
    fun addEdge(source: Int, target: Int, bound: Long): Int {
        from.add(source)
        to.add(target)
        weight.add(bound)
        return from.size - 1
    }

    /**
     * The indices of the edges forming a negative cycle, or `null` when the system is satisfiable.
     *
     * Bellman–Ford relaxes from a virtual source joined to every variable at weight zero, so every
     * component is reached whether or not the graph is connected. A vertex still improving after
     * `numVars` rounds lies on (or downstream of) a negative cycle; walking the predecessor chain
     * `numVars` times lands inside the cycle, and following it to the first repeat extracts it.
     *
     * [active] masks the edges a search currently asserts — `null` means all of them — so a node can ask
     * about its own subset without the graph being rebuilt.
     */
    fun negativeCycle(active: BooleanArray? = null): IntArray? {
        val n = numVars
        if (n == 0 || size == 0) return null
        // The virtual source makes every distance start finite and equal, which is exactly what a
        // zero-weight edge to each vertex would do, without materialising the edges.
        val dist = LongArray(n)
        val predEdge = IntArray(n) { -1 }
        var changed = -1
        repeat(n + 1) {
            changed = -1
            for (e in 0 until size) {
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

    /** Walk back `numVars` steps to land inside the cycle, then round it once collecting its edges. */
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

    /** Whether `a + b` leaves [Long]; the potentials here are sums of user-supplied bounds. */
    private fun addOverflows(a: Long, b: Long): Boolean {
        val r = a + b
        return ((a xor r) and (b xor r)) < 0L
    }
}
