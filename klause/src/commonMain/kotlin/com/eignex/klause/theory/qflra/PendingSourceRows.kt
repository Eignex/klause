package com.eignex.klause.theory.qflra

import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation

internal class PendingSourceRows private constructor(private val words: LongArray) {
    fun asserted(index: Int) = Bits.clear(words, index)

    fun restored(index: Int) = Bits.set(words, index)

    fun visit(stop: Cancellation, action: (Int) -> Boolean): Boolean {
        for (word in words.indices) {
            if (!charge(stop, 1)) return false
            var pending = words[word]
            while (pending != 0L) {
                if (!charge(stop, 1)) return false
                val index = (word shl 6) + pending.countTrailingZeroBits()
                if (!action(index)) return false
                pending = pending and (pending - 1)
            }
        }
        return !stop()
    }

    companion object {
        private const val MAX_WORDS = 8192

        fun supports(size: Int): Boolean = size >= 0 && (size.toLong() + 63L) / 64L <= MAX_WORDS

        fun prepare(size: Int, stop: Cancellation): PendingSourceRows? {
            require(size >= 0)
            val count = (size.toLong() + 63L) / 64L
            if (!supports(size) || !charge(stop, count + 1L)) return null
            val words = LongArray(count.toInt())
            for (word in words.indices) {
                if (!charge(stop, 1)) return null
                words[word] = -1L
            }
            val tail = size and 63
            if (tail != 0) words[words.lastIndex] = (1L shl tail) - 1L
            return PendingSourceRows(words).takeUnless { stop() }
        }
    }
}

private fun charge(stop: Cancellation, units: Long): Boolean {
    if (stop()) return false
    stop.charge(units)
    return !stop()
}
