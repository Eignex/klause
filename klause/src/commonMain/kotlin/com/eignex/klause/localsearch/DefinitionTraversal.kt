package com.eignex.klause.localsearch

import com.eignex.klause.util.Bits
import com.eignex.klause.util.IntArrayList

// A state borrows the live node prefix until its synchronous move or probe returns.
internal class DefinitionTraversal(val network: InvariantNetwork) {
    private val marked = Bits(network.size)
    val nodes = IntArrayList()
    private var seedInt = -1
    private var seedBool = -1

    fun reuses(ints: IntArray, intCount: Int, bools: IntArray, boolCount: Int): Boolean =
        !network.hasDynamicReaders &&
            ((intCount == 1 && boolCount == 0 && seedInt >= 0 && seedInt == ints[0]) ||
                (boolCount == 1 && intCount == 0 && seedBool >= 0 && seedBool == bools[0]))

    fun remember(ints: IntArray, intCount: Int, bools: IntArray, boolCount: Int) {
        seedInt = if (intCount == 1 && boolCount == 0) ints[0] else -1
        seedBool = if (boolCount == 1 && intCount == 0) bools[0] else -1
    }

    fun clear() {
        seedInt = -1
        seedBool = -1
        for (position in 0 until nodes.size) marked.clear(nodes[position])
        nodes.clear()
    }

    fun add(node: Int) {
        if (marked.get(node)) return
        nodes.add(node)
        marked.set(node)
    }

    // Re-read the current word: evaluating a node can invalidate a later node in that same word.
    fun nextPending(pending: Bits, from: Int): Int {
        var word = from ushr 6
        if (word >= marked.words.size) return -1
        var candidates = marked.words[word] and pending.words[word] and (-1L shl (from and 63))
        while (candidates == 0L) {
            if (++word >= marked.words.size) return -1
            candidates = marked.words[word] and pending.words[word]
        }
        return (word shl 6) + candidates.countTrailingZeroBits()
    }
}
