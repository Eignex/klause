package com.eignex.klause.simplex.basis

// One basis owner lends exact-size, uncleared arrays. Borrowed buffers cannot be lent or evicted;
// results that outlive a scope must copy their storage before its finally returns the buffer.
internal class BasisScratch {
    private val doubles = BasisBuffers(::DoubleArray, DoubleArray::size)
    private val integers = BasisBuffers(::IntArray, IntArray::size)

    inline fun <T> borrow(size: Int, block: (DoubleArray) -> T): T {
        val buffer = doubles.take(size)
        try {
            return block(buffer)
        } finally {
            doubles.release(buffer)
        }
    }

    inline fun <T> borrowI32(size: Int, block: (IntArray) -> T): T {
        val buffer = integers.take(size)
        try {
            return block(buffer)
        } finally {
            integers.release(buffer)
        }
    }
}

internal class BasisBuffers<A : Any>(private val allocate: (Int) -> A, private val sizeOf: (A) -> Int) {
    private val idle = ArrayList<A>()

    fun take(size: Int): A {
        require(size >= 0)
        val index = idle.indexOfFirst { sizeOf(it) == size }
        return if (index >= 0) idle.removeAt(index) else allocate(size)
    }

    fun release(buffer: A) {
        val size = sizeOf(buffer)
        if (idle.none { sizeOf(it) == size } && idleWidthCount() >= MAX_IDLE_WIDTHS) {
            val oldestSize = sizeOf(idle[0])
            for (i in idle.lastIndex downTo 0) if (sizeOf(idle[i]) == oldestSize) idle.removeAt(i)
        }
        idle.add(buffer)
    }

    private fun idleWidthCount(): Int {
        var count = 0
        for (i in idle.indices) {
            var seen = false
            for (j in 0 until i) {
                if (sizeOf(idle[j]) == sizeOf(idle[i])) {
                    seen = true
                    break
                }
            }
            if (!seen) count++
        }
        return count
    }
}

private const val MAX_IDLE_WIDTHS = 64
