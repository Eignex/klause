package com.eignex.klause.util

// Small shared affordances over the primitive collections, factored out of the recurring call-site
// idioms: producing an ascending snapshot, and pulling a map's keys out in sorted order. Each builds
// on the collection's own public API, so no boxing is introduced.

/** A fresh ascending copy of the elements. Collapses `list.toIntArray().also { it.sort() }`. */
internal fun IntArrayList.toSortedIntArray(): IntArray = toIntArray().also { it.sort() }

/** A fresh ascending copy of the elements. Collapses `list.toLongArray().also { it.sort() }`. */
internal fun LongArrayList.toSortedLongArray(): LongArray = toLongArray().also { it.sort() }

/** A fresh ascending copy of the members. Collapses `set.toIntArray().also { it.sort() }`. */
internal fun IntHashSet.toSortedIntArray(): IntArray = toIntArray().also { it.sort() }

/** A fresh ascending copy of the members. Collapses `set.toLongArray().also { it.sort() }`. */
internal fun LongHashSet.toSortedLongArray(): LongArray = toLongArray().also { it.sort() }

/** The map's keys as a fresh ascending array. Collapses the `IntArray(size)` + `forEach` + `sort`
 *  idiom that call sites use to emit key-sorted parallel arrays. */
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
