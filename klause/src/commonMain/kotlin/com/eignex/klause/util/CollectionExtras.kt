package com.eignex.klause.util

/** A fresh ascending copy of the elements. */
internal fun IntArrayList.toSortedIntArray(): IntArray = toIntArray().also { it.sort() }

/** A fresh ascending copy of the elements. */
internal fun LongArrayList.toSortedLongArray(): LongArray = toLongArray().also { it.sort() }

/** A fresh ascending copy of the members. */
internal fun IntHashSet.toSortedIntArray(): IntArray = toIntArray().also { it.sort() }

/** A fresh ascending copy of the members. */
internal fun LongHashSet.toSortedLongArray(): LongArray = toLongArray().also { it.sort() }

/** The map's keys as a fresh ascending array. */
internal fun MutableIntIntMap.sortedKeys(): IntArray {
    val keys = IntArray(size)
    var i = 0
    forEach { k, _ -> keys[i++] = k }
    keys.sort()
    return keys
}

internal fun MutableIntLongMap.sortedKeys(): IntArray {
    val keys = IntArray(size)
    var i = 0
    forEach { k, _ -> keys[i++] = k }
    keys.sort()
    return keys
}

internal fun MutableIntDoubleMap.sortedKeys(): IntArray {
    val keys = IntArray(size)
    var i = 0
    forEach { k, _ -> keys[i++] = k }
    keys.sort()
    return keys
}

internal fun <V> MutableIntObjectMap<V>.sortedKeys(): IntArray {
    val keys = IntArray(size)
    var i = 0
    forEach { k, _ -> keys[i++] = k }
    keys.sort()
    return keys
}
