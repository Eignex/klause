package com.eignex.klause.arithmetic.difference

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal enum class DifferencePathCompletion { Completed, Abandoned, Declined }

internal class DifferencePathEdges(
    val source: IntArray,
    val target: IntArray,
    val weight: LongArray,
    val adjacencyStart: IntArray,
    val adjacencyEdge: IntArray,
)

internal class DifferencePathScratch(size: Int) {
    val distance = LongArray(size)
    val predecessor = IntArray(size)
    private val reached = IntArray(size)
    private val settled = IntArray(size)
    private val needed = IntArray(size)
    val heap = DifferenceLongHeap(size)
    var origin = -1
        private set
    var completion = DifferencePathCompletion.Declined
    private var stamp = 0

    fun begin(source: Int, endpoints: IntArray, stop: Cancellation): Int {
        completion = DifferencePathCompletion.Declined
        if (stamp == Int.MAX_VALUE) {
            stop.charge(reached.size * 3L)
            reached.fill(0)
            settled.fill(0)
            needed.fill(0)
            stamp = 0
        }
        stamp++
        origin = source
        var pending = 0
        for (node in endpoints) {
            stop.charge(1)
            if (needed[node] == stamp) continue
            needed[node] = stamp
            pending++
        }
        heap.clear(stop)
        distance[source] = 0L
        predecessor[source] = -1
        reached[source] = stamp
        heap.push(source, 0L, stop)
        return pending
    }

    fun settle(node: Int): Boolean {
        settled[node] = stamp
        return needed[node] == stamp
    }

    fun settled(node: Int): Boolean = settled[node] == stamp
    fun reached(node: Int): Boolean = reached[node] == stamp
    fun reach(node: Int) { reached[node] = stamp }

    @Suppress("ReturnCount")
    fun pathTo(node: Int, source: IntArray, stop: Cancellation = Cancellation.Never): IntArray? {
        if (completion != DifferencePathCompletion.Completed || !settled(node)) return null
        stop.charge(predecessor.size.toLong())
        if (stop()) return null
        val path = IntArrayList()
        var current = node
        while (current != origin) {
            stop.charge(1)
            if (stop() || path.size >= predecessor.size) return null
            val edge = predecessor[current]
            if (edge !in source.indices) return null
            path.add(edge)
            current = source[edge]
        }
        stop.charge(path.size.toLong())
        return if (stop()) null else path.toIntArray()
    }
}

@Suppress("LongParameterList", "ReturnCount")
internal fun differenceShortestPaths(
    edges: DifferencePathEdges,
    active: BooleanArray,
    potential: LongArray,
    origin: Int,
    needed: IntArray,
    scratch: DifferencePathScratch,
    stop: Cancellation = Cancellation.Never,
    skipped: BooleanArray? = null,
): DifferencePathCompletion {
    var pending = scratch.begin(origin, needed, stop)
    while (!scratch.heap.isEmpty() && pending > 0) {
        if (stop()) return DifferencePathCompletion.Abandoned.also { scratch.completion = it }
        stop.charge(1)
        val source = scratch.heap.pop(stop)
        if (scratch.settle(source)) pending--
        for (visit in edges.adjacencyStart[source] until edges.adjacencyStart[source + 1]) {
            stop.charge(1)
            if (stop()) return DifferencePathCompletion.Abandoned.also { scratch.completion = it }
            val edge = edges.adjacencyEdge[visit]
            if (!active[edge] || skipped?.get(edge) == true) continue
            if (edges.weight[edge] == Long.MIN_VALUE) return DifferencePathCompletion.Declined
            val target = edges.target[edge]
            if (scratch.settled(target)) continue
            val first = checkedDifferenceSum(potential[source], edges.weight[edge])
                ?: return DifferencePathCompletion.Declined
            val reduced = checkedDifferenceSubtract(first, potential[target])
                ?: return DifferencePathCompletion.Declined
            if (reduced < 0L) return DifferencePathCompletion.Declined
            val candidate = checkedDifferenceSum(scratch.distance[source], reduced)
                ?: return DifferencePathCompletion.Declined
            if (scratch.reached(target) && candidate >= scratch.distance[target]) continue
            scratch.reach(target)
            scratch.distance[target] = candidate
            scratch.predecessor[target] = edge
            scratch.heap.push(target, candidate, stop)
        }
    }
    val result = if (stop()) DifferencePathCompletion.Abandoned else DifferencePathCompletion.Completed
    scratch.completion = result
    return result
}

internal fun checkedDifferenceSum(a: Long, b: Long): Long? {
    val sum = a + b
    return if (((a xor sum) and (b xor sum)) < 0L) null else sum
}

internal fun checkedDifferenceSubtract(a: Long, b: Long): Long? {
    val difference = a - b
    return if (((a xor b) and (a xor difference)) < 0L) null else difference
}

/* Indexed binary min-heap over [Long] keys; `decrease-key` in place so a vertex is queued once. */
internal class DifferenceLongHeap(capacity: Int) {
    private val heap = IntArray(capacity)
    private val pos = IntArray(capacity) { -1 }
    private val keys = LongArray(capacity)
    private var size = 0

    fun isEmpty(): Boolean = size == 0

    fun clear(stop: Cancellation = Cancellation.Never) {
        stop.charge(size.toLong())
        for (i in 0 until size) pos[heap[i]] = -1
        size = 0
    }

    fun push(id: Int, key: Long, stop: Cancellation = Cancellation.Never) {
        val p = pos[id]
        if (p >= 0) {
            if (key >= keys[id]) return
            keys[id] = key
            siftUp(p, stop)
            return
        }
        keys[id] = key
        heap[size] = id
        pos[id] = size
        size++
        siftUp(size - 1, stop)
    }

    fun pop(stop: Cancellation = Cancellation.Never): Int {
        val top = heap[0]
        pos[top] = -1
        size--
        if (size > 0) {
            heap[0] = heap[size]
            pos[heap[0]] = 0
            siftDown(0, stop)
        }
        return top
    }

    private fun siftUp(start: Int, stop: Cancellation) {
        var i = start
        while (i > 0) {
            stop.charge(1)
            val parent = (i - 1) / 2
            if (keys[heap[parent]] <= keys[heap[i]]) break
            swap(i, parent)
            i = parent
        }
    }

    private fun siftDown(start: Int, stop: Cancellation) {
        var i = start
        while (true) {
            stop.charge(1)
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
