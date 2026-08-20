package com.eignex.klause.propagation.difference

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * A difference-constraint system maintained under assertion and retraction, carrying a feasible
 * potential function alongside it.
 *
 * The potential π satisfies `π(u) + w ≥ π(v)` for every asserted edge `u → v` of weight `w`. That is
 * exactly the statement that `x ↦ π(x)` solves the asserted subsystem, so maintaining π *is* the
 * consistency check — and retracting an edge can never break it, which is why the structure needs no
 * trail of its own and survives backtracking untouched. Only [assertEdge] does work, and it touches
 * only the vertices whose potential actually has to move rather than re-deriving the whole system.
 *
 * The same invariant makes the reduced weight `π(u) + w − π(v)` non-negative, so [shortestPathsFrom]
 * can run Dijkstra over a system whose real weights are negative (Johnson's transformation). Those
 * shortest paths are what let a consumer see a cycle *before* the edge closing it is asserted.
 *
 * Scott Cotton and Oded Maler, "Fast and Flexible Difference Constraint Propagation for DPLL(T)",
 * SAT 2006, LNCS 4121.
 */
internal class IncrementalDifferenceGraph(
    val numNodes: Int,
    private val source: IntArray,
    private val target: IntArray,
    private val weight: LongArray,
    /**
     * Edges stating a declared range rather than a row, every one of them incident to the constant node.
     * They stay in the system — the potential has to stay feasible for them, and a cycle through them is
     * a real conflict — but [shortestPathsFrom] does not walk them, because doing so makes that node a
     * hub and every search spans the graph (#1529). [refreshZeroDistances] is where they are traversed,
     * twice per sweep instead of once per head.
     */
    private val hub: BooleanArray = BooleanArray(source.size),
) {
    val numEdges: Int get() = source.size

    /**
     * Whether the system's numbers leave room for potentials at all. A potential is a sum of up to
     * [numNodes] edge weights, so a system whose weights cannot be summed that many times inside [Long]
     * gets no deduction from this structure rather than one drawn from a wrapped sum.
     */
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
    private val heap = LongMinHeap(numNodes)

    // Query scratch, stamped so a new search need not clear the arrays.
    private val dist = LongArray(numNodes)
    private val distPred = IntArray(numNodes)
    private val reachedStamp = IntArray(numNodes)
    private val settledStamp = IntArray(numNodes)
    private val neededStamp = IntArray(numNodes)
    private var stamp = 0
    private var queryOrigin = -1

    // Reverse adjacency, for the search that measures distances *to* the constant node.
    private val radjStart = IntArray(numNodes + 1)
    private val radjEdge = IntArray(source.size)

    // Distances to and from the constant node, refreshed once per sweep and read per head. Held apart
    // from the query scratch above precisely because a per-head search must not clobber them: the
    // predecessor chains are what recover the guards on a route through that node.
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

    /**
     * The potential of [node] — a value the asserted subsystem admits for it. Everything else here rests
     * on `π(u) + w ≥ π(v)` holding for every asserted edge, so this is the handle a test needs to check
     * that invariant directly rather than through its consequences.
     */
    fun potentialOf(node: Int): Long = potential[node]

    /**
     * Vertices the last [assertEdge] settled. The scan order is what bounds this by [numNodes]; a test
     * reads it because an order that re-settles a vertex still reaches the right answer, only slower, so
     * the cost is the sole observable difference between a monotone scan and a non-monotone one.
     */
    var settlements: Int = 0
        private set

    /** Drop [edge] from the asserted system. The potential stays feasible for a smaller system. */
    fun retract(edge: Int) {
        active[edge] = false
    }

    /**
     * Add [edge] to the asserted system, returning `null` when it stays consistent. On a negative cycle
     * the edge is left out and the potential rolled back, so the structure still describes the system
     * without it, and the returned edge indices are the cycle that names the conflict.
     *
     * A system that is not [usable] admits nothing at all: the edge stays out and the answer is always
     * "consistent", since a potential over those weights could not be maintained.
     */
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

    /**
     * Settle the pending potential decreases, stopping at [origin] — reaching it means the decrease came
     * round to the new edge's own tail, which is a negative cycle.
     *
     * The queue is keyed by the pending decrease γ itself, not by the resulting potential. Every edge
     * other than the new one was feasible before, `π(s) + w ≥ π(t)`, so a decrease propagated along it
     * is never larger than the one that caused it: γ is non-decreasing along the scan, and each vertex
     * settles once. Keying by `π + γ` loses that on a negative weight and re-queues vertices instead —
     * the answer stays right, because a vertex whose potential must fall further is simply queued
     * again by the comparison below, but the linear settle bound is gone.
     */
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

    /** The cycle through [origin] recorded in the predecessor chain the repair laid down. */
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

    /**
     * Shortest paths from [origin] over the asserted edges, run far enough to settle every vertex in
     * [needed]. Read back with [distanceTo] and [pathTo]; a later call invalidates both.
     */
    fun shortestPathsFrom(origin: Int, needed: IntArray) {
        stamp++
        queryOrigin = origin
        var pending = 0
        for (n in needed) {
            if (neededStamp[n] == stamp) continue
            neededStamp[n] = stamp
            pending++
        }
        heap.clear()
        dist[origin] = 0L
        distPred[origin] = -1
        reachedStamp[origin] = stamp
        heap.push(origin, 0L)
        while (!heap.isEmpty() && pending > 0) {
            val s = heap.pop()
            settledStamp[s] = stamp
            if (neededStamp[s] == stamp) pending--
            relaxFrom(s)
        }
    }

    private fun relaxFrom(s: Int) {
        for (i in adjStart[s] until adjStart[s + 1]) {
            val e = adjEdge[i]
            if (!active[e] || hub[e]) continue
            val t = target[e]
            if (settledStamp[t] == stamp) continue
            // Johnson's reduced weight: non-negative because the potential is feasible.
            val reduced = potential[s] + weight[e] - potential[t]
            val candidate = dist[s] + reduced
            if (reachedStamp[t] == stamp && candidate >= dist[t]) continue
            reachedStamp[t] = stamp
            dist[t] = candidate
            distPred[t] = e
            heap.push(t, candidate)
        }
    }

    /**
     * Refresh the distances to and from [zero], over the whole system including the declared-range edges.
     *
     * Every path this structure can derive either avoids [zero] — in which case [shortestPathsFrom]
     * already has it — or passes through it exactly once, since a second visit encloses a cycle that is
     * either negative, and so a conflict, or non-negative, and so removable. Splitting at that single
     * visit is what makes two searches per sweep enough to keep the deductions exact while every
     * per-head search stays inside the rows.
     */
    fun refreshZeroDistances(zero: Int): Boolean {
        zeroStamp++
        zeroNode = zero
        search(zero, outDist, outPred, outStamp, forward = true)
        search(zero, inDist, inPred, inStamp, forward = false)
        // Reported as changed or not so a caller can tell whether a route through this node could have
        // moved for some pair it is not otherwise revisiting.
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

    /** Dijkstra over Johnson's reduced weights, settling every vertex it can reach. */
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
                // Non-negative in either direction of travel: it is a property of the edge, not of the
                // way the search walks it.
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

    /** Weight of the shortest path from [node] to the constant node, or [UNREACHABLE]. */
    fun distanceToZeroFrom(node: Int): Long {
        if (inStamp[node] != zeroStamp) return UNREACHABLE
        return inDist[node] + potential[zeroNode] - potential[node]
    }

    /** Weight of the shortest path from the constant node to [node], or [UNREACHABLE]. */
    fun distanceFromZeroTo(node: Int): Long {
        if (outStamp[node] != zeroStamp) return UNREACHABLE
        return outDist[node] - potential[zeroNode] + potential[node]
    }

    /** Edge indices of the shortest path from [node] to the constant node. */
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

    /** Edge indices of the shortest path from the constant node to [node]. */
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

    /** Weight of the shortest path from the last [shortestPathsFrom] origin to [node], or [UNREACHABLE]. */
    fun distanceTo(node: Int): Long {
        if (settledStamp[node] != stamp) return UNREACHABLE
        return dist[node] - potential[queryOrigin] + potential[node]
    }

    /** Edge indices of that shortest path, tail first. Empty when [node] is the origin itself. */
    fun pathTo(node: Int): IntArray {
        val edges = IntArrayList()
        var cur = node
        while (cur != queryOrigin) {
            val e = distPred[cur]
            if (e < 0) break
            edges.add(e)
            cur = source[e]
        }
        return edges.toIntArray()
    }

    internal companion object {
        /** [distanceTo] for a vertex no asserted path reaches. */
        const val UNREACHABLE: Long = Long.MAX_VALUE

        /**
         * The largest edge weight a system over [numNodes] vertices can carry.
         *
         * A potential is a shortest-path weight, so it stays within [numNodes] edge weights of zero; the
         * reduced weights and path lengths built on top of it add a further constant factor. Exposed so
         * the decision to post the factor at all can be made against the same rule the structure applies.
         */
        fun weightRoom(numNodes: Int): Long = Long.MAX_VALUE / (8L * (numNodes + 1).toLong())
    }
}

/** Indexed binary min-heap over [Long] keys; `decrease-key` in place so a vertex is queued once. */
private class LongMinHeap(capacity: Int) {
    private val heap = IntArray(capacity)
    private val pos = IntArray(capacity) { -1 }
    private val keys = LongArray(capacity)
    private var size = 0

    fun isEmpty(): Boolean = size == 0

    fun clear() {
        for (i in 0 until size) pos[heap[i]] = -1
        size = 0
    }

    fun push(id: Int, key: Long) {
        val p = pos[id]
        if (p >= 0) {
            if (key >= keys[id]) return
            keys[id] = key
            siftUp(p)
            return
        }
        keys[id] = key
        heap[size] = id
        pos[id] = size
        size++
        siftUp(size - 1)
    }

    fun pop(): Int {
        val top = heap[0]
        pos[top] = -1
        size--
        if (size > 0) {
            heap[0] = heap[size]
            pos[heap[0]] = 0
            siftDown(0)
        }
        return top
    }

    private fun siftUp(start: Int) {
        var i = start
        while (i > 0) {
            val parent = (i - 1) / 2
            if (keys[heap[parent]] <= keys[heap[i]]) break
            swap(i, parent)
            i = parent
        }
    }

    private fun siftDown(start: Int) {
        var i = start
        while (true) {
            val left = 2 * i + 1
            if (left >= size) break
            val right = left + 1
            val child = if (right < size && keys[heap[right]] < keys[heap[left]]) right else left
            if (keys[heap[i]] <= keys[heap[child]]) break
            swap(i, child)
            i = child
        }
    }

    private fun swap(a: Int, b: Int) {
        val x = heap[a]
        val y = heap[b]
        heap[a] = y
        heap[b] = x
        pos[y] = a
        pos[x] = b
    }
}
