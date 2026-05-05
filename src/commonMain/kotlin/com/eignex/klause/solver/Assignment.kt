package com.eignex.klause.solver

import kotlin.random.Random

/** Packed-bit assignment over `numVars` Boolean variables. */
class Assignment(val numVars: Int) {
    private val bits: LongArray = LongArray((numVars + 63) ushr 6)

    operator fun get(variable: Int): Boolean =
        (bits[variable ushr 6] ushr (variable and 63)) and 1L == 1L

    operator fun set(variable: Int, value: Boolean) {
        val w = variable ushr 6
        val mask = 1L shl (variable and 63)
        bits[w] = if (value) bits[w] or mask else bits[w] and mask.inv()
    }

    fun flip(variable: Int) {
        val w = variable ushr 6
        bits[w] = bits[w] xor (1L shl (variable and 63))
    }

    fun randomize(rng: Random) {
        for (i in bits.indices) bits[i] = rng.nextLong()
        val tail = numVars and 63
        if (tail != 0) {
            val mask = (1L shl tail) - 1L
            bits[bits.size - 1] = bits[bits.size - 1] and mask
        }
    }

    fun copy(): Assignment {
        val c = Assignment(numVars)
        bits.copyInto(c.bits)
        return c
    }

    fun toBooleanArray(): BooleanArray = BooleanArray(numVars) { this[it] }
}
