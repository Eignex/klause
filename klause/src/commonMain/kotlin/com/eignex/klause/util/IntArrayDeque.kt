package com.eignex.klause.util

/**
 * Primitive double-ended queue specialised for `Int` — eliminates the per-element
 * boxing that `ArrayDeque<Int>` pays on the JVM. Used as the propagation worklist in
 * `PropagationState` (where every push/poll on the dirty-var queue would otherwise
 * box) and by any other propagator-internal BFS/DFS that holds dense int ids.
 *
 * Backed by a power-of-two ring buffer. [addFirst] / [addLast] grow the buffer when
 * needed (amortised O(1)); [removeFirst] / [removeLast] return raw `Int`. The empty
 * sentinel for the `*OrSentinel` variants is caller-provided.
 *
 * Not thread-safe. Iteration order is FIFO from [removeFirst] / LIFO from [removeLast].
 */
class IntArrayDeque(initialCapacity: Int = 8) {

    private var buf: IntArray = IntArray(nextPow2(maxOf(initialCapacity, 1)))
    private var head: Int = 0 // index of first element
    private var count: Int = 0 // number of elements

    val size: Int get() = count
    fun isEmpty(): Boolean = count == 0
    fun isNotEmpty(): Boolean = count != 0

    fun addLast(value: Int) {
        if (count == buf.size) grow()
        buf[(head + count) and (buf.size - 1)] = value
        count++
    }

    fun addFirst(value: Int) {
        if (count == buf.size) grow()
        head = (head - 1) and (buf.size - 1)
        buf[head] = value
        count++
    }

    /** Pop the front element. Throws [NoSuchElementException] on an empty deque. */
    fun removeFirst(): Int {
        if (count == 0) throw NoSuchElementException("IntArrayDeque is empty")
        val v = buf[head]
        head = (head + 1) and (buf.size - 1)
        count--
        return v
    }

    /** Pop the back element. Throws [NoSuchElementException] on an empty deque. */
    fun removeLast(): Int {
        if (count == 0) throw NoSuchElementException("IntArrayDeque is empty")
        count--
        return buf[(head + count) and (buf.size - 1)]
    }

    /** Pop the front element, or return [sentinel] if empty. Avoids the throw on the
     *  common "drain until empty" pattern. */
    fun removeFirstOr(sentinel: Int): Int {
        if (count == 0) return sentinel
        val v = buf[head]
        head = (head + 1) and (buf.size - 1)
        count--
        return v
    }

    fun clear() {
        head = 0
        count = 0
    }

    private fun grow() {
        val newCap = buf.size * 2
        val newBuf = IntArray(newCap)
        val mask = buf.size - 1
        for (i in 0 until count) newBuf[i] = buf[(head + i) and mask]
        buf = newBuf
        head = 0
    }

    private companion object {
        fun nextPow2(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }
    }
}
