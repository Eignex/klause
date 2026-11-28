package com.eignex.klause.propagation.difference

import com.eignex.klause.arithmetic.difference.DifferenceLongHeap
import com.eignex.klause.arithmetic.difference.DifferencePathCompletion
import com.eignex.klause.arithmetic.difference.DifferencePathEdges
import com.eignex.klause.arithmetic.difference.DifferencePathScratch
import com.eignex.klause.arithmetic.difference.differenceShortestPaths
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList

// A difference-constraint system under assertion and retraction with a feasible potential π:
// `π(u) + w ≥ π(v)` for every asserted edge `u → v` of weight w. Maintaining π is the consistency check,
// and retraction can never break it, so the structure needs no trail.
//
// The invariant makes reduced weights `π(u) + w − π(v)` non-negative, so shortest paths run Dijkstra
// over negative real weights (D. B. Johnson, "Efficient Algorithms for Shortest Paths in Sparse
// Networks", JACM 24(1), 1977, https://doi.org/10.1145/321992.321993).
//
// Scott Cotton and Oded Maler, "Fast and Flexible Difference Constraint Propagation for DPLL(T)",
// SAT 2006, LNCS 4121, https://doi.org/10.1007/11814948_19.
internal class IncrementalDifferenceGraph(
    val numNodes: Int,
    private val source: IntArray,
    private val target: IntArray,
    private val weight: LongArray,
    // Declared-range edges, all incident to the constant node. They stay in the system but only
    // refreshZeroDistances walks them; walking them per head would make that node a hub.
    private val hub: BooleanArray = BooleanArray(source.size),
) {
    val numEdges: Int get() = source.size

    // False when weights cannot be summed numNodes times inside Long; such a system yields no deductions
    // rather than wrapped ones.
    val usable: Boolean

    private val potential = LongArray(numNodes)
    private val active = BooleanArray(source.size)

    // Adjacency by source vertex, built once: the edge set is fixed and only activity varies.
    private val adjStart = IntArray(numNodes + 1)
    private val adjEdge = IntArray(source.size)

    // Repair scratch: γ is the pending potential decrease of a vertex, zero when it has none.
    private val gamma = LongArray(numNodes)
    private val predEdge = IntArray(numNodes)
    private val touched = IntArrayList()
    private val touchedPotential = LongArrayList()
    private val queued = IntArrayList()
    private val heap = DifferenceLongHeap(numNodes)

    private val query = DifferencePathScratch(numNodes)
    private val queryEdges = DifferencePathEdges(source, target, weight, adjStart, adjEdge)

    // Reverse adjacency, for the search that measures distances *to* the constant node.
    private val radjStart = IntArray(numNodes + 1)
    private val radjEdge = IntArray(source.size)

    // Distances to and from the constant node, refreshed once per sweep. Kept apart from the query scratch
    // so a per-head search cannot clobber the predecessor chains that recover a route's guards.
    private val outDist = LongArray(numNodes)
    private val outPred = IntArray(numNodes)
    private val outStamp = IntArray(numNodes)
    private val inDist = LongArray(numNodes)
    private val inPred = IntArray(numNodes)
    private val inStamp = IntArray(numNodes)
    private var zeroStamp = 0
    private var zeroNode = -1
    private val prevOut = LongArray(numNodes) { UNREACHABLE }
    private val prevIn = LongArray(numNodes) { UNREACHABLE }

    init {
        var maxAbs = 0L
        for (w in weight) {
            val a = if (w < 0L) -w else w
            if (a > maxAbs) maxAbs = a
        }
        usable = numNodes > 0 && maxAbs <= weightRoom(numNodes)
        for (e in source.indices) adjStart[source[e] + 1]++
        for (v in 1..numNodes) adjStart[v] += adjStart[v - 1]
        val fill = adjStart.copyOf()
        for (e in source.indices) adjEdge[fill[source[e]]++] = e
        for (e in target.indices) radjStart[target[e] + 1]++
        for (v in 1..numNodes) radjStart[v] += radjStart[v - 1]
        val rfill = radjStart.copyOf()
        for (e in target.indices) radjEdge[rfill[target[e]]++] = e
    }

    fun isActive(edge: Int): Boolean = active[edge]

    fun potentialOf(node: Int): Long = potential[node]

    // Vertices the last assertEdge settled; bounded by numNodes only under a monotone scan order.
    var settlements: Int = 0
        private set

    fun retract(edge: Int) {
        active[edge] = false
    }

    // Returns null when the system stays consistent, else the negative cycle's edges, with the edge left
    // out and the potential rolled back. An unusable system admits nothing and always answers null.
    fun assertEdge(edge: Int): IntArray? {
        if (!usable) return null
        val u = source[edge]
        val v = target[edge]
        active[edge] = true
        if (potential[u] + weight[edge] - potential[v] >= 0L) return null
        touched.clear()
        touchedPotential.clear()
        queued.clear()
        heap.clear()
        settlements = 0
        gamma[v] = potential[u] + weight[edge] - potential[v]
        predEdge[v] = edge
        queued.add(v)
        heap.push(v, gamma[v])
        val cycle = repair(u)
        if (cycle != null) {
            for (i in 0 until touched.size) potential[touched[i]] = touchedPotential[i]
            active[edge] = false
        }
        for (i in 0 until queued.size) gamma[queued[i]] = 0L
        return cycle
    }

    // Reaching origin means the decrease came round to the new edge's tail: a negative cycle.
    //
    // The queue is keyed by the pending decrease γ, not by `π + γ`. Every other edge was feasible before,
    // so a propagated decrease never exceeds its cause and each vertex settles once; keying by `π + γ`
    // re-queues vertices on negative weights.
    private fun repair(origin: Int): IntArray? {
        while (!heap.isEmpty()) {
            val s = heap.pop()
            if (s == origin) return cycleFrom(origin)
            settlements++
            touched.add(s)
            touchedPotential.add(potential[s])
            potential[s] += gamma[s]
            gamma[s] = 0L
            for (i in adjStart[s] until adjStart[s + 1]) {
                val e = adjEdge[i]
                if (!active[e]) continue
                val t = target[e]
                val decrease = potential[s] + weight[e] - potential[t]
                if (decrease >= gamma[t]) continue
                if (gamma[t] == 0L) queued.add(t)
                gamma[t] = decrease
                predEdge[t] = e
                heap.push(t, decrease)
            }
        }
        return null
    }

    private fun cycleFrom(origin: Int): IntArray {
        val edges = IntArrayList()
        var cur = origin
        do {
            val e = predEdge[cur]
            edges.add(e)
            cur = source[e]
        } while (cur != origin && edges.size <= numEdges)
        return edges.toIntArray()
    }

    // Settles every vertex in needed; read back with distanceTo and pathTo until the next call.
    fun shortestPathsFrom(origin: Int, needed: IntArray) {
        differenceShortestPaths(queryEdges, active, potential, origin, needed, query, skipped = hub)
    }

    // A shortest path visits zero at most once, so splitting there keeps deductions exact while per-head
    // searches skip the declared-range edges. Returns whether any distance moved.
    fun refreshZeroDistances(zero: Int): Boolean {
        zeroStamp++
        zeroNode = zero
        search(zero, outDist, outPred, outStamp, forward = true)
        search(zero, inDist, inPred, inStamp, forward = false)
        var changed = false
        for (v in 0 until numNodes) {
            val out = distanceFromZeroTo(v)
            if (out != prevOut[v]) {
                prevOut[v] = out
                changed = true
            }
            val into = distanceToZeroFrom(v)
            if (into != prevIn[v]) {
                prevIn[v] = into
                changed = true
            }
        }
        return changed
    }

    private fun search(origin: Int, d: LongArray, pred: IntArray, seen: IntArray, forward: Boolean) {
        heap.clear()
        d[origin] = 0L
        pred[origin] = -1
        seen[origin] = zeroStamp
        heap.push(origin, 0L)
        while (!heap.isEmpty()) {
            val s = heap.pop()
            val start = if (forward) adjStart[s] else radjStart[s]
            val end = if (forward) adjStart[s + 1] else radjStart[s + 1]
            for (i in start until end) {
                val e = if (forward) adjEdge[i] else radjEdge[i]
                if (!active[e]) continue
                val t = if (forward) target[e] else source[e]
                // Non-negative in either direction of travel.
                val reduced = potential[source[e]] + weight[e] - potential[target[e]]
                val candidate = d[s] + reduced
                if (seen[t] == zeroStamp && candidate >= d[t]) continue
                seen[t] = zeroStamp
                d[t] = candidate
                pred[t] = e
                heap.push(t, candidate)
            }
        }
    }

    fun distanceToZeroFrom(node: Int): Long {
        if (inStamp[node] != zeroStamp) return UNREACHABLE
        return inDist[node] + potential[zeroNode] - potential[node]
    }

    fun distanceFromZeroTo(node: Int): Long {
        if (outStamp[node] != zeroStamp) return UNREACHABLE
        return outDist[node] - potential[zeroNode] + potential[node]
    }

    fun pathToZeroFrom(node: Int): IntArray {
        val edges = IntArrayList()
        var cur = node
        while (cur != zeroNode) {
            val e = inPred[cur]
            if (e < 0 || inStamp[cur] != zeroStamp) break
            edges.add(e)
            cur = target[e]
        }
        return edges.toIntArray()
    }

    fun pathFromZeroTo(node: Int): IntArray {
        val edges = IntArrayList()
        var cur = node
        while (cur != zeroNode) {
            val e = outPred[cur]
            if (e < 0 || outStamp[cur] != zeroStamp) break
            edges.add(e)
            cur = source[e]
        }
        return edges.toIntArray()
    }

    fun distanceTo(node: Int): Long {
        if (query.completion != DifferencePathCompletion.Completed || !query.settled(node)) return UNREACHABLE
        return query.distance[node] - potential[query.origin] + potential[node]
    }

    // Tail first; empty when node is the origin.
    fun pathTo(node: Int): IntArray = query.pathTo(node, source) ?: intArrayOf()

    internal companion object {
        const val UNREACHABLE: Long = Long.MAX_VALUE

        // A potential stays within numNodes edge weights of zero; reduced weights and path lengths add a
        // constant factor.
        fun weightRoom(numNodes: Int): Long = Long.MAX_VALUE / (8L * (numNodes + 1).toLong())
    }
}
