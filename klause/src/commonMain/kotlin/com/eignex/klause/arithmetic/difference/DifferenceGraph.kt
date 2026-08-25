package com.eignex.klause.arithmetic.difference

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

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

    fun potentials(active: BooleanArray? = null): LongArray? {
        val dist = LongArray(numVars)
        repeat(numVars) {
            var changed = false
            for (e in 0 until size) {
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
            if (!changed) return dist
        }
        return null
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
