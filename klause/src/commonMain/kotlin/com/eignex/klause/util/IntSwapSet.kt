package com.eignex.klause.util

import kotlin.random.Random

/**
 * Dense-int set with O(1) add/remove and O(1) uniform random sampling.
 * Backed by a list of present elements and a parallel index map keyed by element id.
 * Element ids must be in [0, capacity).
 */
class IntSwapSet(capacity: Int) {
    private val elements = IntArrayList(capacity.coerceAtLeast(8))
    private val position = IntArray(capacity) { -1 }

    val size: Int get() = elements.size
    fun isEmpty(): Boolean = elements.size == 0

    fun contains(id: Int): Boolean = position[id] >= 0

    fun add(id: Int): Boolean {
        if (position[id] >= 0) return false
        position[id] = elements.size
        elements.add(id)
        return true
    }

    fun remove(id: Int): Boolean {
        val pos = position[id]
        if (pos < 0) return false
        val last = elements[elements.size - 1]
        elements[pos] = last
        position[last] = pos
        elements.removeAt(elements.size - 1)
        position[id] = -1
        return true
    }

    operator fun get(index: Int): Int = elements[index]

    fun random(rng: Random): Int = elements[rng.nextInt(elements.size)]

    fun toIntArray(): IntArray = elements.toIntArray()

    fun clear() {
        for (i in 0 until elements.size) position[elements[i]] = -1
        elements.clear()
    }

    inline fun forEach(action: (Int) -> Unit) {
        for (i in 0 until size) action(this[i])
    }
}
