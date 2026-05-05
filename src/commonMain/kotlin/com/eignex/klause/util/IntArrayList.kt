package com.eignex.klause.util

class IntArrayList(initialCapacity: Int = 8) {
    private var data: IntArray = IntArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]

    operator fun set(index: Int, value: Int) {
        data[index] = value
    }

    fun removeAt(index: Int) {
        data[index] = data[--size]
    }

    fun clear() {
        size = 0
    }

    fun toIntArray(): IntArray = data.copyOf(size)

    inline fun forEach(action: (Int) -> Unit) {
        for (i in 0 until size) action(this[i])
    }
}
