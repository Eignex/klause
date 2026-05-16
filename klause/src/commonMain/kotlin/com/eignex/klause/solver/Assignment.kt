package com.eignex.klause.solver

import kotlin.random.Random

/**
 * Mutable mixed assignment over `numBoolVars` Boolean variables (packed into a [LongArray]) and
 * `numIntVars` integer variables (a plain [IntArray]). Bool and int variables live in separate
 * id spaces; a factor that touches both kinds names them through `boolVars` / `intVars` arrays.
 */
class Assignment(val numBoolVars: Int, val numIntVars: Int) {
    private val bits: LongArray = LongArray((numBoolVars + 63) ushr 6)
    private val ints: IntArray = IntArray(numIntVars)

    fun boolValue(varId: Int): Boolean =
        (bits[varId ushr 6] ushr (varId and 63)) and 1L == 1L

    fun setBool(varId: Int, value: Boolean) {
        val w = varId ushr 6
        val mask = 1L shl (varId and 63)
        bits[w] = if (value) bits[w] or mask else bits[w] and mask.inv()
    }

    fun flipBool(varId: Int) {
        val w = varId ushr 6
        bits[w] = bits[w] xor (1L shl (varId and 63))
    }

    fun intValue(varId: Int): Int = ints[varId]

    fun setInt(varId: Int, value: Int) {
        ints[varId] = value
    }

    fun randomize(rng: Random, intDomains: Array<IntDomain>) {
        for (i in bits.indices) bits[i] = rng.nextLong()
        val tail = numBoolVars and 63
        if (tail != 0) {
            val mask = (1L shl tail) - 1L
            bits[bits.size - 1] = bits[bits.size - 1] and mask
        }
        for (i in 0 until numIntVars) {
            val d = intDomains[i]
            ints[i] = d.min + rng.nextInt(d.size)
        }
    }

    fun snapshot(): Sample = Sample(
        bools = BooleanArray(numBoolVars) { boolValue(it) },
        ints = ints.copyOf(),
    )
}

/** Immutable assignment snapshot yielded by the solver. */
data class Sample(val bools: BooleanArray, val ints: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (other !is Sample) return false
        return bools.contentEquals(other.bools) && ints.contentEquals(other.ints)
    }
    override fun hashCode(): Int = 31 * bools.contentHashCode() + ints.contentHashCode()
}
