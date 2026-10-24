package com.eignex.klause.factor.table.internals

import com.eignex.klause.util.IntArrayList

internal class MddLsFrontier(width: Int) {
    val changed = IntArrayList()
    val dirtyWords = LongArray(((width.toLong() + 63) ushr 6).toInt())
    var firstWord = Int.MAX_VALUE
        private set
    var lastWord = -1
        private set
    var edgeWork = 0L
        private set

    fun mark(state: Int, edges: Int) {
        val word = state ushr 6
        val mask = 1L shl (state and 63)
        if (dirtyWords[word] and mask != 0L) return
        dirtyWords[word] = dirtyWords[word] or mask
        firstWord = minOf(firstWord, word)
        lastWord = maxOf(lastWord, word)
        edgeWork += edges.toLong() + 1
    }

    fun resetBounds() {
        firstWord = Int.MAX_VALUE
        lastWord = -1
        edgeWork = 0L
    }
}
