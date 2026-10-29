package com.eignex.klause.util

/**
 * Primitive Int deque backed by a growing power-of-two ring buffer. End operations
 * are amortized O(1); [removeFirstOr] returns a caller-provided sentinel when empty.
 * Not thread-safe.
 */
internal class IntArrayDeque(initialCapacity: Int = 8) {

    private var buf: IntArray = IntArray(nextPow2(maxOf(initialCapacity, 1)))
    internal val allocatedBytes: Long get() = buf.size.toLong() * Int.SIZE_BYTES
    private var head: Int = 0
    private var count: Int = 0

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
