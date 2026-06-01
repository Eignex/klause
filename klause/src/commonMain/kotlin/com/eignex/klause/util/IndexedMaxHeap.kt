package com.eignex.klause.util

/**
 * Binary max-heap with O(log n) `updateKey` keyed by an integer id in `0..capacity-1`. Lets
 * VSIDS-style variable pickers extract the highest-scoring variable without an O(n) linear
 * scan: bumps go through [updateKey] (sift-up if score grew, sift-down if it shrank), picks
 * go through [extractMax] (single sift-down on the new root) or [peekMax] + selective
 * [restore] when callers need to filter for some external "still alive" predicate
 * (`session.boolValue(v) == null` etc.) without losing the popped entry.
 *
 * Positions are tracked in [pos] so [updateKey] / [remove] are O(log n) — no linear search.
 * `pos[id] = -1` means the id isn't currently in the heap (after [extractMax] or [remove];
 * [restore] reinserts at the previously cached key).
 *
 * Ids and keys are stored separately so the heap doesn't allocate per-entry boxes — same
 * tactic as kumulant's primitive heaps. Capacity is fixed at construction; growing isn't
 * needed because every variable is registered up front.
 */
class IndexedMaxHeap(val capacity: Int) {

    private val heap = IntArray(capacity) // heap-position → id
    private val pos = IntArray(capacity) { -1 } // id → heap-position, or -1 if absent
    private val keys = DoubleArray(capacity) // id → current key
    var size: Int = 0
        private set

    fun contains(id: Int): Boolean = pos[id] >= 0
    fun keyOf(id: Int): Double = keys[id]

    /** Insert [id] with [key]. No-op if [id] is already present (use [updateKey] instead). */
    fun insert(id: Int, key: Double) {
        if (pos[id] >= 0) return
        keys[id] = key
        heap[size] = id
        pos[id] = size
        size++
        siftUp(size - 1)
    }

    /** Push id back in at its stored key. Used after [extractMax] / [remove] when the
     *  caller couldn't use the popped id and wants it back in contention. */
    fun restore(id: Int) {
        if (pos[id] >= 0) return
        heap[size] = id
        pos[id] = size
        size++
        siftUp(size - 1)
    }

    /** Change [id]'s key. Caller must have inserted [id]; calling on absent id is a no-op. */
    fun updateKey(id: Int, newKey: Double) {
        val p = pos[id]
        if (p < 0) return
        val old = keys[id]
        keys[id] = newKey
        if (newKey > old) {
            siftUp(p)
        } else if (newKey < old) {
            siftDown(p)
        }
    }

    /** Remove the id with the largest current key and return it. Returns -1 if empty. */
    fun extractMax(): Int {
        if (size == 0) return -1
        val top = heap[0]
        pos[top] = -1
        size--
        if (size > 0) {
            val moved = heap[size]
            heap[0] = moved
            pos[moved] = 0
            siftDown(0)
        }
        return top
    }

    /** Peek the id with the largest current key without removing it. -1 if empty. */
    fun peekMax(): Int = if (size > 0) heap[0] else -1

    /** Remove [id] from the heap regardless of position; no-op if absent. The cached key in
     *  [keys] is preserved so [restore] can re-add at the same priority. */
    fun remove(id: Int) {
        val p = pos[id]
        if (p < 0) return
        pos[id] = -1
        size--
        if (p == size) return
        val moved = heap[size]
        heap[p] = moved
        pos[moved] = p
        // The moved entry might be smaller or larger than its new neighbours; try both.
        siftDown(p)
        siftUp(p)
    }

    /** Scale every key by [factor] in place. O(n). Used by VSIDS-style rescales. Heap order
     *  is preserved by a uniform positive multiplier so no resifting is needed. */
    fun scaleKeys(factor: Double) {
        require(factor > 0.0) { "scale factor must be positive, got $factor" }
        for (i in 0 until capacity) keys[i] *= factor
    }

    /** Reset every id's key to [value] and rebuild the heap in ascending id order, so
     *  equal-key ties resolve to the lowest id (preserves the historical "id-order tie-break"
     *  of the linear-scan pickers ABS / DomWdeg replaced). Requires `size == capacity` —
     *  callers in the variable-picker world hold the full set between picks (skip buffers
     *  get restored before pick returns). O(n). */
    fun resetAllKeysInIdOrder(value: Double) {
        require(size == capacity) {
            "resetAllKeysInIdOrder requires full heap (size=$size, capacity=$capacity)"
        }
        for (i in 0 until capacity) {
            keys[i] = value
            heap[i] = i
            pos[i] = i
        }
    }

    private fun siftUp(start: Int) {
        var i = start
        val id = heap[i]
        val k = keys[id]
        while (i > 0) {
            val parent = (i - 1) ushr 1
            val pid = heap[parent]
            if (keys[pid] >= k) break
            heap[i] = pid
            pos[pid] = i
            i = parent
        }
        heap[i] = id
        pos[id] = i
    }

    private fun siftDown(start: Int) {
        var i = start
        val id = heap[i]
        val k = keys[id]
        val n = size
        while (true) {
            val left = (i shl 1) + 1
            if (left >= n) break
            val right = left + 1
            var pick = left
            var pickKey = keys[heap[left]]
            if (right < n) {
                val rk = keys[heap[right]]
                if (rk > pickKey) {
                    pick = right
                    pickKey = rk
                }
            }
            if (pickKey <= k) break
            val cid = heap[pick]
            heap[i] = cid
            pos[cid] = i
            i = pick
        }
        heap[i] = id
        pos[id] = i
    }
}
